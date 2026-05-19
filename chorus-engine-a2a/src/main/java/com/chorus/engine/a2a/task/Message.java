package com.chorus.engine.a2a.task;

import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record Message(
    @NonNull Role role,
    @NonNull List<Part> parts,
    @Nullable Map<String, Object> metadata
) {

    public enum Role {
        USER("user"),
        AGENT("agent");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    public Message {
        parts = List.copyOf(parts);
        if (metadata != null) metadata = Map.copyOf(metadata);
    }
}
