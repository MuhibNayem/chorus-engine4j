package com.chorus.engine.a2a.task;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record Artifact(
    @NonNull String name,
    @NonNull List<Part> parts,
    @Nullable Map<String, Object> metadata
) {
    public Artifact {
        parts = List.copyOf(parts);
        if (metadata != null) metadata = Map.copyOf(metadata);
    }
}
