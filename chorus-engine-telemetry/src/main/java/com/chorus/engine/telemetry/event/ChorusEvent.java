package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.time.Instant;

/**
 * Sealed interface for all framework telemetry events.
 * Every significant moment in the agent lifecycle is emitted as an immutable event.
 */
public sealed interface ChorusEvent permits
    AgentStartEvent,
    AgentEndEvent,
    LlmCallEvent,
    ToolCallEvent,
    RagQueryEvent,
    HandoffEvent,
    GuardrailEvent,
    CheckpointEvent,
    CircuitBreakerEvent {

    @NonNull String runId();
    @NonNull Instant timestamp();
    @NonNull String eventType();
}
