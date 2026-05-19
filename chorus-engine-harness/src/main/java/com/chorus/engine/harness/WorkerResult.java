package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Result produced by a worker after completing its assignment.
 */
public record WorkerResult(
    @NonNull String workerId,
    @NonNull TaskStatus status,
    @NonNull String summary,
    @NonNull List<String> changedFiles,
    @NonNull List<String> findings,
    @NonNull List<String> risks,
    @NonNull List<String> nextActions,
    @NonNull VerificationSummary verification
) {
    public record VerificationSummary(
        @NonNull List<String> checksRun,
        @NonNull List<String> checksPending
    ) {}
}
