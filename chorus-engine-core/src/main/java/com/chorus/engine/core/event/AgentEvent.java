package com.chorus.engine.core.event;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Sealed event hierarchy for every significant moment in an agent's lifecycle.
 * All events are immutable records. Replay the log to reconstruct state.
 */
public sealed interface AgentEvent {

    @NonNull String runId();
    @NonNull Instant timestamp();

    record StreamToken(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String token,
        long roundIndex,
        @Nullable String reasoningContent
    ) implements AgentEvent {}

    record ThinkingStart(
        @NonNull String runId,
        @NonNull Instant timestamp,
        long roundIndex
    ) implements AgentEvent {}

    record ThinkingEnd(
        @NonNull String runId,
        @NonNull Instant timestamp,
        long roundIndex
    ) implements AgentEvent {}

    record ToolCallStart(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String toolName,
        @NonNull Map<String, Object> arguments,
        long roundIndex
    ) implements AgentEvent {}

    record ToolCallDone(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String toolName,
        @Nullable Object result,
        long durationNanos,
        long roundIndex
    ) implements AgentEvent {}

    record ToolCallError(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String toolName,
        @NonNull String errorMessage,
        boolean retryable,
        long roundIndex
    ) implements AgentEvent {}

    record RoundStart(
        @NonNull String runId,
        @NonNull Instant timestamp,
        long roundIndex,
        int inputTokens,
        int maxTokens
    ) implements AgentEvent {}

    record RoundEnd(
        @NonNull String runId,
        @NonNull Instant timestamp,
        long roundIndex,
        int outputTokens,
        @Nullable String finishReason
    ) implements AgentEvent {}

    record HitlRequested(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String gateId,
        @NonNull String toolName,
        @NonNull Map<String, Object> arguments,
        long timeoutMillis
    ) implements AgentEvent {}

    record HitlResolved(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String gateId,
        @NonNull HitlDecision decision,
        @Nullable String reason
    ) implements AgentEvent {}

    record CheckpointSaved(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String checkpointKey,
        long sequenceNumber
    ) implements AgentEvent {}

    record CheckpointLoaded(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String checkpointKey,
        long sequenceNumber
    ) implements AgentEvent {}

    record CompactionTriggered(
        @NonNull String runId,
        @NonNull Instant timestamp,
        long roundIndex,
        int tokensBefore,
        int tokensAfter,
        @NonNull String strategy
    ) implements AgentEvent {}

    record GuardrailTriggered(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String guardrailName,
        int tier,
        @NonNull String triggerType,
        @Nullable String matchedContent,
        @NonNull GuardrailAction action
    ) implements AgentEvent {}

    record MemoryRecall(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull Set<String> memoryKeys,
        int totalTokensRecalled
    ) implements AgentEvent {}

    record MemoryStore(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String memoryKey,
        int tokenCount
    ) implements AgentEvent {}

    record Handoff(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String fromAgent,
        @NonNull String toAgent,
        @NonNull Map<String, Object> handoffContext
    ) implements AgentEvent {}

    record StreamStart(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String model,
        @NonNull String provider
    ) implements AgentEvent {}

    record StreamEnd(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @Nullable String finishReason,
        int totalInputTokens,
        int totalOutputTokens
    ) implements AgentEvent {}

    record Done(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String finalAnswer,
        int totalRounds,
        int totalInputTokens,
        int totalOutputTokens,
        long totalDurationMillis
    ) implements AgentEvent {}

    record Error(
        @NonNull String runId,
        @NonNull Instant timestamp,
        @NonNull String errorType,
        @NonNull String errorMessage,
        @Nullable String stackTrace,
        boolean fatal
    ) implements AgentEvent {}

    enum HitlDecision { APPROVE, APPROVE_SESSION, REJECT }
    enum GuardrailAction { BLOCK, WARN, REDACT, LOG }
}
