package com.chorus.engine.core.checkpoint;

import com.chorus.engine.core.context.Message;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Serializable snapshot of agent execution state.
 * Used for checkpointing and crash recovery.
 */
public record AgentState(
    @NonNull String runId,
    long roundIndex,
    @NonNull List<Message> history,
    @NonNull Map<String, Object> context,
    @NonNull Map<String, Object> metadata
) {
    public AgentState {
        history = List.copyOf(history);
        context = Map.copyOf(context);
        metadata = Map.copyOf(metadata);
    }
}
