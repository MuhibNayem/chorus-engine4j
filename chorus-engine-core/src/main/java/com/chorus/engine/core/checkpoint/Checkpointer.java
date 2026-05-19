package com.chorus.engine.core.checkpoint;

import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Durable state persistence interface.
 * Enables crash recovery, time travel, and long-running agent workflows.
 */
public interface Checkpointer {

    /**
     * Save a checkpoint.
     */
    @NonNull Result<Void, CheckpointError> save(@NonNull String runId, long sequenceNumber, @NonNull AgentState state);

    /**
     * Load the most recent checkpoint for a run.
     */
    @NonNull Result<AgentState, CheckpointError> loadLatest(@NonNull String runId);

    /**
     * Load a specific checkpoint by sequence number.
     */
    @NonNull Result<AgentState, CheckpointError> load(@NonNull String runId, long sequenceNumber);

    /**
     * List all checkpoints for a run, newest first.
     */
    @NonNull Result<List<CheckpointRef>, CheckpointError> list(@NonNull String runId);

    /**
     * Delete checkpoints older than the given sequence number.
     */
    @NonNull Result<Void, CheckpointError> prune(@NonNull String runId, long keepAfterSequence);

    record CheckpointRef(@NonNull String runId, long sequenceNumber, long timestamp) {}

    record CheckpointError(@NonNull String code, @NonNull String message, @Nullable Throwable cause) {
        public static @NonNull CheckpointError of(@NonNull String code, @NonNull String message) {
            return new CheckpointError(code, message, null);
        }
        public static @NonNull CheckpointError of(@NonNull String code, @NonNull String message, @NonNull Throwable cause) {
            return new CheckpointError(code, message, cause);
        }
    }
}
