package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.memory.ContextCompactor;

import java.util.List;

final class CompactionMiddleware implements Middleware {

    private final ContextCompactor compactor;
    private final int targetTokens;
    private boolean autoCompact = true;
    private int lastTokenCount = 0;

    CompactionMiddleware(int targetTokens) {
        this.compactor = new ContextCompactor(targetTokens);
        this.targetTokens = targetTokens;
    }

    @Override public int priority() { return 50; }

    @Override
    public Result<Middleware.CompactionResult, Middleware.MiddlewareError> maybeCompact(
            String runId, List<Message> history, TokenCount current, TokenCount max) {
        if (!autoCompact) return Result.ok(new CompactionResult(List.of(), ""));
        if (history.size() <= 4) return Result.ok(new CompactionResult(List.of(), ""));

        int estimated = estimateTokens(history);
        lastTokenCount = estimated;

        if (estimated > targetTokens * 2) {
            var result = compactor.summarize(history, messages -> {
                StringBuilder sb = new StringBuilder("[Conversation summary: ");
                for (Message m : messages) {
                    String prefix = switch (m.role()) {
                        case USER -> "User: ";
                        case ASSISTANT -> "Assistant: ";
                        case TOOL -> "Tool: ";
                        case SYSTEM -> "";
                    };
                    String snippet = m.content().length() > 200
                        ? m.content().substring(0, 200) + "..."
                        : m.content();
                    sb.append(prefix).append(snippet).append("\n");
                }
                return sb.append("]").toString();
            });
            return Result.ok(new CompactionResult(result.messages(), result.strategy()));
        }
        return Result.ok(new CompactionResult(List.of(), ""));
    }

    void disableAutoCompact() { autoCompact = false; }
    void enableAutoCompact()  { autoCompact = true; }
    int  lastEstimate()       { return lastTokenCount; }

    private int estimateTokens(List<Message> messages) {
        return messages.stream().mapToInt(m -> (int) Math.ceil(m.content().length() / 3.5)).sum();
    }
}
