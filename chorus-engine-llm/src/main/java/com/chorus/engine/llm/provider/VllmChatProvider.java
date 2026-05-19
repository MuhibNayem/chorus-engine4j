package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
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
 * vLLM / OpenAI-compatible chat provider.
 *
 * <p>Most vLLM deployments expose an OpenAI-compatible {@code /v1/chat/completions}
 * endpoint. This provider auto-detects available models on construction and
 * supports health checks.
 */
public final class VllmChatProvider implements LlmClient {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executor;
    private final Duration timeout;
    private final List<String> availableModels;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public VllmChatProvider(
        @NonNull String baseUrl,
        @Nullable String apiKey,
        @NonNull HttpClient httpClient,
        @NonNull Duration timeout,
        @NonNull RetryPolicy retryPolicy
    ) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = CircuitBreaker.defaults();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.timeout = timeout;
        this.availableModels = detectModels();
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        if (closed.get()) throw new IllegalStateException("Provider is closed");
        if (!circuitBreaker.allowsRequest()) {
            return subscriber -> subscriber.onError(
                new RuntimeException("Circuit breaker is OPEN for provider vllm"));
        }
        return new VllmStreamPublisher(request, cancellationToken);
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
                        // Incremental update
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
                    "vllm",
                    Message.assistant(content.toString()),
                    new TokenCount(promptTokens.get(), completionTokens.get(), "vllm"),
                    Duration.ZERO,
                    finishReason.get(),
                    toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
                    reasoning.isEmpty() ? null : reasoning.toString(),
                    Map.of("available_models", availableModels)
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

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return HealthStatus.HEALTHY;
            }
        } catch (Exception ignored) {
            // Fall through to /healthz
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/healthz"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return HealthStatus.HEALTHY;
            }
        } catch (Exception ignored) {
            // Ignore
        }

        return HealthStatus.DEGRADED;
    }

    @Override
    public @NonNull String providerName() { return "vllm"; }

    public @NonNull List<String> availableModels() {
        return List.copyOf(availableModels);
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
        }
    }

    private @NonNull List<String> detectModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/models"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode data = root.path("data");
                if (data.isArray()) {
                    List<String> models = new ArrayList<>();
                    for (JsonNode node : data) {
                        String id = node.path("id").asText(null);
                        if (id != null) {
                            models.add(id);
                        }
                    }
                    return models;
                }
            }
        } catch (Exception ignored) {
            // Model detection is best-effort
        }
        return List.of();
    }

    // ---- Package-private test helpers ----

    static @NonNull String buildRequestBody(@NonNull ChatRequest request, @NonNull ObjectMapper objectMapper) throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("stream", true);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        if (request.stopSequence() != null) {
            body.put("stop", request.stopSequence());
        }
        if (request.responseFormat() != null) {
            ObjectNode format = body.putObject("response_format");
            switch (request.responseFormat().type()) {
                case JSON_OBJECT -> format.put("type", "json_object");
                case JSON_SCHEMA -> {
                    format.put("type", "json_schema");
                    format.set("json_schema", objectMapper.valueToTree(request.responseFormat().jsonSchema()));
                }
                case TEXT -> format.put("type", "text");
            }
        }

        ArrayNode messages = body.putArray("messages");
        for (Message msg : request.messages()) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role().name().toLowerCase());
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

        return objectMapper.writeValueAsString(body);
    }

    static @NonNull ChatResponse parseResponse(@NonNull String json, @NonNull String model, @NonNull ObjectMapper objectMapper) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);
        String id = root.path("id").asText(UUID.randomUUID().toString());

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();
        String finishReason = null;
        int promptTokens = 0;
        int completionTokens = 0;

        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            finishReason = choice.path("finish_reason").asText(null);

            JsonNode contentNode = message.path("content");
            if (contentNode.isTextual()) {
                content.append(contentNode.asText(""));
            }

            JsonNode toolCallsNode = message.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    String toolId = tc.path("id").asText("");
                    JsonNode function = tc.path("function");
                    String name = function.path("name").asText("");
                    Map<String, Object> args = parseArguments(function.path("arguments"), objectMapper);
                    toolCalls.add(new ChatResponse.ToolCall(toolId, name, args));
                }
            }
        }

        JsonNode usage = root.path("usage");
        promptTokens = usage.path("prompt_tokens").asInt(0);
        completionTokens = usage.path("completion_tokens").asInt(0);

        return new ChatResponse(
            id,
            model,
            "vllm",
            Message.assistant(content.toString()),
            new TokenCount(promptTokens, completionTokens, "vllm"),
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
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return events;

        JsonNode delta = choices.get(0).path("delta");

        JsonNode content = delta.path("content");
        if (content.isTextual()) {
            events.add(new StreamEvent.Token(content.asText(), 0, null));
        }

        JsonNode reasoning = delta.path("reasoning_content");
        if (!reasoning.isMissingNode() && reasoning.isTextual()) {
            events.add(new StreamEvent.Token("", 0, reasoning.asText()));
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText("");
                JsonNode function = tc.path("function");
                if (function.has("name")) {
                    String name = function.get("name").asText();
                    events.add(new StreamEvent.ToolCallStart(id, name, Map.of()));
                }
            }
        }

        JsonNode finish = choices.get(0).path("finish_reason");
        if (!finish.isMissingNode() && !finish.isNull()) {
            JsonNode usage = root.path("usage");
            int prompt = usage.path("prompt_tokens").asInt(0);
            int completion = usage.path("completion_tokens").asInt(0);
            events.add(new StreamEvent.Finish(finish.asText(), prompt, completion));
        }

        return events;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> parseArguments(@NonNull JsonNode node, @NonNull ObjectMapper objectMapper) {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (node.isTextual()) {
            try {
                return objectMapper.readValue(node.asText(), Map.class);
            } catch (JsonProcessingException e) {
                return Map.of();
            }
        }
        try {
            return objectMapper.treeToValue(node, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    // ---- Inner: Stream Publisher ----

    private final class VllmStreamPublisher implements Flow.Publisher<StreamEvent> {
        private final ChatRequest request;
        private final CancellationToken cancellationToken;

        VllmStreamPublisher(ChatRequest request, CancellationToken cancellationToken) {
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
            AtomicReference<String> currentToolCallId = new AtomicReference<>();
            AtomicReference<String> currentToolName = new AtomicReference<>();
            StringBuilder currentArgs = new StringBuilder();

            try {
                parser.parse(event -> {
                    if (cancellationToken.isCancelled()) return;
                    if ("[DONE]".equals(event.data().trim())) return;

                    try {
                        JsonNode root = objectMapper.readTree(event.data());
                        JsonNode choices = root.path("choices");
                        if (!choices.isArray() || choices.isEmpty()) return;

                        JsonNode delta = choices.get(0).path("delta");

                        JsonNode content = delta.path("content");
                        if (content.isTextual()) {
                            subscriber.onNext(new StreamEvent.Token(content.asText(), 0, null));
                        }

                        JsonNode reasoning = delta.path("reasoning_content");
                        if (!reasoning.isMissingNode() && reasoning.isTextual()) {
                            subscriber.onNext(new StreamEvent.Token("", 0, reasoning.asText()));
                        }

                        JsonNode toolCalls = delta.path("tool_calls");
                        if (toolCalls.isArray()) {
                            for (JsonNode tc : toolCalls) {
                                String id = tc.has("id") ? tc.get("id").asText() : currentToolCallId.get();
                                if (id != null) currentToolCallId.set(id);

                                JsonNode function = tc.path("function");
                                if (function.has("name")) {
                                    String name = function.get("name").asText();
                                    currentToolName.set(name);
                                    subscriber.onNext(new StreamEvent.ToolCallStart(id, name, Map.of()));
                                }
                                if (function.has("arguments")) {
                                    String argFragment = function.get("arguments").asText();
                                    currentArgs.append(argFragment);
                                }
                            }
                        }

                        JsonNode finish = choices.get(0).path("finish_reason");
                        if (!finish.isMissingNode() && !finish.isNull()) {
                            JsonNode usage = root.path("usage");
                            int prompt = usage.path("prompt_tokens").asInt(0);
                            int completion = usage.path("completion_tokens").asInt(0);
                            subscriber.onNext(new StreamEvent.Finish(finish.asText(), prompt, completion));
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
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));

            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            return builder.build();
        }
    }
}
