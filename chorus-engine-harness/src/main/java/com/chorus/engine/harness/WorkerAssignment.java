package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Assignment of a worker to a specific task with scope and status.
 */
public record WorkerAssignment(
    @NonNull String workerId,
    @NonNull WorkerRole role,
    @NonNull List<String> ownedScope,
    @NonNull String inputBundleId,
    @NonNull TaskStatus status
) {}
