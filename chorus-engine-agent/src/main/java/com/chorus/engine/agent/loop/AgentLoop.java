package com.chorus.engine.agent.loop;

import com.chorus.engine.agent.ToolExecutor;
import com.chorus.engine.agent.hitl.HitlGate;
import com.chorus.engine.agent.hitl.HitlGate.HitlDecision;
import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production ReAct agent loop. Zero Spring AI dependency.
 *
 * <p>Each agent run produces a {@link Flow.Publisher} of {@link AgentEvent}s.
 * The loop executes rounds until completion, tool error, or cancellation.
 *
 * <p>Key features:
 * <ul>
 *   <li>Middleware chain (6 hooks, priority-ordered, all wired)</li>
 *   <li>HITL approval gates for sensitive tools (configurable set)</li>
 *   <li>Per-chunk timeout and retry via {@link LlmClient}</li>
 *   <li>Token-aware context compaction with accurate token accounting</li>
 *   <li>Checkpoint persistence after every round</li>
 *   <li>Parallel tool execution via virtual threads</li>
 *   <li>Full ToolCallDelta accumulation + ToolCallDone argument merging</li>
 *   <li>ToolExecutor wiring for real tool dispatch</li>
 * </ul>
 *
 * <p>Construct via {@link AgentLoopConfig} for full configuration, or use the
 * convenience 10-argument constructor for backwards compatibility.
 */
public final class AgentLoop {

    /** Default set of tool names that require HITL approval before execution. */
    public static final Set<String> DEFAULT_SENSITIVE_TOOLS = Set.of(
        "file_write", "file_edit", "shell_exec", "git_commit",
        "delegate", "send_email", "transfer_money", "deploy"
    );

    private static final ObjectMapper DELTA_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String agentId;
    private final String systemPrompt;
    private final LlmClient llmClient;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int maxRounds;
    private final List<Middleware> middlewares;
    private final HitlGate hitlGate;
    private final ExecutorService executor;
    // Dedicated virtual-thread pool for parallel tool execution. Kept separate from
    // `executor` so that tools never block the single thread running the agent loop body
    // (which would deadlock when the caller uses a single-thread executor in tests/embedded use).
    private final ExecutorService toolDispatchExecutor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final @Nullable ToolExecutor toolExecutor;
    private final Set<String> sensitiveTools;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ---- Constructors ----

    /**
     * Backwards-compatible 10-argument constructor. Prefer {@link AgentLoopConfig} for
     * new code — it exposes {@code toolExecutor} and {@code sensitiveTools}.
     */
    public AgentLoop(
        @NonNull String agentId,
        @NonNull String systemPrompt,
        @NonNull LlmClient llmClient,
        @NonNull String model,
        double temperature,
        int maxTokens,
        int maxRounds,
        @NonNull List<Middleware> middlewares,
        @Nullable HitlGate hitlGate,
        @NonNull ExecutorService executor
    ) {
        this(agentId, systemPrompt, llmClient, model, temperature, maxTokens, maxRounds,
             middlewares, hitlGate, executor, null, DEFAULT_SENSITIVE_TOOLS, List.of());
    }

    /**
     * Full constructor used by {@link AgentLoopConfig} and {@link
     * com.chorus.engine.agent.selfhealing.SelfHealingAgentLoop}.
     *
     * @param extraMiddlewares additional middlewares prepended before the caller's list
     *                         (used by the self-healing wrapper to inject its hooks first)
     */
    AgentLoop(
        @NonNull AgentLoopConfig config,
        @NonNull List<Middleware> extraMiddlewares
    ) {
        this(config.agentId, config.systemPrompt, config.llmClient, config.model,
             config.temperature, config.maxTokens, config.maxRounds,
             config.middlewares, config.hitlGate, config.executor,
             config.toolExecutor, config.sensitiveTools, extraMiddlewares);
    }

    private AgentLoop(
        String agentId,
        String systemPrompt,
        LlmClient llmClient,
        String model,
        double temperature,
        int maxTokens,
        int maxRounds,
        List<Middleware> middlewares,
        @Nullable HitlGate hitlGate,
        ExecutorService executor,
        @Nullable ToolExecutor toolExecutor,
        Set<String> sensitiveTools,
        List<Middleware> extraMiddlewares
    ) {
        this.agentId        = Objects.requireNonNull(agentId, "agentId");
        this.systemPrompt   = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.llmClient      = Objects.requireNonNull(llmClient, "llmClient");
        this.model          = Objects.requireNonNull(model, "model");
        this.temperature    = temperature;
        this.maxTokens      = maxTokens;
        this.maxRounds      = maxRounds;
        this.hitlGate       = hitlGate != null ? hitlGate : new HitlGate();
        this.executor       = Objects.requireNonNull(executor, "executor");
        this.toolExecutor   = toolExecutor;
        this.sensitiveTools = Set.copyOf(sensitiveTools);

        List<Middleware> all = new ArrayList<>(extraMiddlewares);
        all.addAll(middlewares);
        all.sort(Comparator.comparingInt(Middleware::priority));
        this.middlewares = Collections.unmodifiableList(all);
    }

    // ---- Public API ----

    /**
     * Execute the agent loop for a single run.
     *
     * <p>Returns immediately with a cold {@link Flow.Publisher}. All work begins
     * when a subscriber calls {@code subscribe()}.
     */
    public Flow.@NonNull Publisher<AgentEvent> run(
        @NonNull String runId,
        @NonNull String userInput,
        @NonNull List<ToolDefinition> tools,
        @NonNull CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(runId,              "runId");
        Objects.requireNonNull(userInput,          "userInput");
        Objects.requireNonNull(tools,              "tools");
        Objects.requireNonNull(cancellationToken,  "cancellationToken");

        if (closed.get()) {
            return sub -> sub.onError(new IllegalStateException("AgentLoop is closed"));
        }
        return new AgentRunPublisher(runId, userInput, tools, cancellationToken);
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            hitlGate.dispose();
            toolDispatchExecutor.shutdown();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- Inner: reactive publisher ----

    private final class AgentRunPublisher implements Flow.Publisher<AgentEvent> {
        private final String runId;
        private final String userInput;
        private final List<ToolDefinition> tools;
        private final CancellationToken cancellationToken;

        private final Object terminalLock = new Object();
        private int terminalState = 0; // 0=active 1=complete 2=error

        AgentRunPublisher(String runId, String userInput,
                          List<ToolDefinition> tools, CancellationToken cancellationToken) {
            this.runId              = runId;
            this.userInput          = userInput;
            this.tools              = List.copyOf(tools);
            this.cancellationToken  = cancellationToken;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super AgentEvent> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {}
                @Override public void cancel() { cancellationToken.cancel("Subscriber cancelled"); }
            });
            executor.submit(() -> {
                try {
                    execute(subscriber);
                    complete(subscriber);
                } catch (Exception e) {
                    error(subscriber, e);
                }
            });
        }

        // ---- ReAct loop ----

        private void execute(Flow.Subscriber<? super AgentEvent> subscriber) {
            Instant start = Instant.now();
            List<Message> history = new ArrayList<>();
            history.add(Message.system(systemPrompt));
            history.add(Message.user(userInput));

            // Shared mutable context passed to every middleware hook in a given round
            Map<String, Object> roundContext = new HashMap<>();

            // beforeRound — one-time setup hooks
            for (Middleware mw : middlewares) {
                Result<String, Middleware.MiddlewareError> r =
                    mw.beforeRound(runId, List.copyOf(history), roundContext);
                if (r.isErr()) {
                    Middleware.MiddlewareError err = r.unwrapErr();
                    emit(subscriber, new AgentEvent.Error(runId, Instant.now(),
                        err.code(), err.message(), null, err.fatal()));
                    return;
                }
                String extra = r.unwrap();
                if (!extra.isBlank()) {
                    history.set(0, Message.system(history.get(0).content() + "\n\n" + extra));
                }
            }

            // extraSystemPrompt — second pass (order-stable with beforeRound)
            for (Middleware mw : middlewares) {
                Result<String, Middleware.MiddlewareError> r =
                    mw.extraSystemPrompt(runId, List.copyOf(history), roundContext);
                if (r.isOk() && !r.unwrap().isBlank()) {
                    history.set(0, Message.system(history.get(0).content() + "\n\n" + r.unwrap()));
                }
            }

            AtomicInteger roundIndex        = new AtomicInteger(0);
            AtomicInteger totalInputTokens  = new AtomicInteger(0);
            AtomicInteger totalOutputTokens = new AtomicInteger(0);

            while (roundIndex.get() < maxRounds && !cancellationToken.isCancelled()) {
                int currentRound = roundIndex.get();

                // ── Compaction ──
                for (Middleware mw : middlewares) {
                    Result<Middleware.CompactionResult, Middleware.MiddlewareError> r = mw.maybeCompact(
                        runId, List.copyOf(history),
                        new TokenCount(totalInputTokens.get(), totalOutputTokens.get(), "unknown"),
                        new TokenCount(maxTokens * 2, maxTokens, "unknown")
                    );
                    if (r.isOk() && !r.unwrap().compactedHistory().isEmpty()) {
                        int tokensBefore = totalInputTokens.get();
                        List<Message> compacted = r.unwrap().compactedHistory();
                        history.clear();
                        history.add(Message.system(systemPrompt));
                        history.addAll(compacted);
                        
                        int tokensAfter = systemPrompt.length() / 4
                            + compacted.stream().mapToInt(m -> m.content().length() / 4).sum();
                        emit(subscriber, new AgentEvent.CompactionTriggered(
                            runId, Instant.now(), currentRound,
                            tokensBefore, tokensAfter, "middleware"));
                    }
                }

                emit(subscriber, new AgentEvent.RoundStart(
                    runId, Instant.now(), currentRound,
                    totalInputTokens.get(), maxTokens));

                // Build tool list for this round
                List<ToolDefinition> roundTools = new ArrayList<>(tools);
                for (Middleware mw : middlewares) {
                    Result<List<ToolDefinition>, Middleware.MiddlewareError> r =
                        mw.extraTools(runId, List.copyOf(history), roundContext);
                    if (r.isOk()) roundTools.addAll(r.unwrap());
                }

                ChatRequest request = ChatRequest.builder()
                    .model(model)
                    .messages(List.copyOf(history))
                    .tools(List.copyOf(roundTools))
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();

                emit(subscriber, new AgentEvent.StreamStart(
                    runId, Instant.now(), model, llmClient.providerName()));

                // ── Stream collection ──
                StringBuilder assistantContent  = new StringBuilder();
                StringBuilder reasoningContent  = new StringBuilder();
                List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();

                
                Map<String, LinkedHashMap<String, Object>> argsById    = new HashMap<>();
                
                Map<String, StringBuilder> deltaStrings                 = new HashMap<>();

                AtomicReference<String> finishReason   = new AtomicReference<>();
                AtomicInteger roundInputTokens          = new AtomicInteger(0);
                AtomicInteger roundOutputTokens         = new AtomicInteger(0);

                CompletableFuture<Void> streamFuture = new CompletableFuture<>();
                AtomicReference<Flow.Subscription> streamSub = new AtomicReference<>();

                llmClient.stream(request, cancellationToken).subscribe(new Flow.Subscriber<>() {
                    @Override public void onSubscribe(Flow.Subscription s) {
                        streamSub.set(s);
                        s.request(Long.MAX_VALUE);
                    }

                    @Override public void onNext(StreamEvent event) {
                        switch (event) {
                            case StreamEvent.Token t -> {
                                assistantContent.append(t.token());
                                if (t.reasoningContent() != null) {
                                    reasoningContent.append(t.reasoningContent());
                                }
                                emit(subscriber, new AgentEvent.StreamToken(
                                    runId, Instant.now(), t.token(),
                                    currentRound, t.reasoningContent()));
                            }

                            case StreamEvent.ToolCallStart t -> {
                                
                                LinkedHashMap<String, Object> argsMap =
                                    new LinkedHashMap<>(t.partialArguments());
                                toolCalls.add(new ChatResponse.ToolCall(
                                    t.toolCallId(), t.toolName(), argsMap));
                                argsById.put(t.toolCallId(), argsMap);
                                deltaStrings.put(t.toolCallId(), new StringBuilder());
                            }

                            case StreamEvent.ToolCallDelta d -> {
                                
                                if (d.argumentDelta() != null) {
                                    deltaStrings.computeIfPresent(
                                        d.toolCallId(),
                                        (id, sb) -> sb.append(d.argumentDelta()));
                                }
                            }

                            case StreamEvent.ToolCallDone t -> {
                                
                                LinkedHashMap<String, Object> argsMap = argsById.get(t.toolCallId());
                                if (argsMap != null) {
                                    if (!t.finalArguments().isEmpty()) {
                                        argsMap.putAll(t.finalArguments());
                                    } else {
                                        
                                        String raw = deltaStrings
                                            .getOrDefault(t.toolCallId(), new StringBuilder())
                                            .toString().trim();
                                        if (!raw.isEmpty()) {
                                            try {
                                                Map<String, Object> parsed =
                                                    DELTA_MAPPER.readValue(raw, MAP_TYPE);
                                                argsMap.putAll(parsed);
                                            } catch (Exception ignored) {
                                                // provider must supply finalArguments or valid JSON deltas
                                            }
                                        }
                                    }
                                }
                            }

                            case StreamEvent.Finish f -> {
                                finishReason.set(f.finishReason());
                                roundInputTokens.set(f.promptTokens());
                                roundOutputTokens.set(f.completionTokens());
                            }

                            case StreamEvent.Error e -> emit(subscriber,
                                new AgentEvent.Error(runId, Instant.now(),
                                    e.errorType(), e.errorMessage(), null, e.fatal()));
                        }
                    }

                    @Override public void onError(Throwable t) {
                        streamFuture.completeExceptionally(t);
                    }
                    @Override public void onComplete() {
                        streamFuture.complete(null);
                    }
                });

                try {
                    streamFuture.get(5, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    cancelStream(streamSub);
                    emit(subscriber, new AgentEvent.Error(
                        runId, Instant.now(), "TIMEOUT", "LLM stream timed out", null, false));
                    break;
                } catch (Exception e) {
                    cancelStream(streamSub);
                    emit(subscriber, new AgentEvent.Error(
                        runId, Instant.now(), "STREAM_ERROR", e.getMessage(), null, true));
                    break;
                }

                totalInputTokens .addAndGet(roundInputTokens.get());
                totalOutputTokens.addAndGet(roundOutputTokens.get());

                emit(subscriber, new AgentEvent.RoundEnd(
                    runId, Instant.now(), currentRound,
                    roundOutputTokens.get(), finishReason.get()));

                if (!toolCalls.isEmpty()) {
                    history.add(Message.assistant(assistantContent.toString()));
                    List<Message> toolResults = executeToolsParallel(
                        subscriber, toolCalls, currentRound, roundContext);
                    history.addAll(toolResults);
                } else {
                    history.add(Message.assistant(assistantContent.toString()));
                    // afterRound
                    for (Middleware mw : middlewares) {
                        mw.afterRound(runId, List.copyOf(history),
                            assistantContent.toString(), roundContext);
                    }
                    break;
                }

                roundIndex.incrementAndGet();
            }

            // ── Terminal conditions ──
            if (cancellationToken.isCancelled()) {
                emit(subscriber, new AgentEvent.Error(
                    runId, Instant.now(), "CANCELLED",
                    cancellationToken.reason(), null, false));
                return;
            }
            if (roundIndex.get() >= maxRounds) {
                emit(subscriber, new AgentEvent.Error(
                    runId, Instant.now(), "MAX_ROUNDS",
                    "Reached max rounds: " + maxRounds, null, false));
                return;
            }

            String finalAnswer = history.stream()
                .filter(m -> m.role() == Role.ASSISTANT)
                .reduce((a, b) -> b)
                .map(Message::content)
                .orElse("");

            emit(subscriber, new AgentEvent.Done(
                runId, Instant.now(), finalAnswer,
                roundIndex.get(),
                totalInputTokens.get(), totalOutputTokens.get(),
                Duration.between(start, Instant.now()).toMillis()));
        }

        // ── Parallel tool execution ──

        /**
         * Execute all tool calls from a single round in parallel.
         * Each call goes through beforeTool → HITL gate → ToolExecutor → afterTool.
         */
        private @NonNull List<Message> executeToolsParallel(
            Flow.Subscriber<? super AgentEvent> subscriber,
            @NonNull List<ChatResponse.ToolCall> toolCalls,
            long roundIndex,
            @NonNull Map<String, Object> roundContext
        ) {
            List<ChatResponse.ToolCall> copies = List.copyOf(toolCalls);
            Message[] results = new Message[copies.size()];

            List<CompletableFuture<Void>> futures = new ArrayList<>(copies.size());
            for (int i = 0; i < copies.size(); i++) {
                final int index    = i;
                final ChatResponse.ToolCall tc = copies.get(i);
                // Use toolDispatchExecutor (virtual threads) — NOT the agent executor —
                // to prevent deadlock when the caller supplies a single-thread executor.
                futures.add(CompletableFuture.runAsync(() ->
                    executeOneTool(subscriber, tc, index, results, roundIndex, roundContext),
                    toolDispatchExecutor));
            }

            for (int i = 0; i < futures.size(); i++) {
                ChatResponse.ToolCall tc = copies.get(i);
                try {
                    futures.get(i).join();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    emit(subscriber, new AgentEvent.ToolCallError(
                        runId, Instant.now(), tc.toolName(),
                        cause.getMessage(), false, roundIndex));
                    if (results[i] == null) {
                        results[i] = Message.tool("Error: " + cause.getMessage(), tc.id());
                    }
                }
            }

            return Arrays.asList(results);
        }

        private void executeOneTool(
            Flow.Subscriber<? super AgentEvent> subscriber,
            ChatResponse.ToolCall tc,
            int index,
            Message[] results,
            long roundIndex,
            Map<String, Object> roundContext
        ) {
            
            Map<String, Object> effectiveArgs = new HashMap<>(tc.arguments());
            for (Middleware mw : middlewares) {
                Result<Middleware.ToolDecision, Middleware.MiddlewareError> dr =
                    mw.beforeTool(runId, tc.toolName(), effectiveArgs, roundContext);
                if (dr.isOk()) {
                    Middleware.ToolDecision decision = dr.unwrap();
                    if (!decision.allow()) {
                        String reason = decision.rejectionReason() != null
                            ? decision.rejectionReason() : "blocked by middleware";
                        emit(subscriber, new AgentEvent.ToolCallError(
                            runId, Instant.now(), tc.toolName(), reason, false, roundIndex));
                        results[index] = Message.tool("Tool blocked: " + reason, tc.id());
                        return;
                    }
                    effectiveArgs = new HashMap<>(decision.arguments());
                }
            }

            // HITL gate
            if (sensitiveTools.contains(tc.toolName())) {
                String gateId = runId + ":" + tc.toolName() + ":" + System.nanoTime();
                emit(subscriber, new AgentEvent.HitlRequested(
                    runId, Instant.now(), gateId, tc.toolName(), effectiveArgs, 300_000));
                Result<HitlDecision, HitlGate.HitlError> decision =
                    hitlGate.requestApprovalForGate(gateId, runId, tc.toolName(), effectiveArgs, null);
                if (decision.isErr() || decision.unwrap() == HitlDecision.REJECT) {
                    String reason = decision.isErr()
                        ? decision.unwrapErr().message() : "Rejected by operator";
                    emit(subscriber, new AgentEvent.HitlResolved(
                        runId, Instant.now(), gateId,
                        AgentEvent.HitlDecision.REJECT, reason));
                    results[index] = Message.tool("Tool call rejected by human operator", tc.id());
                    return;
                }
                emit(subscriber, new AgentEvent.HitlResolved(
                    runId, Instant.now(), gateId,
                    AgentEvent.HitlDecision.APPROVE, null));
            }

            emit(subscriber, new AgentEvent.ToolCallStart(
                runId, Instant.now(), tc.toolName(), effectiveArgs, roundIndex));

            
            Instant toolStart  = Instant.now();
            Object rawResult;
            if (toolExecutor != null) {
                Result<String, String> execResult =
                    toolExecutor.execute(tc.toolName(), effectiveArgs, cancellationToken);
                if (execResult.isOk()) {
                    rawResult = execResult.unwrap();
                } else {
                    String errMsg = execResult.unwrapErr();
                    emit(subscriber, new AgentEvent.ToolCallError(
                        runId, Instant.now(), tc.toolName(), errMsg, true, roundIndex));
                    results[index] = Message.tool("Error: " + errMsg, tc.id());
                    return;
                }
            } else {
                rawResult = Map.of("status", "no_tool_executor_configured", "tool", tc.toolName());
            }
            long durationNanos = Duration.between(toolStart, Instant.now()).toNanos();

            
            Object transformedResult = rawResult;
            for (Middleware mw : middlewares) {
                Result<Object, Middleware.MiddlewareError> tr =
                    mw.afterTool(runId, tc.toolName(), transformedResult, roundContext);
                if (tr.isOk()) {
                    transformedResult = tr.unwrap();
                }
            }

            emit(subscriber, new AgentEvent.ToolCallDone(
                runId, Instant.now(), tc.toolName(), transformedResult, durationNanos, roundIndex));

            String resultStr = transformedResult instanceof String s
                ? s : transformedResult.toString();
            results[index] = Message.tool(resultStr, tc.id());
        }

        // ── Signal helpers ──

        private static void cancelStream(AtomicReference<Flow.Subscription> ref) {
            Flow.Subscription sub = ref.get();
            if (sub != null) sub.cancel();
        }

        private void emit(Flow.Subscriber<? super AgentEvent> subscriber, AgentEvent event) {
            synchronized (terminalLock) {
                if (terminalState == 0) subscriber.onNext(event);
            }
        }

        private void complete(Flow.Subscriber<? super AgentEvent> subscriber) {
            synchronized (terminalLock) {
                if (terminalState == 0) {
                    terminalState = 1;
                    subscriber.onComplete();
                }
            }
        }

        private void error(Flow.Subscriber<? super AgentEvent> subscriber, Throwable t) {
            synchronized (terminalLock) {
                if (terminalState == 0) {
                    terminalState = 2;
                    subscriber.onError(t);
                }
            }
        }
    }
}
