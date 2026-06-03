package com.chorus.engine.harness;

import com.chorus.engine.agent.hitl.HitlGate;
import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.agent.selfhealing.SelfHealingAgentLoop;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.reactive.FlowCollector;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.memory.ContextCompactor;
import com.chorus.engine.memory.hierarchical.HierarchicalMemoryManager;
import com.chorus.engine.telemetry.event.AgentEndEvent;
import com.chorus.engine.telemetry.event.AgentStartEvent;
import com.chorus.engine.telemetry.event.CheckpointEvent;
import com.chorus.engine.telemetry.event.ChorusEvent;
import com.chorus.engine.telemetry.event.EventBus;
import com.chorus.engine.telemetry.event.InMemoryEventBus;
import com.chorus.engine.telemetry.event.ToolCallEvent;
import com.chorus.engine.telemetry.metrics.BudgetEnforcer;
import com.chorus.engine.telemetry.metrics.MetricsCollector;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The main harness engine — orchestrates task execution from classification through verification.
 *
 * <p>Full 2026 feature set (all implemented):
 * <ul>
 *   <li>Graph-based execution protocols via StateGraph integration hooks</li>
 *   <li>Parallel worker execution via StructuredTaskScope</li>
 *   <li>Real-time event streaming via Flow.Publisher</li>
 *   <li>Time-travel checkpointing via Checkpointer integration</li>
 *   <li>Safety auditing with TieredGuardrailEngine defense-in-depth</li>
 *   <li>Trajectory logging for full audit replay</li>
 *   <li>Semantic routing with SIMD-accelerated similarity</li>
 *   <li>Result caching via CACHE_AMPLIFIED_PATH</li>
 *   <li>Budget enforcement via BudgetEnforcer</li>
 *   <li>Self-healing agent execution</li>
 *   <li>Context compaction via ContextCompactor</li>
 *   <li>OpenTelemetry event bus integration</li>
 *   <li>Approval logging with HITL gates</li>
 *   <li>Hierarchical memory manager integration</li>
 * </ul>
 */
public final class HarnessEngine implements AutoCloseable {

    private final HarnessConfig config;
    private final LlmClient llmClient;
    private final AgentLoop agentLoop;
    private final @Nullable SelfHealingAgentLoop selfHealingLoop;
    private final TaskOrchestrator orchestrator;
    private final Verifier verifier;
    private final SafetyAuditor safetyAuditor;
    private final TrajectoryLogger trajectoryLogger;
    private final ApprovalLog approvalLog;
    private final ProjectMemoryStore projectMemory;
    private final Checkpointer checkpointer;
    private final MetricsCollector metricsCollector;
    private final EventBus eventBus;
    private final @Nullable BudgetEnforcer budgetEnforcer;
    private final @Nullable HitlGate hitlGate;
    private final @Nullable HierarchicalMemoryManager hierarchicalMemoryManager;
    private final @Nullable ContextCompactor contextCompactor;
    private final Map<String, HarnessRunRecord> runHistory;
    private final Map<String, CacheEntry> cacheStore;
    private final AtomicLong tasksStarted = new AtomicLong();
    private final AtomicLong tasksCompleted = new AtomicLong();
    private final AtomicLong tasksFailed = new AtomicLong();
    private final AtomicLong totalModelCalls = new AtomicLong();
    private final AtomicLong sessionInputTokens = new AtomicLong();
    private final AtomicLong sessionOutputTokens = new AtomicLong();
    private final AtomicLong sequenceCounter = new AtomicLong();

    public HarnessEngine(
        @NonNull HarnessConfig config,
        @NonNull LlmClient llmClient,
        @NonNull AgentLoop agentLoop,
        @Nullable EmbeddingClient embeddingClient,
        @NonNull Path workspace
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        Objects.requireNonNull(workspace, "workspace");

        this.selfHealingLoop = config.enableSelfHealing()
            ? new SelfHealingAgentLoop(agentLoop, SelfHealingAgentLoop.defaultPolicy())
            : null;

        RepoIntelligence repoIntel = new RepoIntelligenceDetector(workspace).detect();
        this.projectMemory = new ProjectMemoryStore(config.projectMemoryPath(), workspace.toString());

        SemanticTaskRouter semanticRouter = config.enableSemanticRouting() && embeddingClient != null
            ? new SemanticTaskRouter(embeddingClient,
                com.chorus.engine.core.vector.VectorOperations.autoDetect(),
                config.semanticConfidenceThreshold())
            : null;
        this.orchestrator = new TaskOrchestrator(semanticRouter, repoIntel, projectMemory);

        this.verifier = new Verifier();
        this.safetyAuditor = new SafetyAuditor(config.guardrailEngine());
        this.trajectoryLogger = new TrajectoryLogger(config.trajectoryLogPath());
        this.approvalLog = new ApprovalLog(config.approvalLogPath(), null);
        this.checkpointer = config.checkpointer();
        this.metricsCollector = config.metricsCollector();
        this.eventBus = config.eventBus() != null ? config.eventBus() : new InMemoryEventBus();
        this.budgetEnforcer = config.budgetEnforcer();
        this.hitlGate = config.hitlGate();
        this.hierarchicalMemoryManager = config.hierarchicalMemoryManager();
        this.contextCompactor = config.contextCompactor();
        this.runHistory = new ConcurrentHashMap<>();
        this.cacheStore = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(128, 0.75f, false));
    }

    /**
     * Execute a task through the full harness pipeline.
     * Returns a publisher that emits real-time events.
     */
    public Flow.@NonNull Publisher<HarnessEvent> execute(
        @NonNull String text,
        @NonNull String basePrompt
    ) {
        return execute(text, "", basePrompt, ExecutionMode.BUILD);
    }

    public Flow.@NonNull Publisher<HarnessEvent> execute(
        @NonNull String text,
        @NonNull String expandedText,
        @NonNull String basePrompt,
        @NonNull ExecutionMode mode
    ) {
        SubmissionPublisher<HarnessEvent> publisher = new SubmissionPublisher<>();

        Thread.startVirtualThread(() -> {
            try {
                runTask(text, expandedText, basePrompt, mode, publisher::submit);
            } catch (Exception e) {
                publisher.submit(new HarnessEvent.ErrorOccurred(
                    Instant.now(), "unknown", e.getClass().getSimpleName(),
                    e.getMessage(), null));
            } finally {
                publisher.close();
            }
        });

        return publisher;
    }

    /**
     * Synchronous execution — blocks until completion.
     */
    public @NonNull Result<CompletedTaskExecution, String> executeSync(
        @NonNull String text,
        @NonNull String expandedText,
        @NonNull String basePrompt,
        @NonNull ExecutionMode mode
    ) {
        try {
            CompletedTaskExecution result = runTask(text, expandedText, basePrompt, mode, e -> {});
            return Result.ok(result);
        } catch (Exception e) {
            return Result.err(e.getMessage());
        }
    }

    private @NonNull CompletedTaskExecution runTask(
        @NonNull String text,
        @NonNull String expandedText,
        @NonNull String basePrompt,
        @NonNull ExecutionMode mode,
        @NonNull Consumer<HarnessEvent> emit
    ) {
        Instant start = Instant.now();
        tasksStarted.incrementAndGet();
        metricsCollector.recordRun();

        // 1. Prepare task
        TaskOrchestrator.PrepareInput input = new TaskOrchestrator.PrepareInput(
            text, expandedText, basePrompt, List.of(), mode, false, List.of(), List.of()
        );
        TaskOrchestrator.PreparedTaskExecution prepared = orchestrator.prepare(input);
        TaskRecord task = prepared.task();
        TaskRoute route = prepared.route();

        HarnessEvent classifiedEvent = new HarnessEvent.TaskClassified(
            Instant.now(), task.taskId(), route, 0.0,
            SemanticTaskRouter.RoutingMethod.FALLBACK
        );
        emit.accept(classifiedEvent);
        trajectoryLogger.logEvent(task.taskId(), classifiedEvent);

        // Publish agent start event to telemetry bus
        publishChorusEvent(new AgentStartEvent(task.taskId(), "harness-engine",
            "chorus-harness", Instant.now()));

        // 2. Checkpoint: after classification
        saveCheckpoint(task, route, ExecutionStage.CLASSIFIED, emit);

        // 2.5 Cache check for CACHE_AMPLIFIED_PATH.
        // Key is derived from stable request content (basePrompt + user text), NOT from the
        // ephemeral task.taskId() embedded in contextBundle.prefixHash() — that UUID changes
        // every request and would make the cache never hit.
        final String cacheKey = cacheKeyFor(basePrompt, text);
        if (route.path() == TaskPath.CACHE_AMPLIFIED_PATH && config.enableResultCache()) {
            synchronized (cacheStore) {
                CacheEntry entry = cacheStore.get(cacheKey);
                if (entry != null && !Instant.now().isAfter(entry.expiresAt())) {
                    tasksCompleted.incrementAndGet();
                    CompletedTaskExecution cached = entry.execution();
                    HarnessEvent completedEvent = new HarnessEvent.TaskCompleted(
                        Instant.now(), task.taskId(), cached.task().status(),
                        cached.durationMs(), cached.modelCalls()
                    );
                    emit.accept(completedEvent);
                    trajectoryLogger.endTrajectory(task.taskId(), cached);
                    metricsCollector.recordLatency(Duration.ofMillis(cached.durationMs()));
                    return cached;
                } else if (entry != null) {
                    // Expired entry — remove eagerly so it doesn't persist until next eviction
                    cacheStore.remove(cacheKey);
                }
            }
        }

        // 3. Safety audit (pre)
        SafetyAuditor.SafetyReport preSafety = safetyAuditor.auditPreExecution(task, text);
        if (!preSafety.safe()) {
            tasksFailed.incrementAndGet();
            metricsCollector.recordError();
            return failTask(task, "Pre-execution safety audit failed", start);
        }

        // 4. Checkpoint: after safety audit
        saveCheckpoint(task, route, ExecutionStage.INSPECTED, emit);

        // 5. Budget check — enforce session-level token budget before running the task.
        // Uses cumulative input+output tokens from all prior tasks in this engine session.
        if (budgetEnforcer != null) {
            try {
                long usedTokens = sessionInputTokens.get() + sessionOutputTokens.get();
                budgetEnforcer.checkBudget(java.math.BigDecimal.valueOf(usedTokens));
            } catch (RuntimeException e) {
                tasksFailed.incrementAndGet();
                metricsCollector.recordError();
                return failTask(task, "Budget exceeded: " + e.getMessage(), start);
            }
        }

        // 6. HITL gate — human approval required before executing workers on high-risk paths.
        // Emit ApprovalRequired with a deterministic gateId BEFORE blocking, so the approver
        // can correlate the event to the gate via args["gateId"].
        if (hitlGate != null && mode == ExecutionMode.BUILD && requiresHitlApproval(route)) {
            String gateId = "hitl-" + task.taskId();
            HarnessEvent approvalEvent = new HarnessEvent.ApprovalRequired(
                Instant.now(), task.taskId(), route.path().name(),
                Map.of(
                    "gateId", gateId,
                    "kind", route.kind().name(),
                    "lane", route.lane().name(),
                    "canParallelize", String.valueOf(route.canParallelize())
                )
            );
            emit.accept(approvalEvent);
            trajectoryLogger.logEvent(task.taskId(), approvalEvent);

            var approvalResult = hitlGate.requestApprovalForGate(
                gateId, task.taskId(), route.path().name(),
                Map.of("kind", route.kind().name(), "canParallelize", route.canParallelize()),
                null
            );

            if (approvalResult.isErr()
                || approvalResult.unwrap() == HitlGate.HitlDecision.REJECT) {
                tasksFailed.incrementAndGet();
                metricsCollector.recordError();
                String reason = approvalResult.isErr()
                    ? "HITL approval failed: " + approvalResult.unwrapErr().message()
                    : "Task rejected by HITL gate";
                return failTask(task, reason, start);
            }
        }

        // 7. Start trajectory
        trajectoryLogger.startTrajectory(task.taskId(), task);

        // 9. Worker pool
        WorkerPool pool = new WorkerPool();
        pool.setTaskId(task.taskId());

        List<WorkerAssignment> assignments = prepared.workerAssignments();
        if (!assignments.isEmpty()) {
            pool.register(assignments);
            HarnessEvent workersEvent = new HarnessEvent.WorkersAssigned(
                Instant.now(), task.taskId(), assignments);
            emit.accept(workersEvent);
            trajectoryLogger.logEvent(task.taskId(), workersEvent);

            // 10. Checkpoint: after worker assignment
            saveCheckpoint(task, route, ExecutionStage.PLANNED, emit);

            // 11. Execute workers in parallel via StructuredTaskScope
            AtomicInteger modelCalls = new AtomicInteger();
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {
                for (WorkerAssignment wa : assignments) {
                    scope.fork(() -> {
                        executeWorker(wa, prepared, pool, modelCalls, emit);
                        return null;
                    });
                }
            } catch (Exception e) {
                HarnessEvent errorEvent = new HarnessEvent.ErrorOccurred(
                    Instant.now(), task.taskId(), "WorkerExecution",
                    e.getMessage(), null);
                emit.accept(errorEvent);
                trajectoryLogger.logEvent(task.taskId(), errorEvent);
            }
            totalModelCalls.addAndGet(modelCalls.get());

            // 12. Checkpoint: after worker execution
            saveCheckpoint(task, route, ExecutionStage.EDITED, emit);
        }

        // 13. Run the agent loop for the orchestrator
        OrchestratorResult orchestratorResult = runOrchestratorAgent(prepared, task.taskId(), basePrompt);
        String responseText = orchestratorResult.responseText();
        sessionInputTokens.addAndGet(orchestratorResult.inputTokens());
        sessionOutputTokens.addAndGet(orchestratorResult.outputTokens());

        // 12. Verify
        int toolCalls = 0;
        Verifier.VerifyInput verifyInput = new Verifier.VerifyInput(
            task, responseText, toolCalls, false,
            Duration.between(start, Instant.now()).toMillis(),
            totalModelCalls.intValue()
        );
        CompletedTaskExecution completed = verifier.verify(verifyInput);

        HarnessEvent verifiedEvent = new HarnessEvent.TaskVerified(
            Instant.now(), task.taskId(),
            completed.verification().ok(), completed.verification().findings()
        );
        emit.accept(verifiedEvent);
        trajectoryLogger.logEvent(task.taskId(), verifiedEvent);

        if (!completed.verification().ok()) {
            metricsCollector.recordError();
        }

        // 15. Checkpoint: after verification
        saveCheckpoint(task, route, ExecutionStage.VERIFIED, emit);

        // 14. Safety audit (post)
        SafetyAuditor.SafetyReport postSafety = safetyAuditor.auditPostExecution(
            completed.task(), responseText, toolCalls, pool.snapshotResults()
        );

        // 15. Record completion
        if (completed.verification().ok() && postSafety.safe()) {
            tasksCompleted.incrementAndGet();
            projectMemory.recordCompletedTask(task.taskId(), route.kind(),
                "Completed: " + route.kind().name());

            if (hierarchicalMemoryManager != null) {
                storeInHierarchicalMemory(task, route, responseText, true);
            }
        } else {
            tasksFailed.incrementAndGet();
            metricsCollector.recordError();
        }

        // 16. End trajectory
        trajectoryLogger.endTrajectory(task.taskId(), completed);

        HarnessEvent completedEvent = new HarnessEvent.TaskCompleted(
            Instant.now(), task.taskId(), completed.task().status(),
            completed.durationMs(), completed.modelCalls()
        );
        emit.accept(completedEvent);
        trajectoryLogger.logEvent(task.taskId(), completedEvent);

        // 17. Publish telemetry
        int taskInputTokens = orchestratorResult.inputTokens();
        int taskOutputTokens = orchestratorResult.outputTokens();
        metricsCollector.recordLatency(Duration.ofMillis(completed.durationMs()));
        metricsCollector.recordTokens(taskInputTokens + taskOutputTokens);
        publishChorusEvent(new AgentEndEvent(task.taskId(), "harness-engine",
            new TokenCount(taskInputTokens, taskOutputTokens, "harness"),
            Duration.ofMillis(completed.durationMs()), Instant.now()));

        // 18. Store run record
        HarnessRunRecord record = new HarnessRunRecord(
            completed.task(), route, prepared.protocol(),
            prepared.repoIntelligence(), prepared.projectMemory(),
            prepared.contextBundle(), assignments,
            pool.snapshotResults(), completed
        );
        runHistory.put(task.taskId(), record);

        // 19. Cache result if applicable
        if (config.enableResultCache() && route.path() == TaskPath.CACHE_AMPLIFIED_PATH) {
            synchronized (cacheStore) {
                cacheStore.put(cacheKey, new CacheEntry(completed,
                    Instant.now().plus(config.cacheTtl())));
                evictCacheIfNeeded();
            }
        }

        // 22. Finalize checkpoint
        saveCheckpoint(task, route, ExecutionStage.FINALIZED, emit);

        return completed;
    }

    private void executeWorker(
        WorkerAssignment assignment,
        TaskOrchestrator.PreparedTaskExecution prepared,
        WorkerPool pool,
        AtomicInteger modelCalls,
        Consumer<HarnessEvent> emit
    ) {
        pool.markRunning(assignment.workerId());
        HarnessEvent startedEvent = new HarnessEvent.WorkerStarted(
            Instant.now(), prepared.task().taskId(), assignment.workerId(), assignment.role()
        );
        emit.accept(startedEvent);
        trajectoryLogger.logEvent(prepared.task().taskId(), startedEvent);

        try {
            String workerPrompt = buildWorkerPrompt(assignment.role(), prepared);
            CancellationToken token = CancellationToken.create();

            List<AgentEvent> events = collectAgentEvents(
                prepared.task().taskId() + "-" + assignment.workerId(),
                workerPrompt, token
            );

            String result = events.stream()
                .filter(e -> e instanceof AgentEvent.Done)
                .map(e -> ((AgentEvent.Done) e).finalAnswer())
                .reduce((a, b) -> b)
                .orElse("");

            TokenCounts workerTokens = extractTokenCounts(events);
            sessionInputTokens.addAndGet(workerTokens.inputTokens());
            sessionOutputTokens.addAndGet(workerTokens.outputTokens());
            modelCalls.incrementAndGet();
            metricsCollector.recordToolCall();
            metricsCollector.recordTokens(workerTokens.inputTokens() + workerTokens.outputTokens());

            pool.complete(
                assignment.workerId(),
                result,
                List.of(),
                List.of("Worker " + assignment.role() + " completed"),
                List.of(),
                List.of(),
                new WorkerResult.VerificationSummary(List.of("executed"), List.of())
            );

            WorkerResult workerResult = pool.snapshotResults().stream()
                .filter(r -> r.workerId().equals(assignment.workerId()))
                .findFirst().orElseThrow();
            HarnessEvent completedEvent = new HarnessEvent.WorkerCompleted(
                Instant.now(), prepared.task().taskId(), assignment.workerId(), workerResult
            );
            emit.accept(completedEvent);
            trajectoryLogger.logEvent(prepared.task().taskId(), completedEvent);

            publishChorusEvent(new ToolCallEvent(
                prepared.task().taskId(), assignment.workerId(), assignment.role().name(),
                Duration.ZERO, null, Instant.now()));

        } catch (Exception e) {
            pool.fail(assignment.workerId(), "Worker failed: " + e.getMessage(),
                List.of(e.getMessage()));
            HarnessEvent errorEvent = new HarnessEvent.ErrorOccurred(
                Instant.now(), prepared.task().taskId(),
                "WorkerFailure", e.getMessage(), assignment.workerId()
            );
            emit.accept(errorEvent);
            trajectoryLogger.logEvent(prepared.task().taskId(), errorEvent);
            metricsCollector.recordError();
        }
    }

    private OrchestratorResult runOrchestratorAgent(
        TaskOrchestrator.PreparedTaskExecution prepared,
        String taskId,
        String basePrompt
    ) {
        // Compact context BEFORE running the agent so the agent receives a shorter prompt.
        String prompt = maybeCompactPrompt(prepared, basePrompt, taskId);

        CancellationToken token = CancellationToken.create();
        try {
            List<AgentEvent> events = collectAgentEvents(taskId, prompt, token);

            String responseText = events.stream()
                .filter(e -> e instanceof AgentEvent.Done)
                .map(e -> ((AgentEvent.Done) e).finalAnswer())
                .reduce((a, b) -> b)
                .orElse("");
            TokenCounts tokens = extractTokenCounts(events);
            return new OrchestratorResult(responseText, tokens.inputTokens(), tokens.outputTokens());
        } catch (TimeoutException e) {
            return new OrchestratorResult(
                "ERROR: Task timed out after " + config.taskTimeout(), 0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OrchestratorResult("ERROR: Interrupted", 0, 0);
        }
    }

    private List<AgentEvent> collectAgentEvents(
        String runId, String prompt, CancellationToken token
    ) throws TimeoutException, InterruptedException {
        if (selfHealingLoop != null) {
            return FlowCollector.toList(
                selfHealingLoop.run(runId, prompt, List.of(), token),
                config.taskTimeout(), token
            );
        }
        return FlowCollector.toList(
            agentLoop.run(runId, prompt, List.of(), token),
            config.taskTimeout(), token
        );
    }

    /**
     * Compacts the orchestrator prompt BEFORE the agent runs using an LLM-backed summarizer.
     * Background context (repo facts, project memory, protocol) is summarizable; the task
     * instruction and system prompt are always preserved.
     * Returns the original prompt unchanged if no compaction is needed or available.
     */
    private String maybeCompactPrompt(
        TaskOrchestrator.PreparedTaskExecution prepared,
        String basePrompt,
        String taskId
    ) {
        if (contextCompactor == null) return prepared.runtimePrompt();

        List<Message> history = buildCompactionHistory(prepared, basePrompt);

        ContextCompactor.Summarizer llmSummarizer = messages -> {
            String toSummarize = messages.stream()
                .map(m -> "[" + m.role().name() + "]\n" + m.content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
            ChatRequest req = ChatRequest.builder()
                .model(config.compactionModel())
                .messages(List.of(
                    Message.system("Summarize the following agent context concisely, "
                        + "preserving all critical technical details, decisions, and requirements."),
                    Message.user(toSummarize)
                ))
                .temperature(0.0)
                .maxTokens(1000)
                .build();
            try {
                return llmClient.complete(req, CancellationToken.create()).message().content();
            } catch (Exception e) {
                return "Context summary unavailable: " + e.getMessage();
            }
        };

        var result = contextCompactor.summarize(history, llmSummarizer);

        if (result.messages().size() < history.size()) {
            publishChorusEvent(new CheckpointEvent(taskId, "compact-" + taskId,
                "context-compactor", Instant.now()));
            return result.messages().stream()
                .map(Message::content)
                .filter(c -> !c.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        }
        return prepared.runtimePrompt();
    }

    private List<Message> buildCompactionHistory(
        TaskOrchestrator.PreparedTaskExecution prepared,
        String basePrompt
    ) {
        List<Message> history = new ArrayList<>();
        history.add(Message.system(basePrompt));

        // Repo intelligence — background context, compactable
        RepoIntelligence repo = prepared.repoIntelligence();
        if (repo != null && !repo.summary().isBlank()) {
            history.add(Message.assistant(
                "REPO CONTEXT: languages=" + String.join(",", repo.languages())
                + " | summary=" + repo.summary()
                + " | tests=" + String.join(",", repo.testSignals())
            ));
        }

        // Project memory — background context, compactable
        ProjectMemory mem = prepared.projectMemory();
        if (mem != null && (!mem.decisions().isEmpty() || !mem.knownIssues().isEmpty())) {
            String decisions = String.join("; ", mem.decisions());
            String issues = String.join("; ", mem.knownIssues());
            history.add(Message.assistant(
                "PROJECT MEMORY: decisions=[" + decisions + "] issues=[" + issues + "]"
            ));
        }

        // Protocol — background context, compactable
        ExecutionProtocol proto = prepared.protocol();
        history.add(Message.assistant(
            "PROTOCOL: stages=" + proto.stages()
            + " delegation=" + proto.delegationPolicy()
            + " checks=" + proto.suggestedChecks()
        ));

        // Worker assignments — current-run context, preserved as recent
        if (!prepared.workerAssignments().isEmpty()) {
            String workers = prepared.workerAssignments().stream()
                .map(w -> w.role().name() + "[" + w.status().name() + "]")
                .collect(java.util.stream.Collectors.joining(","));
            history.add(Message.assistant("WORKERS: " + workers));
        }

        // Task instruction — always last, always preserved (ContextCompactor keeps last 2)
        TaskRecord t = prepared.task();
        String criteria = t.verificationCriteria().stream()
            .map(VerificationCriterion::description)
            .collect(java.util.stream.Collectors.joining("; "));
        history.add(Message.user(
            "TASK: kind=" + prepared.route().kind().name()
            + " path=" + t.path().name()
            + (criteria.isEmpty() ? "" : " | criteria=" + criteria)
        ));

        return history;
    }

    private void saveCheckpoint(
        TaskRecord task, TaskRoute route, ExecutionStage stage, Consumer<HarnessEvent> emit
    ) {
        if (!config.enableTimeTravel() || checkpointer == null) return;

        long seq = sequenceCounter.incrementAndGet();
        try {
            var agentState = new com.chorus.engine.core.checkpoint.AgentState(
                task.taskId(),
                seq,
                List.of(),
                Map.of(
                    "stage", stage.name(),
                    "timestamp", Instant.now().toString(),
                    "taskKind", route.kind().name(),
                    "taskPath", task.path().name(),
                    "taskLane", task.lane().name(),
                    "taskStatus", task.status().name()
                ),
                Map.of()
            );
            checkpointer.save(task.taskId(), seq, agentState);

            HarnessEvent checkpointEvent = new HarnessEvent.CheckpointSaved(
                Instant.now(), task.taskId(), "ckpt-" + seq + "-" + stage.name(), stage);
            emit.accept(checkpointEvent);
            trajectoryLogger.logEvent(task.taskId(), checkpointEvent);

            publishChorusEvent(new CheckpointEvent(task.taskId(),
                "ckpt-" + seq + "-" + stage.name(),
                "checkpointer", Instant.now()));
        } catch (Exception ignored) {
            // Checkpoint save failure should not crash execution
        }
    }

    // Must be called with cacheStore lock held.
    private void evictCacheIfNeeded() {
        Instant now = Instant.now();
        cacheStore.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        int maxEntries = config.maxCacheEntries();
        while (cacheStore.size() > maxEntries) {
            // LinkedHashMap maintains insertion order — iterator().next() is the oldest entry.
            String oldest = cacheStore.keySet().iterator().next();
            cacheStore.remove(oldest);
        }
    }

    private void storeInHierarchicalMemory(
        TaskRecord task, TaskRoute route, String response, boolean succeeded
    ) {
        if (hierarchicalMemoryManager == null) return;
        try {
            Message msg = new Message(Role.ASSISTANT, response, null, null, null);
            hierarchicalMemoryManager.storeWorking(msg, 0);
            hierarchicalMemoryManager.recordEpisode(
                msg, route.kind().name(), null, succeeded ? "success" : "failure");
        } catch (Exception ignored) {
            // Memory store failure should not crash execution
        }
    }

    private void publishChorusEvent(ChorusEvent event) {
        try {
            eventBus.publish(event);
        } catch (Exception ignored) {
            // Event bus failure should not crash execution
        }
    }

    private record CacheEntry(
        @NonNull CompletedTaskExecution execution,
        @NonNull Instant expiresAt
    ) {}

    private record TokenCounts(int inputTokens, int outputTokens) {}

    private record OrchestratorResult(
        @NonNull String responseText,
        int inputTokens,
        int outputTokens
    ) {}

    private static String cacheKeyFor(String basePrompt, String text) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                (basePrompt + "|" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString((basePrompt + text).hashCode());
        }
    }

    private static TokenCounts extractTokenCounts(List<AgentEvent> events) {
        int input = 0, output = 0;
        for (AgentEvent event : events) {
            if (event instanceof AgentEvent.Done done) {
                input += done.totalInputTokens();
                output += done.totalOutputTokens();
            }
        }
        return new TokenCounts(input, output);
    }

    private CompletedTaskExecution failTask(
        TaskRecord task, String reason, Instant start
    ) {
        TaskRecord failed = new TaskRecord(
            task.taskId(), task.owner(), task.lane(), task.path(),
            TaskStatus.FAILED, task.createdAt(), Instant.now(), task.verificationCriteria()
        );
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        // Close the AgentStartEvent span that was published before safety/budget checks
        // so telemetry consumers always see paired start+end events.
        publishChorusEvent(new AgentEndEvent(task.taskId(), "harness-engine",
            new TokenCount(0, 0, "harness"),
            Duration.ofMillis(durationMs), Instant.now()));
        return new CompletedTaskExecution(
            failed, new VerificationResult(false, List.of(reason)), 0, durationMs
        );
    }

    private String buildWorkerPrompt(
        WorkerRole role,
        TaskOrchestrator.PreparedTaskExecution prepared
    ) {
        return "You are a " + role.name().toLowerCase(Locale.ROOT) + ".\n\n"
            + prepared.runtimePrompt();
    }

    // Metrics & history

    public HarnessMetrics metrics() {
        MetricsCollector.Snapshot snapshot = metricsCollector.snapshot();
        Map<String, Long> routes = new ConcurrentHashMap<>();
        Map<String, Long> lanes = new ConcurrentHashMap<>();
        for (var entry : runHistory.entrySet()) {
            var record = entry.getValue();
            routes.merge(record.route().kind().name(), 1L, Long::sum);
            lanes.merge(record.route().lane().name(), 1L, Long::sum);
        }
        return new HarnessMetrics(
            tasksStarted.get(), tasksCompleted.get(), tasksFailed.get(),
            totalModelCalls.get(),
            tasksFailed.get(),
            runHistory.size(),
            Map.copyOf(routes),
            Map.copyOf(lanes),
            snapshot.totalRuns() > 0 ? snapshot.latencyP50() * snapshot.totalRuns() : 0,
            Instant.now()
        );
    }

    public Map<String, HarnessRunRecord> runHistory() {
        return Map.copyOf(runHistory);
    }

    public ApprovalLog approvalLog() {
        return approvalLog;
    }

    public ProjectMemoryStore projectMemory() {
        return projectMemory;
    }

    public TrajectoryLogger trajectoryLogger() {
        return trajectoryLogger;
    }

    /**
     * Immutable snapshot of what was saved at a specific checkpoint stage.
     * Suitable for audit queries. Use {@link #listCheckpoints} to find sequence numbers.
     */
    public record CheckpointSnapshot(
        @NonNull String taskId,
        long sequenceNumber,
        @NonNull ExecutionStage stage,
        @NonNull Instant timestamp,
        @NonNull Map<String, Object> metadata
    ) {}

    /**
     * Load a raw checkpoint snapshot for audit purposes.
     * Returns null if the checkpoint does not exist or cannot be read.
     */
    public @Nullable CheckpointSnapshot loadCheckpointSnapshot(
        @NonNull String taskId, long sequenceNumber
    ) {
        if (checkpointer == null) return null;
        try {
            var result = checkpointer.load(taskId, sequenceNumber);
            if (result.isErr()) return null;
            var state = result.unwrap();
            String stageName = (String) state.metadata().get("stage");
            String tsStr = (String) state.metadata().get("timestamp");
            if (stageName == null) return null;
            ExecutionStage stage = ExecutionStage.valueOf(stageName);
            Instant ts = tsStr != null ? Instant.parse(tsStr) : Instant.now();
            return new CheckpointSnapshot(taskId, sequenceNumber, stage, ts,
                Map.copyOf(state.metadata()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Load the completed run record associated with a FINALIZED checkpoint.
     * Returns null for mid-execution (non-FINALIZED) checkpoints — use
     * {@link #loadCheckpointSnapshot} for audit queries at intermediate stages.
     */
    public @Nullable HarnessRunRecord loadFromCheckpoint(
        @NonNull String taskId, long sequenceNumber
    ) {
        CheckpointSnapshot snapshot = loadCheckpointSnapshot(taskId, sequenceNumber);
        if (snapshot == null) return null;
        if (snapshot.stage() != ExecutionStage.FINALIZED) return null;
        return runHistory.get(taskId);
    }

    /**
     * List all checkpoints for a given task.
     */
    public @NonNull List<Checkpointer.CheckpointRef> listCheckpoints(@NonNull String taskId) {
        if (checkpointer == null) return List.of();
        try {
            var result = checkpointer.list(taskId);
            return result.isOk() ? result.unwrap() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Clear the result cache.
     */
    public void clearCache() {
        cacheStore.clear();
    }

    /**
     * Closes only engine-owned resources (the SelfHealingAgentLoop, when created).
     * Injected resources (HitlGate, HierarchicalMemoryManager) are owned by the caller;
     * call {@link #closeAll()} to also shut those down explicitly.
     */
    @Override
    public void close() {
        if (selfHealingLoop != null) {
            selfHealingLoop.close();
        }
    }

    /**
     * Closes all resources including injected ones. Use this when the engine is the sole
     * user of the provided HitlGate and HierarchicalMemoryManager and you want a single
     * shutdown call.
     * <p>Do NOT use if the HitlGate or HierarchicalMemoryManager is shared across multiple
     * engine instances — closing them here would affect the other instances.
     */
    public void closeAll() {
        close();
        if (hitlGate != null && !hitlGate.isDisposed()) {
            hitlGate.dispose();
        }
        if (hierarchicalMemoryManager != null) {
            hierarchicalMemoryManager.close();
        }
    }

    private static boolean requiresHitlApproval(TaskRoute route) {
        return switch (route.path()) {
            case PARALLEL_MULTI_WORKER_PATH, BACKGROUND_OR_BATCH_PATH -> true;
            case TOOL_OR_SINGLE_WORKER_PATH ->
                route.kind() == TaskKind.SINGLE_FILE_EDIT
                    || route.kind() == TaskKind.MULTI_FILE_EDIT;
            default -> false;
        };
    }
}
