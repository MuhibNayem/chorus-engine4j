package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.*;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.llm.sse.SseParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Azure OpenAI Service LLM provider.
 *
 * <p>Differences from {@link OpenAiProvider}:
 * <ul>
 *   <li>URL format: {@code https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version={version}}</li>
 *   <li>Auth header: {@code api-key: {key}} (not {@code Authorization: Bearer})</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * registry.registerAzureOpenAi(
 *     "gpt4o",
 *     "https://my-resource.openai.azure.com",
 *     "gpt-4o",                 // deployment name
 *     "2024-10-21",             // API version
 *     System.getenv("AZURE_OPENAI_KEY")
 * );
 * }</pre>
 */
public final class AzureOpenAiProvider implements LlmClient {

    private static final String DEFAULT_API_VERSION = "2024-10-21";

    private final String providerName;
    private final String chatUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param providerName  logical name used in {@link ChatResponse}
     * @param resourceEndpoint  {@code https://{resource}.openai.azure.com}
     * @param deploymentId  Azure deployment name (e.g. {@code "gpt-4o"})
     * @param apiVersion    Azure REST API version (e.g. {@code "2024-10-21"})
     * @param apiKey        Azure OpenAI API key
     */
    public AzureOpenAiProvider(
        @NonNull String providerName,
        @NonNull String resourceEndpoint,
        @NonNull String deploymentId,
        @NonNull String apiVersion,
        @NonNull String apiKey,
        @NonNull HttpClient httpClient,
        @NonNull ObjectMapper objectMapper,
        @NonNull RetryPolicy retryPolicy,
        @NonNull CircuitBreaker circuitBreaker,
        @NonNull ExecutorService executor
    ) {
        this.providerName = providerName;
        String base = resourceEndpoint.endsWith("/") ? resourceEndpoint.substring(0, resourceEndpoint.length() - 1) : resourceEndpoint;
        this.chatUrl = base + "/openai/deployments/" + deploymentId + "/chat/completions?api-version=" + apiVersion;
        this.apiKey = Objects.requireNonNull(apiKey);
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
        return new AzureStreamPublisher(request, cancellationToken);
    }

    @Override
    public @NonNull ChatResponse complete(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder content = new StringBuilder();
        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicInteger promptTokens = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);

        stream(request, cancellationToken).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription s) { this.subscription = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) {
                switch (event) {
                    case StreamEvent.Token t -> content.append(t.token());
                    case StreamEvent.Finish f -> {
                        finishReason.set(f.finishReason());
                        promptTokens.set(f.promptTokens());
                        completionTokens.set(f.completionTokens());
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
                    new TokenCount(promptTokens.get(), completionTokens.get(), "o200k_base"),
                    Duration.ZERO, finishReason.get(),
                    toolCalls.isEmpty() ? null : List.copyOf(toolCalls), null, Map.of()
                ));
            }
        });

        try {
            return future.get(120L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Azure OpenAI request timed out", e);
        } catch (Exception e) {
            throw new RuntimeException("Azure OpenAI request failed", e);
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

    private final class AzureStreamPublisher implements Flow.Publisher<StreamEvent> {
        private final ChatRequest request;
        private final CancellationToken cancellationToken;

        AzureStreamPublisher(ChatRequest request, CancellationToken cancellationToken) {
            this.request = request;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            AtomicLong demand = new AtomicLong(0);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    demand.getAndUpdate(d -> {
                        long sum = d + n; return sum < 0 ? Long.MAX_VALUE : sum;
                    });
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
                if (cancelled.get() || cancellationToken.isCancelled()) {
                    throw new CancellationException("Request cancelled");
                }
                try {
                    HttpRequest httpRequest = buildRequest(request);
                    HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 200) {
                        circuitBreaker.recordSuccess();
                        try (InputStream stream = response.body()) {
                            parseSseStream(stream, subscriber, cancelled, demand);
                        }
                        return;
                    }
                    String body = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    lastError = new RuntimeException("HTTP " + response.statusCode() + ": " + body);
                    if (!retryPolicy.isRetryable(response.statusCode())) throw lastError;
                    circuitBreaker.recordFailure();
                } catch (IOException | InterruptedException e) {
                    lastError = e;
                    circuitBreaker.recordFailure();
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                attempt++;
                if (attempt < retryPolicy.maxAttempts()) {
                    Thread.sleep(retryPolicy.computeDelay(attempt).toMillis());
                }
            }
            throw lastError != null ? lastError : new RuntimeException("Max retries exceeded");
        }

        private void parseSseStream(InputStream stream, Flow.Subscriber<? super StreamEvent> subscriber,
                                    AtomicBoolean cancelled, AtomicLong demand) {
            SseParser parser = new SseParser(stream);
            AtomicReference<String> currentToolCallId = new AtomicReference<>();
            AtomicReference<String> currentToolName = new AtomicReference<>();
            StringBuilder currentArgs = new StringBuilder();

            try {
                parser.parse(event -> {
                    if (cancelled.get() || cancellationToken.isCancelled()) return;
                    if ("[DONE]".equals(event.data().trim())) return;
                    try {
                        JsonNode root = objectMapper.readTree(event.data());
                        JsonNode choices = root.path("choices");
                        if (!choices.isArray() || choices.isEmpty()) return;
                        JsonNode delta = choices.get(0).path("delta");

                        JsonNode content = delta.path("content");
                        if (content.isTextual()) {
                            emit(subscriber, new StreamEvent.Token(content.asText(), 0, null), demand, cancelled);
                        }

                        JsonNode toolCalls = delta.path("tool_calls");
                        if (toolCalls.isArray()) {
                            for (JsonNode tc : toolCalls) {
                                String id = tc.has("id") ? tc.get("id").asText() : currentToolCallId.get();
                                if (id == null) id = "tc_" + System.nanoTime();
                                currentToolCallId.set(id);
                                JsonNode function = tc.path("function");
                                if (function.has("name")) {
                                    currentToolName.set(function.get("name").asText());
                                    emit(subscriber, new StreamEvent.ToolCallStart(id, currentToolName.get(), Map.of()), demand, cancelled);
                                    currentArgs.setLength(0);
                                }
                                if (function.has("arguments")) {
                                    String frag = function.get("arguments").asText();
                                    currentArgs.append(frag);
                                    emit(subscriber, new StreamEvent.ToolCallDelta(id, currentToolName.get(), frag), demand, cancelled);
                                }
                            }
                        }

                        JsonNode finish = choices.get(0).path("finish_reason");
                        if (!finish.isMissingNode() && !finish.isNull()) {
                            if (currentArgs.length() > 0 && currentToolCallId.get() != null) {
                                emit(subscriber, new StreamEvent.ToolCallDone(currentToolCallId.get(), currentToolName.get(), parseArgs(currentArgs.toString())), demand, cancelled);
                            }
                            JsonNode usage = root.path("usage");
                            emit(subscriber, new StreamEvent.Finish(finish.asText(), usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0)), demand, cancelled);
                        }
                    } catch (JsonProcessingException e) {
                        emit(subscriber, new StreamEvent.Error("parse_error", e.getMessage(), false, null), demand, cancelled);
                    }
                });
            } catch (IOException e) {
                if (!cancelled.get()) subscriber.onError(e);
            }
        }

        @SuppressWarnings("unchecked")
        private @NonNull Map<String, Object> parseArgs(@NonNull String json) {
            try { return objectMapper.readValue(json, HashMap.class); }
            catch (Exception e) { return Map.of(); }
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
            body.put("stream", true);
            body.put("temperature", request.temperature());
            body.put("max_tokens", request.maxTokens());
            if (request.stopSequence() != null) body.put("stop", request.stopSequence());
            if (request.responseFormat() != null) {
                ObjectNode fmt = body.putObject("response_format");
                switch (request.responseFormat().type()) {
                    case JSON_OBJECT -> fmt.put("type", "json_object");
                    case JSON_SCHEMA -> {
                        fmt.put("type", "json_schema");
                        fmt.set("json_schema", objectMapper.valueToTree(request.responseFormat().jsonSchema()));
                    }
                    case TEXT -> fmt.put("type", "text");
                }
            }

            ArrayNode messages = body.putArray("messages");
            for (Message msg : request.messages()) {
                ObjectNode m = messages.addObject();
                m.put("role", msg.role().name().toLowerCase(Locale.ROOT));
                m.put("content", msg.content());
                if (msg.name() != null) m.put("name", msg.name());
                if (msg.toolCallId() != null) m.put("tool_call_id", msg.toolCallId());
            }

            if (!request.tools().isEmpty()) {
                ArrayNode tools = body.putArray("tools");
                for (ToolDefinition tool : request.tools()) {
                    ObjectNode t = tools.addObject();
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tool.name());
                    fn.put("description", tool.description());
                    fn.set("parameters", objectMapper.valueToTree(tool.parametersSchema()));
                }
            }

            if (request.providerExtras() != null) {
                for (Map.Entry<String, Object> e : request.providerExtras().entrySet()) {
                    body.set(e.getKey(), objectMapper.valueToTree(e.getValue()));
                }
            }

            return HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)          // Azure uses api-key, not Bearer
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        }
    }
}
