package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates task preparation — routing, worker assignment, protocol building,
 * context assembly, and runtime prompt generation.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #prepareSync} — synchronous, uses regex routing</li>
 *   <li>{@link #prepare} — uses semantic routing with regex fallback</li>
 * </ul>
 */
public final class TaskOrchestrator {

    private final TaskRouter fallbackRouter;
    private final SemanticTaskRouter semanticRouter;
    private final RepoIntelligence repoIntelligence;
    private final ProjectMemoryStore projectMemoryStore;

    public TaskOrchestrator(
        @Nullable SemanticTaskRouter semanticRouter,
        @NonNull RepoIntelligence repoIntelligence,
        @NonNull ProjectMemoryStore projectMemoryStore
    ) {
        this.fallbackRouter = new TaskRouter();
        this.semanticRouter = semanticRouter;
        this.repoIntelligence = Objects.requireNonNull(repoIntelligence, "repoIntelligence");
        this.projectMemoryStore = Objects.requireNonNull(projectMemoryStore, "projectMemoryStore");
    }

    /**
     * Synchronous preparation using regex-based routing.
     */
    public @NonNull PreparedTaskExecution prepareSync(@NonNull PrepareInput input) {
        TaskRoute route = fallbackRouter.route(input.text, input.expandedText);
        return buildPreparedExecution(input, route);
    }

    /**
     * Asynchronous preparation using semantic routing with regex fallback.
     * Preferred for production use.
     */
    public @NonNull PreparedTaskExecution prepare(@NonNull PrepareInput input) {
        if (semanticRouter != null) {
            SemanticTaskRouter.SemanticRouteResult semantic = semanticRouter.route(
                input.text, input.expandedText);
            TaskRoute route = new TaskRoute(
                semantic.kind(), semantic.lane(), semantic.path(),
                semantic.requiresResearch(), semantic.canParallelize(), semantic.usesCheapTriage()
            );
            return buildPreparedExecution(input, route);
        }
        return prepareSync(input);
    }

    private @NonNull PreparedTaskExecution buildPreparedExecution(
        @NonNull PrepareInput input,
        @NonNull TaskRoute route
    ) {
        ExecutionMode mode = input.mode != null ? input.mode : ExecutionMode.BUILD;
        ProjectMemory projectMemory = projectMemoryStore.get();
        ExecutionProtocol protocol = ExecutionProtocolBuilder.build(route, repoIntelligence, mode);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();

        TaskRecord task = new TaskRecord(
            taskId,
            WorkerRole.ORCHESTRATOR,
            route.lane(),
            route.path(),
            TaskStatus.RUNNING,
            now,
            now,
            fallbackRouter.buildCriteria(route, mode, input.isAgentInvocation)
        );

        List<WorkerAssignment> workerAssignments = mode == ExecutionMode.PLAN
            ? List.of()
            : createWorkerAssignments(taskId, route, mode);

        List<String> toolNames = input.toolNames != null ? input.toolNames : List.of();
        List<String> subagentNames = input.subagentNames != null ? input.subagentNames : List.of();

        ContextBundle contextBundle = ContextAssembler.createContextBundle(
            input.basePrompt,
            task,
            input.messages != null ? input.messages : List.of(),
            toolNames,
            subagentNames,
            workerAssignments,
            repoIntelligence,
            projectMemory
        );

        String runtimePrompt = ContextAssembler.buildRuntimePrompt(
            input.basePrompt,
            task,
            route.lane() + " / " + route.path(),
            contextBundle,
            workerAssignments,
            protocol,
            repoIntelligence,
            projectMemory
        );

        return new PreparedTaskExecution(
            mode, task, route, protocol, repoIntelligence,
            projectMemory, contextBundle, workerAssignments, runtimePrompt
        );
    }

    private @NonNull List<WorkerAssignment> createWorkerAssignments(
        @NonNull String taskId,
        @NonNull TaskRoute route,
        @NonNull ExecutionMode mode
    ) {
        if (route.path() == TaskPath.DIRECT_AGENT_PATH) {
            return List.of();
        }

        List<WorkerRole> roles = new ArrayList<>();
        if (route.requiresResearch()) {
            roles.add(WorkerRole.RESEARCHER);
            roles.add(WorkerRole.PLANNER);
            roles.add(WorkerRole.REVIEWER);
        } else if (route.lane() == ExecutionLane.BACKGROUND_ASYNC) {
            roles.add(WorkerRole.PLANNER);
            roles.add(WorkerRole.REVIEWER);
            roles.add(WorkerRole.TESTER);
        } else if (route.canParallelize()) {
            roles.add(WorkerRole.PLANNER);
            roles.add(WorkerRole.CODER);
            roles.add(WorkerRole.REVIEWER);
            roles.add(WorkerRole.TESTER);
        } else {
            roles.add(WorkerRole.ORCHESTRATOR);
        }

        List<WorkerAssignment> assignments = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            WorkerRole role = roles.get(i);
            List<String> scope = switch (role) {
                case CODER -> List.of("workspace");
                case REVIEWER -> List.of("changed-surface");
                case TESTER -> List.of("verification-surface");
                case ADVISOR -> List.of("plan-review");
                default -> List.of();
            };
            assignments.add(new WorkerAssignment(
                taskId + "-" + role.name().toLowerCase() + "-" + i,
                role, scope, "ctx-" + taskId, TaskStatus.QUEUED
            ));
        }
        return List.copyOf(assignments);
    }

    // ------------------------------------------------------------------

    public record PrepareInput(
        @NonNull String text,
        @NonNull String expandedText,
        @NonNull String basePrompt,
        @Nullable List<? extends com.chorus.engine.core.context.Message> messages,
        @Nullable ExecutionMode mode,
        boolean isAgentInvocation,
        @Nullable List<String> toolNames,
        @Nullable List<String> subagentNames
    ) {
        public PrepareInput(@NonNull String text, @NonNull String expandedText, @NonNull String basePrompt) {
            this(text, expandedText, basePrompt, null, null, false, null, null);
        }
    }

    public record PreparedTaskExecution(
        @NonNull ExecutionMode mode,
        @NonNull TaskRecord task,
        @NonNull TaskRoute route,
        @NonNull ExecutionProtocol protocol,
        @NonNull RepoIntelligence repoIntelligence,
        @NonNull ProjectMemory projectMemory,
        @NonNull ContextBundle contextBundle,
        @NonNull List<WorkerAssignment> workerAssignments,
        @NonNull String runtimePrompt
    ) {}
}
