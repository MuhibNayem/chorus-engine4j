package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Full record of a harness run — task, routing, workers, and completion.
 */
public record HarnessRunRecord(
    @NonNull TaskRecord task,
    @NonNull TaskRoute route,
    @Nullable ExecutionProtocol protocol,
    @Nullable RepoIntelligence repoIntelligence,
    @Nullable ProjectMemory projectMemory,
    @NonNull ContextBundle contextBundle,
    @NonNull List<WorkerAssignment> workerAssignments,
    @NonNull List<WorkerResult> workerResults,
    @Nullable CompletedTaskExecution completed
) {}
