package com.chorus.engine.core.middleware;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.tool.AgentTool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Middleware hooks for the agent loop.
 * Mirrors Spring AI's Advisor pattern but with chorus-specific lifecycle hooks.
 */
public interface AgentMiddleware {

    default int priority() { return 0; }

    default CompletableFuture<Void> beforeRound(RoundContext ctx) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> afterRound(RoundContext ctx) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Called before a tool executes. Return a non-null result to cancel execution
     * and inject a synthetic result into the conversation.
     */
    default CompletableFuture<ToolDirective> beforeTool(BeforeToolContext ctx) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<String> afterTool(ToolResultContext ctx) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Called before each round. Return a non-null result to compact history.
     * First matching middleware wins.
     */
    default CompletableFuture<CompactResult> maybeCompact(List<ChatMessage> history, CompactOptions opts) {
        return CompletableFuture.completedFuture(null);
    }

    default List<AgentTool> extraTools() {
        return List.of();
    }

    default String extraSystemPrompt() {
        return null;
    }

    default void setTools(Map<String, AgentTool> toolsByName) {}

    record RoundContext(int round, String threadId, String model, List<ChatMessage> history, int toolCallsThisRound) {}
    record BeforeToolContext(String id, String name, Map<String, Object> args) {}
    record ToolResultContext(String id, String name, String result, long durationMs) {}
    record ToolDirective(boolean cancel, String result) {}
    record CompactResult(List<ChatMessage> replacement, int removedMessages, int savedTokens) {}
    record CompactOptions(String model, String systemPrompt) {}
}
