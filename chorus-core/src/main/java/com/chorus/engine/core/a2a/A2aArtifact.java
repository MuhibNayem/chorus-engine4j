package com.chorus.engine.core.a2a;

import java.util.Map;

/**
 * A2A Artifact — result of a task, may contain text, images, files, etc.
 */
public record A2aArtifact(
    String name,
    String type, // text, image, file, json
    String content,
    Map<String, Object> metadata
) {
    public A2aArtifact {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
