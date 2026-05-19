package com.chorus.engine.agent.middleware;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.ToolDefinition;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Middleware hooks for the agent loop. Six interception points.
 *
 * <p>Middlewares are executed in priority order (lower = earlier).
 * Each hook can transform, block, or enrich the data flowing through it.
 */
public interface Middleware {

    int priority();

    /**
     * Called before each round starts. Can inject system prompts or modify history.
     */
    default @NonNull Result<String, MiddlewareError> beforeRound(
        @NonNull String runId,
        @NonNull List<Message> history,
        @NonNull Map<String, Object> context
    ) {
        return Result.ok("");
    }

    /**
     * Decide whether to compact the context window.
     */
    default @NonNull Result<CompactionResult, MiddlewareError> maybeCompact(
        @NonNull String runId,
        @NonNull List<Message> history,
        @NonNull TokenCount current,
        @NonNull TokenCount max
    ) {
        return Result.ok(new CompactionResult(List.of(), "")); // empty = no compaction
    }

    /**
     * Called before a tool is executed. Can cancel or substitute arguments.
     */
    default @NonNull Result<ToolDecision, MiddlewareError> beforeTool(
        @NonNull String runId,
        @NonNull String toolName,
        @NonNull Map<String, Object> arguments,
        @NonNull Map<String, Object> context
    ) {
        return Result.ok(new ToolDecision(true, arguments, null));
    }

    /**
     * Inject additional system prompt content.
     */
    default @NonNull Result<String, MiddlewareError> extraSystemPrompt(
        @NonNull String runId,
        @NonNull List<Message> history,
        @NonNull Map<String, Object> context
    ) {
        return Result.ok("");
    }

    /**
     * Inject additional tools available for this round.
     */
    default @NonNull Result<List<ToolDefinition>, MiddlewareError> extraTools(
        @NonNull String runId,
        @NonNull List<Message> history,
        @NonNull Map<String, Object> context
    ) {
        return Result.ok(List.of());
    }

    /**
     * Transform tool results before adding to history.
     */
    default @NonNull Result<Object, MiddlewareError> afterTool(
        @NonNull String runId,
        @NonNull String toolName,
        @Nullable Object result,
        @NonNull Map<String, Object> context
    ) {
        return Result.ok(result);
    }

    /**
     * Called after a round completes. Can trigger side effects.
     */
    default @NonNull Result<Void, MiddlewareError> afterRound(
        @NonNull String runId,
        @NonNull List<Message> history,
        @Nullable String assistantOutput,
        @NonNull Map<String, Object> context
    ) {
        return new Result.Ok<>(null);
    }

    record MiddlewareError(@NonNull String code, @NonNull String message, boolean fatal) {}
    record CompactionResult(@NonNull List<Message> compactedHistory, @NonNull String summary) {}
    record ToolDecision(boolean allow, @NonNull Map<String, Object> arguments, @Nullable String rejectionReason) {}
}
