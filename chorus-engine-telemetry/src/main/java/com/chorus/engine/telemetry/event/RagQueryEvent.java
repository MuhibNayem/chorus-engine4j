package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a RAG retrieval is performed.
 */
public record RagQueryEvent(
    @NonNull String runId,
    @NonNull String query,
    int chunksRetrieved,
    int contextTokens,
    @NonNull Duration latency,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public RagQueryEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(latency, "latency cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (chunksRetrieved < 0) throw new IllegalArgumentException("chunksRetrieved must be >= 0");
        if (contextTokens < 0) throw new IllegalArgumentException("contextTokens must be >= 0");
    }

    @Override
    public @NonNull String eventType() {
        return "rag.query";
    }
}
