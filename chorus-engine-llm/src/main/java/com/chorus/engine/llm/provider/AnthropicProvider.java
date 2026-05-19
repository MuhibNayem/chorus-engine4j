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
 * Anthropic Claude API provider.
 *
 * <p>Uses JDK HttpClient with HTTP/2. Supports streaming via SSE,
 * tool calling via {@code tool_use} content blocks, and reasoning
 * via {@code thinking} content blocks (Claude 3.7+).
 */
public final class AnthropicProvider implements LlmClient {

    private static final String API_VERSION = "2023-06-01";
    private static final String BASE_URL = "https://api.anthropic.com";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executor;
    private final Duration timeout;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AnthropicProvider(
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
                new RuntimeException("Circuit breaker is OPEN for provider anthropic"));
        }
        return new AnthropicStreamPublisher(request, cancellationToken);
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
                        // Update arguments incrementally if needed
                    }
                    case StreamEvent.ToolCallDone t -> {
                        // Replace with final arguments
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
                    "anthropic",
                    Message.assistant(content.toString()),
                    new TokenCount(promptTokens.get(), completionTokens.get(), "claude"),
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
    public @NonNull String providerName() { return "anthropic"; }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
        }
    }

    // ---- Package-private test helpers ----

    static @NonNull String buildRequestBody(@NonNull ChatRequest request, @NonNull ObjectMapper objectMapper) throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("stream", true);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        if (request.stopSequence() != null) {
            body.put("stop_sequence", request.stopSequence());
        }

        // System messages are extracted to top-level field
        List<String> systemMessages = new ArrayList<>();
        ArrayNode messages = body.putArray("messages");
        for (Message msg : request.messages()) {
            if (msg.role() == Role.SYSTEM) {
                systemMessages.add(msg.content());
                continue;
            }
            ObjectNode m = messages.addObject();
            m.put("role", msg.role() == Role.TOOL ? "user" : msg.role().name().toLowerCase());
            if (msg.role() == Role.TOOL && msg.toolCallId() != null) {
                ArrayNode contentArray = m.putArray("content");
                ObjectNode toolResult = contentArray.addObject();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", msg.toolCallId());
                toolResult.put("content", msg.content());
            } else {
                m.put("content", msg.content());
            }
        }
        if (!systemMessages.isEmpty()) {
            body.put("system", String.join("\n", systemMessages));
        }

        // Tools — Anthropic uses input_schema
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("input_schema", objectMapper.valueToTree(tool.parametersSchema()));
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
        String id = root.path("id").asText(UUID.randomUUID().toString());

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();

        JsonNode contentArray = root.path("content");
        if (contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                String type = block.path("type").asText("");
                switch (type) {
                    case "text" -> content.append(block.path("text").asText(""));
                    case "thinking" -> reasoning.append(block.path("thinking").asText(""));
                    case "tool_use" -> {
                        String toolId = block.path("id").asText("");
                        String toolName = block.path("name").asText("");
                        Map<String, Object> args = parseJsonObject(block.path("input"), objectMapper);
                        toolCalls.add(new ChatResponse.ToolCall(toolId, toolName, args));
                    }
                }
            }
        }

        JsonNode usage = root.path("usage");
        int inputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);

        String stopReason = root.path("stop_reason").asText(null);

        return new ChatResponse(
            id,
            model,
            "anthropic",
            Message.assistant(content.toString()),
            new TokenCount(inputTokens, outputTokens, "claude"),
            Duration.ZERO,
            stopReason,
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
        String type = root.path("type").asText("");

        switch (type) {
            case "content_block_start" -> {
                JsonNode block = root.path("content_block");
                String blockType = block.path("type").asText("");
                if ("tool_use".equals(blockType)) {
                    String toolId = block.path("id").asText("");
                    String toolName = block.path("name").asText("");
                    events.add(new StreamEvent.ToolCallStart(toolId, toolName, Map.of()));
                }
            }
            case "content_block_delta" -> {
                JsonNode delta = root.path("delta");
                String deltaType = delta.path("type").asText("");
                switch (deltaType) {
                    case "text_delta" -> {
                        String text = delta.path("text").asText("");
                        events.add(new StreamEvent.Token(text, 0, null));
                    }
                    case "thinking_delta" -> {
                        String thinking = delta.path("thinking").asText("");
                        events.add(new StreamEvent.Token("", 0, thinking));
                    }
                    case "input_json_delta" -> {
                        String partial = delta.path("partial_json").asText("");
                        // We don't know the tool call id here without tracking index
                        // For simplicity, emit as token — callers can correlate by index if needed
                    }
                }
            }
            case "message_delta" -> {
                JsonNode delta = root.path("delta");
                String stopReason = delta.path("stop_reason").asText(null);
                JsonNode usage = root.path("usage");
                int outputTokens = usage.path("output_tokens").asInt(0);
                // Anthropic doesn't send input tokens in message_delta
                events.add(new StreamEvent.Finish(stopReason, 0, outputTokens));
            }
        }

        return events;
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

    private final class AnthropicStreamPublisher implements Flow.Publisher<StreamEvent> {
        private final ChatRequest request;
        private final CancellationToken cancellationToken;

        AnthropicStreamPublisher(ChatRequest request, CancellationToken cancellationToken) {
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
            int[] blockIndex = {-1};

            try {
                parser.parse(event -> {
                    if (cancellationToken.isCancelled()) return;
                    if ("[DONE]".equals(event.data().trim())) return;

                    try {
                        JsonNode root = objectMapper.readTree(event.data());
                        String type = root.path("type").asText("");

                        switch (type) {
                            case "content_block_start" -> {
                                int idx = root.path("index").asInt(0);
                                blockIndex[0] = idx;
                                JsonNode block = root.path("content_block");
                                String blockType = block.path("type").asText("");
                                if ("tool_use".equals(blockType)) {
                                    String id = block.path("id").asText("");
                                    String name = block.path("name").asText("");
                                    currentToolCallId.set(id);
                                    currentToolName.set(name);
                                    subscriber.onNext(new StreamEvent.ToolCallStart(id, name, Map.of()));
                                }
                            }
                            case "content_block_delta" -> {
                                JsonNode delta = root.path("delta");
                                String deltaType = delta.path("type").asText("");
                                switch (deltaType) {
                                    case "text_delta" -> {
                                        String text = delta.path("text").asText("");
                                        subscriber.onNext(new StreamEvent.Token(text, 0, null));
                                    }
                                    case "thinking_delta" -> {
                                        String thinking = delta.path("thinking").asText("");
                                        subscriber.onNext(new StreamEvent.Token("", 0, thinking));
                                    }
                                    case "input_json_delta" -> {
                                        String partial = delta.path("partial_json").asText("");
                                        currentArgs.append(partial);
                                    }
                                }
                            }
                            case "message_delta" -> {
                                JsonNode delta = root.path("delta");
                                String stopReason = delta.path("stop_reason").asText(null);
                                JsonNode usage = root.path("usage");
                                int outputTokens = usage.path("output_tokens").asInt(0);
                                subscriber.onNext(new StreamEvent.Finish(stopReason, 0, outputTokens));
                            }
                            case "message_stop" -> {
                                // End of stream
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
            return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        }
    }
}
