package com.chorus.engine.memory.checkpoint;

import com.chorus.engine.core.checkpoint.AgentState;
import org.jspecify.annotations.NonNull;

/**
 * Strategy interface for serializing and deserializing {@link AgentState}.
 */
public interface CheckpointSerializer {

    /**
     * Serialize an {@link AgentState} into a {@link String}.
     */
    @NonNull String serialize(@NonNull AgentState state);

    /**
     * Deserialize a {@link String} back into an {@link AgentState}.
     */
    @NonNull AgentState deserialize(@NonNull String data);
}
