package com.chorus.engine.core.event;

import java.util.List;
import java.util.Map;

/**
 * Sealed interface representing all possible events emitted by the agent loop.
 * Enables exhaustive pattern matching with Java 25 switch expressions.
 */
public sealed interface AgentEvent permits
    AgentEvent.TokenEvent,
    AgentEvent.ThinkingEvent,
    AgentEvent.ToolStartEvent,
    AgentEvent.ToolDoneEvent,
    AgentEvent.ToolErrorEvent,
    AgentEvent.HitlEvent,
    AgentEvent.BtwEvent,
    AgentEvent.CheckpointEvent,
    AgentEvent.CompactedEvent,
    AgentEvent.DoneEvent,
    AgentEvent.ErrorEvent,
    AgentEvent.AbortedEvent,
    AgentEvent.RoundStartEvent,
    AgentEvent.RoundEndEvent,
    AgentEvent.GuardrailTriggeredEvent,
    AgentEvent.MemoryRecallEvent,
    AgentEvent.MemoryCompactEvent,
    AgentEvent.CheckpointSavedEvent,
    AgentEvent.CheckpointLoadedEvent,
    AgentEvent.StreamStartEvent,
    AgentEvent.StreamEndEvent,
    AgentEvent.MiddlewareBeforeEvent,
    AgentEvent.MiddlewareAfterEvent,
    AgentEvent.HandoffEvent,
    AgentEvent.TraceEvent {

    record TokenEvent(String text) implements AgentEvent {}
    record ThinkingEvent(String text) implements AgentEvent {}
    record ToolStartEvent(String id, String name, Map<String, Object> args) implements AgentEvent {}
    record ToolDoneEvent(String id, String name, String result, long durationMs) implements AgentEvent {}
    record ToolErrorEvent(String id, String name, String error, boolean willRetry) implements AgentEvent {}
    record HitlEvent(List<HitlRequest> requests, String resumeKey) implements AgentEvent {}
    record BtwEvent(String text) implements AgentEvent {}
    record CheckpointEvent(int round, String threadId) implements AgentEvent {}
    record CheckpointSavedEvent(int round, String threadId, String mode) implements AgentEvent {}
    record CheckpointLoadedEvent(int round, String threadId, boolean restored) implements AgentEvent {}
    record CompactedEvent(int removedMessages, int savedTokens) implements AgentEvent {}

    record DoneEvent(
        String response,
        String reasoning,
        int toolCount,
        List<ChatMessage> history,
        int inputTokens,
        int outputTokens,
        double costUsd,
        long durationMs
    ) implements AgentEvent {}

    record ErrorEvent(String message, boolean fatal) implements AgentEvent {}
    record AbortedEvent(String message) implements AgentEvent {}
    record RoundStartEvent(int round, String threadId, int messageCount) implements AgentEvent {}
    record RoundEndEvent(int round, String threadId, int toolCallsThisRound) implements AgentEvent {}

    record GuardrailTriggeredEvent(
        String guardrail,
        String severity,
        String action,
        String message
    ) implements AgentEvent {}

    record MemoryRecallEvent(String scope, String query, int resultsCount) implements AgentEvent {}
    record MemoryCompactEvent(String scope, int removedMessages, int factsExtracted) implements AgentEvent {}
    record StreamStartEvent(int round, String threadId, String model) implements AgentEvent {}
    record StreamEndEvent(int round, String threadId, int tokensEmitted) implements AgentEvent {}
    record MiddlewareBeforeEvent(int round, String hook) implements AgentEvent {}
    record MiddlewareAfterEvent(int round, String hook) implements AgentEvent {}

    record HandoffEvent(
        String targetAgent,
        String taskDescription,
        List<String> artifacts,
        String reasoning
    ) implements AgentEvent {}

    record TraceEvent(String traceId, String spanId, String traceparent) implements AgentEvent {}
}
