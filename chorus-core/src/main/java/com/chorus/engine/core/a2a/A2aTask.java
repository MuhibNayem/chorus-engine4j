package com.chorus.engine.core.a2a;

import java.util.List;
import java.util.Map;

/**
 * A2A Task — unit of work delegated between agents.
 */
public record A2aTask(
    String id,
    String state, // SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, FAILED, CANCELED
    String parentTaskId,
    List<A2aMessage> messages,
    List<A2aArtifact> artifacts,
    Map<String, Object> metadata,
    long createdAt,
    Long completedAt
) {
    public A2aTask {
        messages = messages != null ? List.copyOf(messages) : List.of();
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public A2aTask withState(String newState) {
        return new A2aTask(id, newState, parentTaskId, messages, artifacts, metadata, createdAt, completedAt);
    }

    public A2aTask withMessages(List<A2aMessage> newMessages) {
        return new A2aTask(id, state, parentTaskId, newMessages, artifacts, metadata, createdAt, completedAt);
    }

    public A2aTask withArtifacts(List<A2aArtifact> newArtifacts) {
        return new A2aTask(id, state, parentTaskId, messages, newArtifacts, metadata, createdAt, completedAt);
    }

    public static A2aTask create(String id, String description, String sessionId) {
        return new A2aTask(
            id, "SUBMITTED", null,
            List.of(new A2aMessage("user", description, null)),
            List.of(),
            Map.of("sessionId", sessionId),
            System.currentTimeMillis(),
            null
        );
    }
}
