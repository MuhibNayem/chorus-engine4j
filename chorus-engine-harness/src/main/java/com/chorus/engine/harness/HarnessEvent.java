package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Events emitted by the harness during task execution.
 * Integrates with the telemetry module's event bus for observability.
 */
public sealed interface HarnessEvent {

    @NonNull Instant timestamp();
    @NonNull String taskId();

    record TaskClassified(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        @NonNull TaskRoute route,
        double confidence,
        SemanticTaskRouter.RoutingMethod method
    ) implements HarnessEvent {}

    record WorkersAssigned(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        List<WorkerAssignment> assignments
    ) implements HarnessEvent {}

    record WorkerStarted(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        @NonNull String workerId,
        @NonNull WorkerRole role
    ) implements HarnessEvent {}

    record WorkerCompleted(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        @NonNull String workerId,
        @NonNull WorkerResult result
    ) implements HarnessEvent {}

    record TaskVerified(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        boolean passed,
        List<String> findings
    ) implements HarnessEvent {}

    record TaskCompleted(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        TaskStatus finalStatus,
        long durationMs,
        int modelCalls
    ) implements HarnessEvent {}

    record CheckpointSaved(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        @NonNull String checkpointId,
        @NonNull ExecutionStage stage
    ) implements HarnessEvent {}

    record ApprovalRequired(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        @NonNull String tool,
        Map<String, Object> args
    ) implements HarnessEvent {}

    record ErrorOccurred(
        @NonNull Instant timestamp,
        @NonNull String taskId,
        @NonNull String errorType,
        @NonNull String message,
        @Nullable String workerId
    ) implements HarnessEvent {}
}
