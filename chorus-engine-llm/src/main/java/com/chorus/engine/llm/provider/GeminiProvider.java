package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Google Gemini API provider.
 *
 * <p>Uses JDK HttpClient with HTTP/2. Supports streaming via SSE,
 * tool calling via {@code functionDeclarations}, and multimodal inputs.
 */
public final class GeminiProvider implements LlmClient {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executor;
    private final Duration timeout;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public GeminiProvider(
        @NonNull String apiKey,
        @NonNull HttpClient httpClient,
        @NonNull Duration timeout,
        @NonNull RetryPolicy retryPolicy
    ) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = CircuitBreaker.defaults();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.timeout = timeout;
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        if (closed.get()) throw new IllegalStateException("Provider is closed");
        if (!circuitBreaker.allowsRequest()) {
            return subscriber -> subscriber.onError(
                new RuntimeException("Circuit breaker is OPEN for provider gemini"));
        }
        return new GeminiStreamPublisher(request, cancellationToken);
    }

    @Override
    public @NonNull ChatResponse complete(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicInteger promptTokens = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);

        stream(request, cancellationToken).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription s) { this.subscription = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) {
                switch (event) {
                    case StreamEvent.Token t -> {
                        content.append(t.token());
                        if (t.reasoningContent() != null) {
                            reasoning.append(t.reasoningContent());
                        }
                    }
                    case StreamEvent.ToolCallStart t -> {
                        toolCalls.add(new ChatResponse.ToolCall(t.toolCallId(), t.toolName(), new LinkedHashMap<>(t.partialArguments())));
                    }
                    case StreamEvent.ToolCallDelta t -> {
                        // Incremental update if needed
                    }
                    case StreamEvent.ToolCallDone t -> {
                        for (int i = 0; i < toolCalls.size(); i++) {
                            if (toolCalls.get(i).id().equals(t.toolCallId())) {
                                toolCalls.set(i, new ChatResponse.ToolCall(t.toolCallId(), t.toolName(), t.finalArguments()));
                            }
                        }
                    }
                    case StreamEvent.Finish f -> {
                        finishReason.set(f.finishReason());
                        promptTokens.set(f.promptTokens());
                        completionTokens.set(f.completionTokens());
                    }
                    case StreamEvent.Error e -> future.completeExceptionally(new RuntimeException(e.errorMessage()));
                }
            }
            @Override public void onError(Throwable t) { future.completeExceptionally(t); }
            @Override public void onComplete() {
                future.complete(new ChatResponse(
                    UUID.randomUUID().toString(),
                    request.model(),
                    "gemini",
                    Message.assistant(content.toString()),
                    new TokenCount(promptTokens.get(), completionTokens.get(), "gemini"),
                    Duration.ZERO,
                    finishReason.get(),
                    toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
                    reasoning.isEmpty() ? null : reasoning.toString(),
                    Map.of()
                ));
            }
        });

        try {
            return future.get(request.maxTokens() * 2L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("LLM request timed out", e);
        } catch (Exception e) {
            throw new RuntimeException("LLM request failed", e);
        }
    }

    @Override
    public @NonNull HealthStatus health() {
        if (circuitBreaker.isOpen()) return HealthStatus.UNAVAILABLE;
        return HealthStatus.HEALTHY;
    }

    @Override
    public @NonNull String providerName() { return "gemini"; }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
        }
    }

    // ---- Package-private test helpers ----

    static @NonNull String buildRequestBody(@NonNull ChatRequest request, @NonNull ObjectMapper objectMapper) throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();

        // generationConfig
        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("temperature", request.temperature());
        generationConfig.put("maxOutputTokens", request.maxTokens());
        if (request.stopSequence() != null) {
            ArrayNode stopSequences = generationConfig.putArray("stopSequences");
            stopSequences.add(request.stopSequence());
        }

        // System instruction
        List<String> systemMessages = new ArrayList<>();
        ArrayNode contents = body.putArray("contents");
        for (Message msg : request.messages()) {
            if (msg.role() == Role.SYSTEM) {
                systemMessages.add(msg.content());
                continue;
            }
            ObjectNode contentEntry = contents.addObject();
            contentEntry.put("role", mapRole(msg.role()));
            ArrayNode parts = contentEntry.putArray("parts");
            ObjectNode textPart = parts.addObject();
            textPart.put("text", msg.content());
        }
        if (!systemMessages.isEmpty()) {
            ObjectNode systemInstruction = body.putObject("systemInstruction");
            ArrayNode parts = systemInstruction.putArray("parts");
            ObjectNode textPart = parts.addObject();
            textPart.put("text", String.join("\n", systemMessages));
        }

        // Tools — Gemini uses functionDeclarations
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            ArrayNode functionDeclarations = tool.putArray("functionDeclarations");
            for (ToolDefinition td : request.tools()) {
                ObjectNode fd = functionDeclarations.addObject();
                fd.put("name", td.name());
                fd.put("description", td.description());
                fd.set("parameters", objectMapper.valueToTree(td.parametersSchema()));
            }
        }

        // Provider extras
        if (request.providerExtras() != null) {
            for (Map.Entry<String, Object> e : request.providerExtras().entrySet()) {
                body.set(e.getKey(), objectMapper.valueToTree(e.getValue()));
            }
        }

        return objectMapper.writeValueAsString(body);
    }

    static @NonNull ChatResponse parseResponse(@NonNull String json, @NonNull String model, @NonNull ObjectMapper objectMapper) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();
        String finishReason = null;

        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode candidate = candidates.get(0);
            finishReason = candidate.path("finishReason").asText(null);
            JsonNode candidateContent = candidate.path("content");
            JsonNode parts = candidateContent.path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.has("text")) {
                        content.append(part.path("text").asText(""));
                    }
                    if (part.has("functionCall")) {
                        JsonNode fc = part.path("functionCall");
                        String name = fc.path("name").asText("");
                        Map<String, Object> args = parseJsonObject(fc.path("args"), objectMapper);
                        toolCalls.add(new ChatResponse.ToolCall(name, name, args));
                    }
                }
            }
        }

        JsonNode usage = root.path("usageMetadata");
        int promptTokens = usage.path("promptTokenCount").asInt(0);
        int completionTokens = usage.path("candidatesTokenCount").asInt(0);

        return new ChatResponse(
            UUID.randomUUID().toString(),
            model,
            "gemini",
            Message.assistant(content.toString()),
            new TokenCount(promptTokens, completionTokens, "gemini"),
            Duration.ZERO,
            finishReason,
            toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
            reasoning.isEmpty() ? null : reasoning.toString(),
            Map.of()
        );
    }

    static @NonNull List<StreamEvent> parseSseEvents(@NonNull String data, @NonNull ObjectMapper objectMapper) throws JsonProcessingException {
        List<StreamEvent> events = new ArrayList<>();
        if (data.trim().isEmpty() || "[DONE]".equals(data.trim())) {
            return events;
        }

        JsonNode root = objectMapper.readTree(data);

        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode candidate = candidates.get(0);
            JsonNode content = candidate.path("content");
            JsonNode parts = content.path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.has("text")) {
                        events.add(new StreamEvent.Token(part.path("text").asText(""), 0, null));
                    }
                    if (part.has("functionCall")) {
                        JsonNode fc = part.path("functionCall");
                        String name = fc.path("name").asText("");
                        events.add(new StreamEvent.ToolCallStart(name, name, Map.of()));
                    }
                }
            }

            String finishReason = candidate.path("finishReason").asText(null);
            if (finishReason != null) {
                JsonNode usage = root.path("usageMetadata");
                int promptTokens = usage.path("promptTokenCount").asInt(0);
                int completionTokens = usage.path("candidatesTokenCount").asInt(0);
                events.add(new StreamEvent.Finish(finishReason, promptTokens, completionTokens));
            }
        }

        return events;
    }

    private static @NonNull String mapRole(@NonNull Role role) {
        return switch (role) {
            case SYSTEM -> throw new IllegalStateException("System messages should be extracted");
            case USER, TOOL -> "user";
            case ASSISTANT -> "model";
        };
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> parseJsonObject(@NonNull JsonNode node, @NonNull ObjectMapper objectMapper) {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        try {
            return objectMapper.treeToValue(node, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    // ---- Inner: Stream Publisher ----

    private final class GeminiStreamPublisher implements Flow.Publisher<StreamEvent> {
        private final ChatRequest request;
        private final CancellationToken cancellationToken;

        GeminiStreamPublisher(ChatRequest request, CancellationToken cancellationToken) {
            this.request = request;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
            executor.submit(() -> {
                try {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        private final AtomicBoolean cancelled = new AtomicBoolean(false);
                        @Override public void request(long n) {}
                        @Override public void cancel() { cancelled.set(true); }
                    });

                    executeWithRetry(subscriber);
                    subscriber.onComplete();
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            });
        }

        private void executeWithRetry(Flow.Subscriber<? super StreamEvent> subscriber) throws Exception {
            int attempt = 0;
            Exception lastError = null;

            while (attempt < retryPolicy.maxAttempts()) {
                if (cancellationToken.isCancelled()) {
                    throw new CancellationException("Request cancelled: " + cancellationToken.reason());
                }

                try {
                    HttpRequest httpRequest = buildHttpRequest(request);
                    Instant start = Instant.now();
                    HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

                    if (response.statusCode() == 200) {
                        circuitBreaker.recordSuccess();
                        parseSseStream(response.body(), subscriber, start);
                        return;
                    }

                    String body = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    lastError = new RuntimeException("HTTP " + response.statusCode() + ": " + body);

                    if (!retryPolicy.isRetryable(response.statusCode())) {
                        throw lastError;
                    }

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

        private void parseSseStream(InputStream stream, Flow.Subscriber<? super StreamEvent> subscriber, Instant start) {
            SseParser parser = new SseParser(stream);

            try {
                parser.parse(event -> {
                    if (cancellationToken.isCancelled()) return;
                    if ("[DONE]".equals(event.data().trim())) return;

                    try {
                        JsonNode root = objectMapper.readTree(event.data());
                        JsonNode candidates = root.path("candidates");
                        if (candidates.isArray() && !candidates.isEmpty()) {
                            JsonNode candidate = candidates.get(0);
                            JsonNode content = candidate.path("content");
                            JsonNode parts = content.path("parts");
                            if (parts.isArray()) {
                                for (JsonNode part : parts) {
                                    if (part.has("text")) {
                                        subscriber.onNext(new StreamEvent.Token(part.path("text").asText(""), 0, null));
                                    }
                                    if (part.has("functionCall")) {
                                        JsonNode fc = part.path("functionCall");
                                        String name = fc.path("name").asText("");
                                        subscriber.onNext(new StreamEvent.ToolCallStart(name, name, Map.of()));
                                    }
                                }
                            }

                            String finishReason = candidate.path("finishReason").asText(null);
                            if (finishReason != null) {
                                JsonNode usage = root.path("usageMetadata");
                                int promptTokens = usage.path("promptTokenCount").asInt(0);
                                int completionTokens = usage.path("candidatesTokenCount").asInt(0);
                                subscriber.onNext(new StreamEvent.Finish(finishReason, promptTokens, completionTokens));
                            }
                        }
                    } catch (JsonProcessingException e) {
                        subscriber.onNext(new StreamEvent.Error("parse_error", e.getMessage(), false, null));
                    }
                });
            } catch (IOException e) {
                subscriber.onError(e);
            }
        }

        private @NonNull HttpRequest buildHttpRequest(@NonNull ChatRequest request) throws JsonProcessingException {
            String body = buildRequestBody(request, objectMapper);
            String uri = BASE_URL + "/" + request.model() + ":streamGenerateContent?alt=sse&key=" + apiKey;
            return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        }
    }
}
