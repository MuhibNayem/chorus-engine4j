package com.chorus.engine.memory.checkpoint;

import com.chorus.engine.core.checkpoint.AgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Jackson-based JSON serializer for {@link AgentState}.
 */
public final class JsonCheckpointSerializer implements CheckpointSerializer {

    private final ObjectMapper objectMapper;

    public JsonCheckpointSerializer() {
        this(new ObjectMapper());
    }

    public JsonCheckpointSerializer(@NonNull ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull String serialize(@NonNull AgentState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize AgentState", e);
        }
    }

    @Override
    public @NonNull AgentState deserialize(@NonNull String data) {
        try {
            return objectMapper.readValue(data, AgentState.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize AgentState", e);
        }
    }
}
