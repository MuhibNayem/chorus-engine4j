package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a checkpoint is saved or loaded.
 */
public record CheckpointEvent(
    @NonNull String runId,
    @NonNull String checkpointId,
    @NonNull String storageType,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public CheckpointEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(checkpointId, "checkpointId cannot be null");
        Objects.requireNonNull(storageType, "storageType cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }

    @Override
    public @NonNull String eventType() {
        return "checkpoint";
    }
}
