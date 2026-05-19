package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;

/**
 * Persistent memory about decisions, known issues, and completed tasks in a project.
 */
public record ProjectMemory(
    int version,
    @NonNull String workspace,
    @NonNull List<String> decisions,
    @NonNull List<String> knownIssues,
    @NonNull List<CompletedTaskSummary> completedTasks,
    @NonNull Instant updatedAt
) {
    public record CompletedTaskSummary(
        @NonNull String taskId,
        @NonNull TaskKind kind,
        @NonNull String summary,
        @NonNull Instant completedAt
    ) {}
}
