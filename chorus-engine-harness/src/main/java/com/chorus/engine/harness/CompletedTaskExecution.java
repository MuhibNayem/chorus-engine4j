package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A completed task execution with verification, metrics, and skill-generation support.
 */
public record CompletedTaskExecution(
    @NonNull TaskRecord task,
    @NonNull VerificationResult verification,
    int modelCalls,
    long durationMs,
    int toolCallCount,
    List<ExecutionStage> stagesCompleted,
    @Nullable String verificationSummary
) {
    public CompletedTaskExecution(
        @NonNull TaskRecord task,
        @NonNull VerificationResult verification,
        int modelCalls,
        long durationMs
    ) {
        this(task, verification, modelCalls, durationMs, 0, List.of(), null);
    }
}
