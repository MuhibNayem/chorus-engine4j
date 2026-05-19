package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;

/**
 * A task being executed by the harness.
 */
public record TaskRecord(
    @NonNull String taskId,
    @NonNull WorkerRole owner,
    @NonNull ExecutionLane lane,
    @NonNull TaskPath path,
    @NonNull TaskStatus status,
    @NonNull Instant createdAt,
    @NonNull Instant updatedAt,
    @NonNull List<VerificationCriterion> verificationCriteria
) {}
