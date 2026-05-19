package com.chorus.engine.llm;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Unified streaming event from any LLM provider.
 * Normalizes OpenAI delta, Anthropic content_block_delta, Gemini candidates,
 * and others into a single immutable record.
 */
public sealed interface StreamEvent {

    record Token(
        @NonNull String token,
        int index,
        @Nullable String reasoningContent
    ) implements StreamEvent {}

    record ToolCallStart(
        @NonNull String toolCallId,
        @NonNull String toolName,
        @NonNull Map<String, Object> partialArguments
    ) implements StreamEvent {}

    record ToolCallDelta(
        @NonNull String toolCallId,
        @Nullable String toolName,
        @Nullable String argumentDelta
    ) implements StreamEvent {}

    record ToolCallDone(
        @NonNull String toolCallId,
        @NonNull String toolName,
        @NonNull Map<String, Object> finalArguments
    ) implements StreamEvent {}

    record Finish(
        @Nullable String finishReason,
        int promptTokens,
        int completionTokens
    ) implements StreamEvent {}

    record Error(
        @NonNull String errorType,
        @NonNull String errorMessage,
        boolean fatal,
        @Nullable Integer httpStatus
    ) implements StreamEvent {}
}
