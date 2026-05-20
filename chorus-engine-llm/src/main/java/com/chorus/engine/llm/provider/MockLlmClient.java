package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fully scriptable, zero-configuration local LLM provider for development and testing.
 *
 * <p>Implements the complete {@link LlmClient} contract, including realistic token-by-token
 * streaming simulation, tool-call event sequences, and configurable response scripts.
 * This is a first-class provider — not a test double — and is suitable for integration
 * tests, local developer boots, and offline demos.
 *
 * <h2>Response Selection</h2>
 * Scripts are evaluated in order. The first script whose {@link ResponseScript#trigger()}
 * matches a substring of the last user message content is selected. The wildcard
 * {@code "*"} always matches. If no script matches, a default placeholder is emitted.
 *
 * <h2>Streaming</h2>
 * Tokens are emitted word-by-word with a configurable inter-token delay on a dedicated
 * virtual-thread executor, accurately exercising the full reactive Publisher contract.
 *
 * <h2>Tool Calls</h2>
 * A script with a non-null {@link ResponseScript#toolName()} emits the complete
 * {@code ToolCallStart → ToolCallDelta → ToolCallDone → Finish} sequence before any
 * text content, exactly mirroring the OpenAI streaming wire protocol.
 */
public final class MockLlmClient implements LlmClient, AutoCloseable {

    private static final String PROVIDER_NAME = "mock";
    private static final String DEFAULT_RESPONSE =
        "I am the Chorus Mock LLM. No matching script found — configure response scripts to customize behavior.";
    /** Approximate words-to-tokens conversion coefficient. */
    private static final double TOKEN_COEFFICIENT = 1.3;

    private final List<ResponseScript> scripts;
    private final Duration interTokenDelay;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    /**
     * Constructs a {@code MockLlmClient} with the given response scripts.
     *
     * @param scripts         ordered list of response scripts; evaluated first-match wins
     * @param interTokenDelay simulated delay between streamed tokens (default 20ms)
     */
    public MockLlmClient(
        @NonNull List<ResponseScript> scripts,
        @NonNull Duration interTokenDelay
    ) {
        this.scripts = List.copyOf(Objects.requireNonNull(scripts, "scripts"));
        this.interTokenDelay = Objects.requireNonNull(interTokenDelay, "interTokenDelay");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Constructs a {@code MockLlmClient} with sensible defaults:
     * a single wildcard script returning a placeholder message, 20ms inter-token delay.
     */
    public static @NonNull MockLlmClient defaults() {
        return new MockLlmClient(
            List.of(new ResponseScript("*", DEFAULT_RESPONSE, null, Map.of(), Duration.ofMillis(20))),
            Duration.ofMillis(20)
        );
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(
        @NonNull ChatRequest request,
        @NonNull CancellationToken cancellationToken
    ) {
        if (closed.get()) throw new IllegalStateException("MockLlmClient is closed");
        ResponseScript script = selectScript(request);
        return new MockStreamPublisher(script, request, cancellationToken);
    }

    @Override
    public @NonNull ChatResponse complete(
        @NonNull ChatRequest request,
        @NonNull CancellationToken cancellationToken
    ) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder content = new StringBuilder();
        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();
        AtomicInteger promptTokens = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);

        stream(request, cancellationToken).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override public void onNext(StreamEvent event) {
                switch (event) {
                    case StreamEvent.Token t -> content.append(t.token());
                    case StreamEvent.ToolCallDone t ->
                        toolCalls.add(new ChatResponse.ToolCall(t.toolCallId(), t.toolName(), t.finalArguments()));
                    case StreamEvent.Finish f -> {
                        promptTokens.set(f.promptTokens());
                        completionTokens.set(f.completionTokens());
                    }
                    case StreamEvent.Error e -> future.completeExceptionally(new RuntimeException(e.errorMessage()));
                    default -> { /* ToolCallStart / ToolCallDelta — accumulate in Done */ }
                }
            }

            @Override public void onError(Throwable t) { future.completeExceptionally(t); }

            @Override public void onComplete() {
                future.complete(new ChatResponse(
                    UUID.randomUUID().toString(),
                    request.model(),
                    PROVIDER_NAME,
                    Message.assistant(content.toString()),
                    new TokenCount(promptTokens.get(), completionTokens.get(), "mock"),
                    Duration.ZERO,
                    "stop",
                    toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
                    null,
                    Map.of("mock", true)
                ));
            }
        });

        try {
            return future.get(30L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("MockLlmClient completion timed out", e);
        } catch (Exception e) {
            throw new RuntimeException("MockLlmClient completion failed", e);
        }
    }

    @Override
    public @NonNull HealthStatus health() {
        return closed.get() ? HealthStatus.UNAVAILABLE : HealthStatus.HEALTHY;
    }

    @Override
    public @NonNull String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    // ── Script Selection ──────────────────────────────────────────────

    private @NonNull ResponseScript selectScript(@NonNull ChatRequest request) {
        if (scripts.isEmpty()) {
            return new ResponseScript("*", DEFAULT_RESPONSE, null, Map.of(), interTokenDelay);
        }
        String lastUserMessage = extractLastUserMessage(request);

        // First-match by trigger substring
        for (ResponseScript script : scripts) {
            if ("*".equals(script.trigger()) || lastUserMessage.toLowerCase(Locale.ROOT)
                    .contains(script.trigger().toLowerCase(Locale.ROOT))) {
                return script;
            }
        }

        // Round-robin fallback across all scripts
        int idx = roundRobinIndex.getAndUpdate(i -> (i + 1) % scripts.size());
        return scripts.get(idx);
    }

    private static @NonNull String extractLastUserMessage(@NonNull ChatRequest request) {
        List<com.chorus.engine.core.context.Message> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            com.chorus.engine.core.context.Message msg = messages.get(i);
            if (msg.role() == com.chorus.engine.core.context.Role.USER) {
                return msg.content() != null ? msg.content() : "";
            }
        }
        return "";
    }

    // ── Approximate token counting ────────────────────────────────────

    private static int countTokens(@NonNull String text) {
        if (text.isBlank()) return 0;
        long words = Arrays.stream(text.split("\\s+")).filter(w -> !w.isBlank()).count();
        return (int) Math.ceil(words * TOKEN_COEFFICIENT);
    }

    // ── Inner: Stream Publisher ───────────────────────────────────────

    private final class MockStreamPublisher implements Flow.Publisher<StreamEvent> {
        private final ResponseScript script;
        private final ChatRequest request;
        private final CancellationToken cancellationToken;

        MockStreamPublisher(
            ResponseScript script,
            ChatRequest request,
            CancellationToken cancellationToken
        ) {
            this.script = script;
            this.request = request;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            AtomicLong demand = new AtomicLong(0);

            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    if (n <= 0) {
                        cancelled.set(true);
                        subscriber.onError(new IllegalArgumentException("Non-positive demand: " + n));
                        return;
                    }
                    demand.getAndUpdate(d -> {
                        long sum = d + n;
                        return sum < 0 ? Long.MAX_VALUE : sum;
                    });
                }
                @Override public void cancel() { cancelled.set(true); }
            });

            executor.submit(() -> {
                try {
                    emitStream(subscriber, cancelled, demand);
                    if (!cancelled.get()) subscriber.onComplete();
                } catch (Exception e) {
                    if (!cancelled.get()) subscriber.onError(e);
                }
            });
        }

        private void emitStream(
            Flow.Subscriber<? super StreamEvent> subscriber,
            AtomicBoolean cancelled,
            AtomicLong demand
        ) throws InterruptedException {
            int promptTokens = countTokens(extractLastUserMessage(request));
            String toolCallId = "mock_tc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

            // Emit tool-call sequence if script declares a tool
            if (script.toolName() != null && !script.toolName().isBlank()) {
                emit(subscriber, new StreamEvent.ToolCallStart(toolCallId, script.toolName(), Map.of()), demand, cancelled);
                // Emit argument fragments
                String argsJson = renderArgsJson(script.toolArguments());
                if (!argsJson.isEmpty()) {
                    emit(subscriber, new StreamEvent.ToolCallDelta(toolCallId, script.toolName(), argsJson), demand, cancelled);
                }
                emit(subscriber, new StreamEvent.ToolCallDone(toolCallId, script.toolName(), script.toolArguments()), demand, cancelled);
                int completionTokens = countTokens(script.toolName()) + countTokens(argsJson);
                emit(subscriber, new StreamEvent.Finish("tool_calls", promptTokens, completionTokens), demand, cancelled);
                return;
            }

            // Emit text tokens word-by-word
            String response = script.response();
            String[] words = response.split("(?<=\\s)|(?=\\s)"); // split preserving whitespace
            StringBuilder emitted = new StringBuilder();
            for (String fragment : words) {
                if (cancelled.get() || cancellationToken.isCancelled()) return;
                emit(subscriber, new StreamEvent.Token(fragment, 0, null), demand, cancelled);
                emitted.append(fragment);
                Duration delay = script.interTokenDelay();
                if (!delay.isZero()) Thread.sleep(delay.toMillis());
            }

            int completionTokens = countTokens(emitted.toString());
            emit(subscriber, new StreamEvent.Finish("stop", promptTokens, completionTokens), demand, cancelled);
        }

        private void emit(
            Flow.Subscriber<? super StreamEvent> subscriber,
            StreamEvent event,
            AtomicLong demand,
            AtomicBoolean cancelled
        ) throws InterruptedException {
            while (demand.get() <= 0 && !cancelled.get()) {
                Thread.sleep(1);
            }
            if (!cancelled.get()) {
                demand.decrementAndGet();
                subscriber.onNext(event);
            }
        }

        private static @NonNull String renderArgsJson(@NonNull Map<String, Object> args) {
            if (args.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
    }

    // ── Public API: ResponseScript ────────────────────────────────────

    /**
     * A single response script evaluated against incoming requests.
     *
     * @param trigger          keyword substring to match against the last user message,
     *                         or {@code "*"} to match any message
     * @param response         text to stream back (ignored when {@code toolName} is set)
     * @param toolName         when non-null, emits a tool-call sequence instead of text
     * @param toolArguments    arguments for the tool call
     * @param interTokenDelay  delay between streamed word-level tokens
     */
    public record ResponseScript(
        @NonNull String trigger,
        @NonNull String response,
        @Nullable String toolName,
        @NonNull Map<String, Object> toolArguments,
        @NonNull Duration interTokenDelay
    ) {
        public ResponseScript {
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(response, "response");
            toolArguments = toolArguments != null ? Map.copyOf(toolArguments) : Map.of();
            Objects.requireNonNull(interTokenDelay, "interTokenDelay");
        }

        /** Convenience factory — wildcard text-only script with 20ms delay. */
        public static @NonNull ResponseScript text(@NonNull String response) {
            return new ResponseScript("*", response, null, Map.of(), Duration.ofMillis(20));
        }

        /** Convenience factory — keyword-triggered text script. */
        public static @NonNull ResponseScript text(@NonNull String trigger, @NonNull String response) {
            return new ResponseScript(trigger, response, null, Map.of(), Duration.ofMillis(20));
        }

        /** Convenience factory — tool-call script. */
        public static @NonNull ResponseScript toolCall(
            @NonNull String trigger,
            @NonNull String toolName,
            @NonNull Map<String, Object> toolArguments
        ) {
            return new ResponseScript(trigger, "", toolName, toolArguments, Duration.ZERO);
        }
    }
}
