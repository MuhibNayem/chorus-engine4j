package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

/**
 * A completed task execution with verification and metrics.
 */
public record CompletedTaskExecution(
    @NonNull TaskRecord task,
    @NonNull VerificationResult verification,
    int modelCalls,
    long durationMs
) {}
