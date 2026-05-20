package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cohere Command R / R+ LLM provider.
 *
 * <p>Uses the Cohere v2 chat API ({@code /v2/chat}). Supports streaming via NDJSON.
 * Cohere has its own message format and tool-call schema distinct from OpenAI.
 *
 * <p>Usage:
 * <pre>{@code
 * registry.registerCohere("cohere", System.getenv("COHERE_API_KEY"));
 * ChatRequest req = ChatRequest.builder()
 *     .model("command-r-plus-08-2024")
 *     ...build();
 * }</pre>
 */
public final class CohereProvider implements LlmClient {

    private static final String BASE_URL = "https://api.cohere.com";

    private final String providerName;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CohereProvider(
        @NonNull String providerName,
        @NonNull String apiKey,
        @NonNull HttpClient httpClient,
        @NonNull ObjectMapper objectMapper,
        @NonNull RetryPolicy retryPolicy,
        @NonNull CircuitBreaker circuitBreaker,
        @NonNull ExecutorService executor
    ) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.executor = executor;
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        if (closed.get()) throw new IllegalStateException("Provider is closed");
        if (!circuitBreaker.allowsRequest()) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() {}
                });
                subscriber.onError(new RuntimeException("Circuit breaker is OPEN for provider " + providerName));
            };
        }
        return new CohereStreamPublisher(request, cancellationToken);
    }

    @Override
    public @NonNull ChatResponse complete(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder content = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicLong inputTokens = new AtomicLong(0);
        AtomicLong outputTokens = new AtomicLong(0);

        stream(request, cancellationToken).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) {
                switch (event) {
                    case StreamEvent.Token t -> content.append(t.token());
                    case StreamEvent.Finish f -> {
                        finishReason.set(f.finishReason());
                        inputTokens.set(f.promptTokens());
                        outputTokens.set(f.completionTokens());
                    }
                    case StreamEvent.Error e -> future.completeExceptionally(new RuntimeException(e.errorMessage()));
                    default -> {}
                }
            }
            @Override public void onError(Throwable t) { future.completeExceptionally(t); }
            @Override public void onComplete() {
                future.complete(new ChatResponse(
                    UUID.randomUUID().toString(), request.model(), providerName,
                    Message.assistant(content.toString()),
                    new TokenCount((int) inputTokens.get(), (int) outputTokens.get(), "cohere"),
                    Duration.ZERO, finishReason.get(), null, null, Map.of()
                ));
            }
        });

        try {
            return future.get(120L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Cohere request timed out", e);
        } catch (Exception e) {
            throw new RuntimeException("Cohere request failed", e);
        }
    }

    @Override
    public @NonNull HealthStatus health() {
        if (circuitBreaker.isOpen()) return HealthStatus.UNAVAILABLE;
        return HealthStatus.HEALTHY;
    }

    @Override
    public @NonNull String providerName() { return providerName; }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    // ---- Inner: Stream Publisher ----

    private final class CohereStreamPublisher implements Flow.Publisher<StreamEvent> {
        private final ChatRequest request;
        private final CancellationToken cancellationToken;

        CohereStreamPublisher(ChatRequest request, CancellationToken cancellationToken) {
            this.request = request;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            AtomicLong demand = new AtomicLong(0);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    demand.getAndUpdate(d -> { long s = d + n; return s < 0 ? Long.MAX_VALUE : s; });
                }
                @Override public void cancel() { cancelled.set(true); }
            });
            try {
                executor.submit(() -> {
                    try {
                        executeWithRetry(subscriber, cancelled, demand);
                        if (!cancelled.get()) subscriber.onComplete();
                    } catch (Exception e) {
                        if (!cancelled.get()) subscriber.onError(e);
                    }
                });
            } catch (Exception e) {
                if (!cancelled.get()) subscriber.onError(e);
            }
        }

        private void executeWithRetry(Flow.Subscriber<? super StreamEvent> subscriber,
                                      AtomicBoolean cancelled, AtomicLong demand) throws Exception {
            int attempt = 0;
            Exception lastError = null;
            while (attempt < retryPolicy.maxAttempts()) {
                if (cancelled.get() || cancellationToken.isCancelled()) throw new CancellationException("cancelled");
                try {
                    HttpRequest httpRequest = buildRequest(request);
                    HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 200) {
                        circuitBreaker.recordSuccess();
                        try (InputStream stream = response.body()) {
                            parseNdjsonStream(stream, subscriber, cancelled, demand);
                        }
                        return;
                    }
                    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    lastError = new RuntimeException("HTTP " + response.statusCode() + ": " + body);
                    if (!retryPolicy.isRetryable(response.statusCode())) throw lastError;
                    circuitBreaker.recordFailure();
                } catch (IOException | InterruptedException e) {
                    lastError = e;
                    circuitBreaker.recordFailure();
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                attempt++;
                if (attempt < retryPolicy.maxAttempts()) Thread.sleep(retryPolicy.computeDelay(attempt).toMillis());
            }
            throw lastError != null ? lastError : new RuntimeException("Max retries exceeded");
        }

        /**
         * Cohere v2 streams NDJSON, not SSE. Each line is a JSON event with {@code "type"}.
         */
        private void parseNdjsonStream(InputStream stream, Flow.Subscriber<? super StreamEvent> subscriber,
                                       AtomicBoolean cancelled, AtomicLong demand) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && !cancelled.get()) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        JsonNode event = objectMapper.readTree(line);
                        String type = event.path("type").asText("");
                        switch (type) {
                            case "content-delta" -> {
                                String token = event.path("delta").path("message").path("content").path("text").asText("");
                                if (!token.isEmpty()) emit(subscriber, new StreamEvent.Token(token, 0, null), demand, cancelled);
                            }
                            case "message-end" -> {
                                JsonNode usage = event.path("delta").path("usage").path("billed_units");
                                int inputTokens = usage.path("input_tokens").asInt(0);
                                int outputTokens = usage.path("output_tokens").asInt(0);
                                String reason = event.path("delta").path("finish_reason").asText("stop");
                                emit(subscriber, new StreamEvent.Finish(reason, inputTokens, outputTokens), demand, cancelled);
                            }
                            case "tool-call-start" -> {
                                JsonNode tool = event.path("delta").path("message").path("tool_calls");
                                if (tool.has("id") && tool.has("function")) {
                                    String toolId = tool.get("id").asText();
                                    String toolName = tool.path("function").path("name").asText();
                                    emit(subscriber, new StreamEvent.ToolCallStart(toolId, toolName, Map.of()), demand, cancelled);
                                }
                            }
                            case "tool-call-delta" -> {
                                String toolId = event.path("index").asText("0");
                                String argFrag = event.path("delta").path("message").path("tool_calls").path("function").path("arguments").asText("");
                                if (!argFrag.isEmpty()) emit(subscriber, new StreamEvent.ToolCallDelta(toolId, "", argFrag), demand, cancelled);
                            }
                            default -> {} // Ignore other event types
                        }
                    } catch (JsonProcessingException e) {
                        emit(subscriber, new StreamEvent.Error("parse_error", e.getMessage(), false, null), demand, cancelled);
                    }
                }
            } catch (IOException e) {
                if (!cancelled.get()) subscriber.onError(e);
            }
        }

        private void emit(Flow.Subscriber<? super StreamEvent> subscriber, StreamEvent event,
                          AtomicLong demand, AtomicBoolean cancelled) {
            while (demand.get() <= 0 && !cancelled.get()) {
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            if (!cancelled.get()) { demand.decrementAndGet(); subscriber.onNext(event); }
        }

        private @NonNull HttpRequest buildRequest(@NonNull ChatRequest request) throws JsonProcessingException {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", request.model());
            body.put("stream", true);

            // Cohere v2 uses "messages" array with role/content
            ArrayNode messages = body.putArray("messages");
            for (Message msg : request.messages()) {
                ObjectNode m = messages.addObject();
                String role = switch (msg.role()) {
                    case SYSTEM -> "system";
                    case ASSISTANT -> "assistant";
                    default -> "user";
                };
                m.put("role", role);
                m.put("content", msg.content());
            }

            if (request.maxTokens() > 0) body.put("max_tokens", request.maxTokens());
            body.put("temperature", request.temperature());

            if (!request.tools().isEmpty()) {
                ArrayNode tools = body.putArray("tools");
                for (ToolDefinition tool : request.tools()) {
                    ObjectNode t = tools.addObject();
                    t.put("name", tool.name());
                    t.put("description", tool.description());
                    t.set("parameter_definitions", objectMapper.valueToTree(tool.parametersSchema()));
                }
            }

            return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v2/chat"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-Client-Name", "chorus-engine4j")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        }
    }
}
