package com.chorus.engine.a2a.task;

import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record Task(
    @NonNull String id,
    @NonNull String sessionId,
    @NonNull Status status,
    @NonNull List<Message> history,
    @Nullable List<Artifact> artifacts,
    @Nullable Map<String, Object> metadata
) {

    public enum Status {
        SUBMITTED("submitted"),
        WORKING("working"),
        INPUT_REQUIRED("input-required"),
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELED("canceled");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    public Task {
        history = List.copyOf(history);
        if (artifacts != null) artifacts = List.copyOf(artifacts);
        if (metadata != null) metadata = Map.copyOf(metadata);
    }
}
