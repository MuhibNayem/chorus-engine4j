package com.chorus.engine.core.loop;

import com.chorus.engine.core.ApprovalPolicy;
import com.chorus.engine.core.HandoffSignal;
import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.CheckpointState;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.event.HitlDecision;
import com.chorus.engine.core.event.HitlRequest;
import com.chorus.engine.core.hitl.HitlGate;
import com.chorus.engine.core.llm.ChorusChatModel;
import com.chorus.engine.core.middleware.AgentMiddleware;
import com.chorus.engine.core.tool.AgentTool;
import com.chorus.engine.core.trace.TraceCarrier;
import com.chorus.engine.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Core ReAct agent loop. Streams structured events via {@link Flux}.
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ChorusChatModel chatModel;
    private final String model;
    private final List<AgentTool> tools;
    private final List<ChatMessage> initialMessages;
    private final String systemPrompt;
    private final String threadId;
    private final HitlGate hitlGate;
    private final ApprovalPolicy policy;
    private final com.chorus.engine.core.checkpoint.Checkpointer checkpointer;
    private final int maxRounds;
    private final Optional<HitlDecision> resumedDecision;
    private final List<AgentMiddleware> middleware;
    private final AtomicBoolean abortSignal;
    private final long streamTimeoutMs;
    private final Map<String, Object> outputSchema;
    private final BtwQueue btwQueue;
    private final Optional<TraceContext> traceContext;

    public AgentLoop(LoopOptions options) {
        this.chatModel = options.chatModel();
        this.model = options.model();
        this.tools = options.tools();
        this.initialMessages = options.messages();
        this.systemPrompt = options.systemPrompt();
        this.threadId = options.threadId();
        this.hitlGate = options.hitlGate();
        this.policy = options.policy();
        this.checkpointer = options.checkpointer();
        this.maxRounds = options.maxRounds();
        this.resumedDecision = options.resumedDecision();
        this.middleware = options.middleware();
        this.abortSignal = options.abortSignal();
        this.streamTimeoutMs = options.streamTimeoutMs();
        this.outputSchema = options.outputSchema();
        this.btwQueue = new BtwQueue();
        this.traceContext = options.traceContext();
    }

    public BtwQueue getBtwQueue() {
        return btwQueue;
    }

    public Flux<AgentEvent> run() {
        return Flux.defer(() -> {
            TraceContext ctx = traceContext.orElseGet(TraceCarrier::getOrCreate);
            TraceCarrier.set(ctx);
            Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    executeLoop(sink, ctx);
                } finally {
                    TraceCarrier.clear();
                }
            });
            return sink.asFlux();
        });
    }

    private void executeLoop(Sinks.Many<AgentEvent> sink, TraceContext activeTrace) {
        Instant spanStart = Instant.now();
        log.info("[trace={}] AgentLoop started thread={} model={}", activeTrace.traceId(), threadId, model);
        try {
            Checkpoint saved = checkpointer.load(threadId).join();
            boolean restoreFromCheckpoint = saved != null && saved.waitingForHitl().isPresent();
            int round = restoreFromCheckpoint ? saved.round() : 0;
            List<ChatMessage> history = new ArrayList<>(restoreFromCheckpoint ? saved.messages() : initialMessages);

            sink.tryEmitNext(new AgentEvent.CheckpointLoadedEvent(round, threadId, restoreFromCheckpoint));
            sink.tryEmitNext(new AgentEvent.TraceEvent(activeTrace.traceId(), activeTrace.parentId(), activeTrace.toTraceparent()));

            int totalTools = 0;
            Instant loopStart = Instant.now();
            AtomicInteger totalInputTokens = new AtomicInteger();
            AtomicInteger totalOutputTokens = new AtomicInteger();
            Optional<HitlDecision> pendingDecision = resumedDecision;

            while (round < maxRounds) {
                if (abortSignal.get()) {
                    sink.tryEmitNext(new AgentEvent.AbortedEvent("Interrupted by user."));
                    sink.tryEmitComplete();
                    return;
                }

                for (String text : btwQueue.drain()) {
                    history.add(ChatMessage.user("[/btw] " + text));
                    sink.tryEmitNext(new AgentEvent.BtwEvent(text));
                }

                sink.tryEmitNext(new AgentEvent.RoundStartEvent(round, threadId, history.size()));

                AgentMiddleware.RoundContext roundCtx = new AgentMiddleware.RoundContext(round, threadId, model, List.copyOf(history), 0);
                runMiddlewareBeforeRound(roundCtx).join();
                sink.tryEmitNext(new AgentEvent.MiddlewareBeforeEvent(round, "beforeRound"));

                List<AgentTool> allTools = new ArrayList<>(tools);
                for (AgentMiddleware mw : middleware) {
                    allTools.addAll(mw.extraTools());
                }
                Map<String, AgentTool> toolsByName = allTools.stream()
                    .filter(t -> t.name() != null && !t.name().isEmpty())
                    .collect(Collectors.toMap(AgentTool::name, t -> t, (a, b) -> a, LinkedHashMap::new));

                for (AgentMiddleware mw : middleware) {
                    mw.setTools(toolsByName);
                }

                List<String> extraPrompts = new ArrayList<>();
                for (AgentMiddleware mw : middleware) {
                    String extra = mw.extraSystemPrompt();
                    if (extra != null) extraPrompts.add(extra);
                }
                String effectiveSystemPrompt = extraPrompts.isEmpty()
                    ? systemPrompt
                    : systemPrompt + "\n\n" + String.join("\n\n", extraPrompts);

                for (AgentMiddleware mw : middleware) {
                    AgentMiddleware.CompactResult compact = mw.maybeCompact(List.copyOf(history),
                        new AgentMiddleware.CompactOptions(model, effectiveSystemPrompt)).join();
                    if (compact != null) {
                        history = new ArrayList<>(compact.replacement());
                        sink.tryEmitNext(new AgentEvent.CompactedEvent(compact.removedMessages(), compact.savedTokens()));
                        break;
                    }
                }

                sink.tryEmitNext(new AgentEvent.StreamStartEvent(round, threadId, model));

                Instant llmSpanStart = Instant.now();
                ChorusChatModel.ModelResponse response = callLlm(history, effectiveSystemPrompt, toolsByName, activeTrace);
                log.debug("[trace={}] LLM call completed in {}ms", activeTrace.traceId(),
                    Duration.between(llmSpanStart, Instant.now()).toMillis());
                if (response == null) {
                    sink.tryEmitNext(new AgentEvent.ErrorEvent("Agent loop exited stream consumption without a response.", true));
                    sink.tryEmitComplete();
                    return;
                }

                sink.tryEmitNext(new AgentEvent.StreamEndEvent(round, threadId, response.outputTokens()));
                totalInputTokens.addAndGet(response.inputTokens());
                totalOutputTokens.addAndGet(response.outputTokens());

                String assistantContent = response.content();
                List<ChatMessage.ToolCall> toolCalls = response.toolCalls().stream()
                    .map(tc -> new ChatMessage.ToolCall(tc.id(), tc.name(), tc.arguments()))
                    .toList();

                history.add(ChatMessage.assistantWithToolCalls(assistantContent, toolCalls));

                if (toolCalls.isEmpty()) {
                    if (outputSchema != null && !outputSchema.isEmpty()) {
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            mapper.readTree(assistantContent);
                        } catch (Exception e) {
                            String correction = "Your response must be a valid JSON object matching the required schema. " +
                                "Validation error: " + e.getMessage() + ". " +
                                "Please respond with a valid JSON object only — no prose, no markdown fences.";
                            history.add(ChatMessage.user(correction));
                            round++;
                            checkpointer.save(threadId, new CheckpointState(List.copyOf(history), round, Optional.empty())).join();
                            sink.tryEmitNext(new AgentEvent.CheckpointEvent(round, threadId));
                            continue;
                        }
                    }

                    checkpointer.save(threadId, new CheckpointState(List.copyOf(history), round, Optional.empty())).join();
                    sink.tryEmitNext(new AgentEvent.CheckpointEvent(round, threadId));

                    Duration elapsed = Duration.between(loopStart, Instant.now());
                    double cost = chatModel.estimateCost(totalInputTokens.get(), totalOutputTokens.get());
                    sink.tryEmitNext(new AgentEvent.DoneEvent(
                        assistantContent, "", totalTools, List.copyOf(history),
                        totalInputTokens.get(), totalOutputTokens.get(), cost, elapsed.toMillis()
                    ));
                    sink.tryEmitComplete();
                    return;
                }

                totalTools += toolCalls.size();
                List<HitlRequest> requests = toHitlRequests(toolCalls);
                HitlDecision decision = pendingDecision.orElse(null);
                pendingDecision = Optional.empty();

                if (decision == null && hitlGate.shouldPause(toolCalls, policy)) {
                    String resumeKey = "hitl-" + threadId + "-" + round;
                    CheckpointState.HitlPause pause = new CheckpointState.HitlPause(
                        resumeKey, requests, toolCalls, history.get(history.size() - 1)
                    );
                    checkpointer.save(threadId, new CheckpointState(List.copyOf(history), round, Optional.of(pause))).join();
                    sink.tryEmitNext(new AgentEvent.CheckpointEvent(round, threadId));
                    sink.tryEmitNext(new AgentEvent.HitlEvent(requests, resumeKey));
                    decision = hitlGate.waitForDecision(resumeKey).join();
                }

                if (decision != null) {
                    if (decision instanceof HitlDecision.Reject reject) {
                        String msg = reject.message().orElse("Tool execution denied by user.");
                        history.add(ChatMessage.user(msg));
                        checkpointer.save(threadId, new CheckpointState(List.copyOf(history), round, Optional.empty())).join();
                        sink.tryEmitNext(new AgentEvent.CheckpointEvent(round, threadId));
                        Duration elapsed = Duration.between(loopStart, Instant.now());
                        sink.tryEmitNext(new AgentEvent.DoneEvent(
                            assistantContent, "", totalTools, List.copyOf(history),
                            totalInputTokens.get(), totalOutputTokens.get(),
                            chatModel.estimateCost(totalInputTokens.get(), totalOutputTokens.get()),
                            elapsed.toMillis()
                        ));
                        sink.tryEmitComplete();
                        return;
                    }
                }

                int toolCallsThisRound = 0;
                for (ChatMessage.ToolCall toolCall : toolCalls) {
                    String name = toolCall.name();
                    Map<String, Object> args = parseArgs(toolCall.arguments());

                    AgentMiddleware.ToolDirective directive = null;
                    for (AgentMiddleware mw : middleware) {
                        directive = mw.beforeTool(new AgentMiddleware.BeforeToolContext(toolCall.id(), name, args)).join();
                        if (directive != null && directive.cancel()) break;
                    }

                    if (directive != null && directive.cancel()) {
                        history.add(ChatMessage.tool(toolCall.id(), directive.result()));
                        sink.tryEmitNext(new AgentEvent.ToolDoneEvent(toolCall.id(), name, directive.result(), 0));
                        toolCallsThisRound++;
                        continue;
                    }

                    toolCallsThisRound++;
                    sink.tryEmitNext(new AgentEvent.ToolStartEvent(toolCall.id(), name, args));
                    long startedAt = System.currentTimeMillis();

                    try {
                        AgentTool tool = toolsByName.get(name);
                        if (tool == null) {
                            throw new IllegalArgumentException("Unknown tool: " + name);
                        }
                        String rawResult = tool.invoke(args).join();
                        long duration = System.currentTimeMillis() - startedAt;

                        String result = rawResult;
                        for (AgentMiddleware mw : middleware) {
                            String transformed = mw.afterTool(new AgentMiddleware.ToolResultContext(toolCall.id(), name, result, duration)).join();
                            if (transformed != null) result = transformed;
                        }

                        history.add(ChatMessage.tool(toolCall.id(), result));
                        sink.tryEmitNext(new AgentEvent.ToolDoneEvent(toolCall.id(), name, result, duration));
                    } catch (HandoffSignal handoff) {
                        history.add(ChatMessage.tool(toolCall.id(), "[Handoff to " + handoff.targetAgent() + "]"));
                        sink.tryEmitNext(new AgentEvent.HandoffEvent(
                            handoff.targetAgent(), handoff.taskDescription(), handoff.artifacts(), handoff.reasoning()
                        ));
                        checkpointer.save(threadId, new CheckpointState(List.copyOf(history), round, Optional.empty())).join();
                        sink.tryEmitNext(new AgentEvent.CheckpointEvent(round, threadId));
                        Duration elapsed = Duration.between(loopStart, Instant.now());
                        sink.tryEmitNext(new AgentEvent.DoneEvent(
                            assistantContent, "", totalTools, List.copyOf(history),
                            totalInputTokens.get(), totalOutputTokens.get(),
                            chatModel.estimateCost(totalInputTokens.get(), totalOutputTokens.get()),
                            elapsed.toMillis()
                        ));
                        sink.tryEmitComplete();
                        return;
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        history.add(ChatMessage.tool(toolCall.id(), "Error: " + msg));
                        sink.tryEmitNext(new AgentEvent.ToolErrorEvent(toolCall.id(), name, msg, false));
                    }
                }

                round++;
                AgentMiddleware.RoundContext afterCtx = new AgentMiddleware.RoundContext(round, threadId, model, List.copyOf(history), toolCallsThisRound);
                runMiddlewareAfterRound(afterCtx).join();
                sink.tryEmitNext(new AgentEvent.MiddlewareAfterEvent(round, "afterRound"));

                checkpointer.save(threadId, new CheckpointState(List.copyOf(history), round, Optional.empty())).join();
                sink.tryEmitNext(new AgentEvent.CheckpointEvent(round, threadId));
                sink.tryEmitNext(new AgentEvent.CheckpointSavedEvent(round, threadId, "sync"));
                sink.tryEmitNext(new AgentEvent.RoundEndEvent(round, threadId, toolCallsThisRound));
            }

            sink.tryEmitNext(new AgentEvent.ErrorEvent("Agent loop exceeded max rounds (" + maxRounds + ").", true));
            sink.tryEmitComplete();
        } catch (Exception e) {
            log.error("[trace={}] Fatal error in agent loop", activeTrace.traceId(), e);
            sink.tryEmitNext(new AgentEvent.ErrorEvent(e.getMessage(), true));
            sink.tryEmitComplete();
        } finally {
            log.info("[trace={}] AgentLoop finished thread={} duration={}ms",
                activeTrace.traceId(), threadId, Duration.between(spanStart, Instant.now()).toMillis());
        }
    }

    private ChorusChatModel.ModelResponse callLlm(List<ChatMessage> history, String effectiveSystemPrompt,
                                                   Map<String, AgentTool> toolsByName, TraceContext trace) {
        List<ChorusChatModel.ToolDef> toolDefs = toolsByName.values().stream()
            .map(t -> new ChorusChatModel.ToolDef(t.name(), t.description(), t.schema()))
            .toList();
        log.debug("[trace={}] Calling LLM model={} tools={}", trace.traceId(), model, toolDefs.size());
        return chatModel.generate(history, effectiveSystemPrompt, toolDefs, model).join();
    }

    private List<HitlRequest> toHitlRequests(List<ChatMessage.ToolCall> toolCalls) {
        return toolCalls.stream()
            .map(tc -> new HitlRequest(tc.id(), tc.name(), parseArgs(tc.arguments()), null))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(arguments, Map.class);
        } catch (Exception e) {
            return Map.of("_raw", arguments);
        }
    }

    private CompletableFuture<Void> runMiddlewareBeforeRound(AgentMiddleware.RoundContext ctx) {
        Map<Integer, List<AgentMiddleware>> groups = middleware.stream()
            .collect(Collectors.groupingBy(AgentMiddleware::priority, TreeMap::new, Collectors.toList()));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<AgentMiddleware> group : groups.values()) {
            List<CompletableFuture<Void>> futures = group.stream()
                .map(mw -> mw.beforeRound(ctx))
                .toList();
            chain = chain.thenCompose(v -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])));
        }
        return chain;
    }

    private CompletableFuture<Void> runMiddlewareAfterRound(AgentMiddleware.RoundContext ctx) {
        Map<Integer, List<AgentMiddleware>> groups = middleware.stream()
            .collect(Collectors.groupingBy(AgentMiddleware::priority, TreeMap::new, Collectors.toList()));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<AgentMiddleware> group : groups.values()) {
            List<CompletableFuture<Void>> futures = group.stream()
                .map(mw -> mw.afterRound(ctx))
                .toList();
            chain = chain.thenCompose(v -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])));
        }
        return chain;
    }
}
