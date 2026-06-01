package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.checkpoint.AgentState;
import com.chorus.engine.core.checkpoint.InMemoryCheckpointer;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.result.Result;

import java.util.List;
import java.util.Map;

final class CheckpointManager {
    private final InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
    private long sequenceNumber = 0;

    void save(String runId, List<Message> history, long roundIndex) {
        AgentState state = new AgentState(runId, roundIndex, history, Map.of(), Map.of());
        checkpointer.save(runId, sequenceNumber++, state);
    }

    AgentState loadLatest(String runId) {
        var result = checkpointer.loadLatest(runId);
        return result.isOk() ? result.unwrap() : null;
    }

    List<InMemoryCheckpointer.CheckpointRef> list(String runId) {
        var result = checkpointer.list(runId);
        return result.isOk() ? result.unwrap() : List.of();
    }

    Middleware toMiddleware() {
        return new CheckpointMiddleware();
    }

    private class CheckpointMiddleware implements Middleware {
        @Override public int priority() { return 999; }

        @Override
        public Result<Void, MiddlewareError> afterRound(
                String runId, List<Message> history, String assistantOutput, Map<String, Object> context) {
            Object roundObj = context.get("roundIndex");
            long round = roundObj instanceof Number n ? n.longValue() : 0;
            save(runId, history, round);
            return new Result.Ok<>(null);
        }
    }
}
