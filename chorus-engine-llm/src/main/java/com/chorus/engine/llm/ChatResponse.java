package com.chorus.engine.llm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable LLM chat completion response.
 */
public record ChatResponse(
    @NonNull String id,
    @NonNull String model,
    @NonNull String provider,
    @NonNull Message message,
    @NonNull TokenCount tokenCount,
    @NonNull Duration latency,
    @Nullable String finishReason,
    @Nullable List<ToolCall> toolCalls,
    @Nullable String reasoningContent,
    @NonNull Map<String, Object> rawMetadata
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public record ToolCall(
        @NonNull String id,
        @NonNull String toolName,
        @NonNull Map<String, Object> arguments
    ) {}
}
