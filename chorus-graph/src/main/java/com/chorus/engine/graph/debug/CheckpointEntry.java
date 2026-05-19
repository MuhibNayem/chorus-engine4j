package com.chorus.engine.graph.debug;

/**
 * Lightweight snapshot of a persisted checkpoint for time-travel browsing.
 */
public record CheckpointEntry(
    int round,
    long timestamp,
    String stateFingerprint,
    String threadId
) {
}
