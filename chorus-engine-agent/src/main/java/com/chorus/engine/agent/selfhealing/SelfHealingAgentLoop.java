package com.chorus.engine.agent.selfhealing;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.agent.loop.AgentLoopConfig;
import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-healing wrapper around {@link AgentLoop} that detects failure patterns
 * and auto-adjusts execution parameters without human intervention.
 *
 * <p>Healing strategies applied on the next round after detection:
 * <ul>
 *   <li><b>Repeated tool failures</b> → lower temperature + explicit instruction</li>
 *   <li><b>Timeout storm</b> → reduce maxTokens + add conciseness instruction</li>
 *   <li><b>Hallucination</b> → zero temperature + "use provided tools only" instruction</li>
 *   <li><b>Cascading errors</b> → lower temperature, reduce tokens, backoff</li>
 *   <li><b>Context overflow</b> → shrink maxTokens + brevity instruction</li>
 * </ul>
 *
 * <h2>Construction</h2>
 * <p>Use {@link #wrap(AgentLoopConfig, HealPolicy)} to get full healing capability
 * (temperature, model, token, and instruction changes all applied).  The legacy
 * {@link #SelfHealingAgentLoop(AgentLoop, HealPolicy)} constructor retains
 * observation-only mode for callers that build the delegate externally.
 *
 * <pre>{@code
 * SelfHealingAgentLoop loop = SelfHealingAgentLoop.wrap(
 *     AgentLoopConfig.builder("my-agent", llmClient)
 *         .systemPrompt("You are a coding assistant.")
 *         .toolExecutor(ToolExecutor.of(registry))
 *         .build(),
 *     SelfHealingAgentLoop.defaultPolicy()
 * );
 * }</pre>
 */
public final class SelfHealingAgentLoop {

    private final AgentLoop delegate;
    private final HealPolicy policy;
    private final @Nullable HealingState healingState;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    // ── Constructors ──

    /**
     * Full healing constructor. Use this when possible.
     * Healing actually modifies temperature, maxTokens, model, and injects instructions.
     */
    private SelfHealingAgentLoop(
            @NonNull AgentLoop delegate,
            @NonNull HealPolicy policy,
            @Nullable HealingState healingState) {
        this.delegate      = delegate;
        this.policy        = policy;
        this.healingState  = healingState;
    }

    /**
     * Convenience constructor for callers that build the delegate themselves.
     * Pattern detection and instruction injection work; temperature/model changes do not
     * because those require the {@link HealingLlmClientProxy} injected by {@link #wrap}.
     */
    public SelfHealingAgentLoop(@NonNull AgentLoop delegate, @NonNull HealPolicy policy) {
        this(delegate, policy, null);
    }

    /**
     * Factory that wires full healing capability.
     *
     * <p>Prepends {@link HealingMiddleware} and wraps the {@link LlmClient} with
     * {@link HealingLlmClientProxy} so all {@link HealAction} fields are applied.
     */
    public static @NonNull SelfHealingAgentLoop wrap(
            @NonNull AgentLoopConfig config,
            @NonNull HealPolicy policy) {

        HealingState state = new HealingState();

        // Proxy wraps the real LlmClient to apply temperature/maxTokens/model overrides
        LlmClient proxyClient = new HealingLlmClientProxy(config.llmClient, state);

        // HealingMiddleware injected first (priority = Integer.MIN_VALUE) for backoff + instruction
        List<Middleware> extraMiddlewares = List.of(new HealingMiddleware(state));

        // config.createLoop() handles copying all fields and applying hitlGate/toolExecutor
        AgentLoop delegate = config.createLoop(proxyClient, extraMiddlewares);
        return new SelfHealingAgentLoop(delegate, policy, state);
    }

    // ── Public API ──

    public Flow.@NonNull Publisher<AgentEvent> run(
            @NonNull String runId,
            @NonNull String userInput,
            @NonNull List<ToolDefinition> tools,
            @NonNull CancellationToken cancellationToken) {

        SessionState session = new SessionState(runId);
        sessions.put(runId, session);

        return subscriber ->
            delegate.run(runId, userInput, tools, cancellationToken)
                .subscribe(new Flow.Subscriber<>() {
                    @Override public void onSubscribe(Flow.Subscription s) {
                        subscriber.onSubscribe(s);
                    }
                    @Override public void onNext(AgentEvent event) {
                        session.observe(event);
                        maybeHeal(session);
                        subscriber.onNext(event);
                    }
                    @Override public void onError(Throwable t) {
                        sessions.remove(runId);
                        subscriber.onError(t);
                    }
                    @Override public void onComplete() {
                        sessions.remove(runId);
                        subscriber.onComplete();
                    }
                });
    }

    public void close() {
        delegate.close();
    }

    // ── Healing logic ──

    private void maybeHeal(@NonNull SessionState session) {
        FailurePattern pattern = session.detectPattern();
        if (pattern == null) return;

        HealAction action = policy.resolve(pattern, session);
        if (action == null) return;

        session.applyHeal(action);

        if (healingState != null) {
            healingState.apply(action);
        }
    }

    // ── Inner types ──

    /**
     * Per-run observation counters and recent event ring.
     */
    public static final class SessionState {
        final String runId;
        final AtomicInteger toolFailures   = new AtomicInteger(0);
        final AtomicInteger timeouts       = new AtomicInteger(0);
        final AtomicInteger hallucinations = new AtomicInteger(0);
        final AtomicInteger errors         = new AtomicInteger(0);
        final AtomicInteger rounds         = new AtomicInteger(0);
        final List<AgentEvent> recentEvents =
            Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<HealAction> lastHeal = new AtomicReference<>();
        final Instant startTime = Instant.now();

        public SessionState(String runId) { this.runId = runId; }

        public void observe(@NonNull AgentEvent event) {
            recentEvents.add(event);
            if (recentEvents.size() > 50) recentEvents.remove(0);

            switch (event) {
                case AgentEvent.RoundStart rs ->
                    rounds.incrementAndGet();
                case AgentEvent.ToolCallError te -> {
                    if (te.errorMessage() != null && te.errorMessage().contains("timeout")) {
                        timeouts.incrementAndGet();
                    } else {
                        toolFailures.incrementAndGet();
                    }
                }
                case AgentEvent.Error err -> {
                    errors.incrementAndGet();
                    if (err.errorMessage() != null
                            && (err.errorMessage().contains("unknown tool")
                                || err.errorMessage().contains("does not exist"))) {
                        hallucinations.incrementAndGet();
                    }
                }
                default -> {}
            }
        }

        public @Nullable FailurePattern detectPattern() {
            if (toolFailures.get()   >= 3) return FailurePattern.REPEATED_TOOL_FAILURES;
            if (timeouts.get()       >= 2) return FailurePattern.TIMEOUT_STORM;
            if (hallucinations.get() >= 2) return FailurePattern.HALLUCINATION;
            if (errors.get()         >= 3) return FailurePattern.CASCADING_ERRORS;
            if (rounds.get()         >= 8) return FailurePattern.CONTEXT_OVERFLOW;
            return null;
        }

        public void applyHeal(@NonNull HealAction action) {
            lastHeal.set(action);
            switch (action.pattern()) {
                case REPEATED_TOOL_FAILURES -> toolFailures.set(0);
                case TIMEOUT_STORM          -> timeouts.set(0);
                case HALLUCINATION          -> hallucinations.set(0);
                case CASCADING_ERRORS       -> errors.set(0);
                case CONTEXT_OVERFLOW       -> rounds.set(0);
            }
        }
    }

    public enum FailurePattern {
        REPEATED_TOOL_FAILURES,
        TIMEOUT_STORM,
        HALLUCINATION,
        CASCADING_ERRORS,
        CONTEXT_OVERFLOW
    }

    public record HealAction(
        @NonNull FailurePattern pattern,
        @Nullable String newModel,
        @Nullable Double newTemperature,
        @Nullable Integer newMaxTokens,
        @Nullable String extraInstruction,
        @Nullable Duration backoffDelay
    ) {}

    public interface HealPolicy {
        @Nullable HealAction resolve(
            @NonNull FailurePattern pattern,
            @NonNull SessionState state);
    }

    public static @NonNull HealPolicy defaultPolicy() {
        return (pattern, state) -> switch (pattern) {
            case REPEATED_TOOL_FAILURES -> new HealAction(
                pattern, null, 0.1, null,
                "You have encountered tool failures. Verify all arguments carefully before calling tools.",
                null);
            case TIMEOUT_STORM -> new HealAction(
                pattern, null, null, 1024,
                "External tools are slow. Reduce the number of tool calls and be concise.",
                Duration.ofSeconds(2));
            case HALLUCINATION -> new HealAction(
                pattern, null, 0.0, null,
                "Only use tools that were explicitly provided to you. Never invent tool names.",
                null);
            case CASCADING_ERRORS -> new HealAction(
                pattern, null, 0.2, 2048,
                "Multiple errors detected. Simplify your approach and verify each step before proceeding.",
                Duration.ofSeconds(5));
            case CONTEXT_OVERFLOW -> new HealAction(
                pattern, null, null, 512,
                "The context is long. Summarize your findings concisely and avoid repetition.",
                null);
        };
    }
}
