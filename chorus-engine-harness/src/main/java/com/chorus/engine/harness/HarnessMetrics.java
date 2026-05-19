package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Map;

/**
 * Aggregated metrics across all harness runs.
 */
public record HarnessMetrics(
    long tasksStarted,
    long tasksCompleted,
    long tasksFailed,
    long modelCalls,
    long verifierFailures,
    long workerAssignments,
    @NonNull Map<String, Long> routes,
    @NonNull Map<String, Long> lanes,
    long totalDurationMs,
    @NonNull Instant updatedAt
) {}
