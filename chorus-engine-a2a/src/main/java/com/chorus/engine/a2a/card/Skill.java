package com.chorus.engine.a2a.card;

import org.jspecify.annotations.NonNull;

import java.util.List;

public record Skill(
    @NonNull String id,
    @NonNull String name,
    @NonNull String description,
    @NonNull List<String> tags
) {
    public Skill {
        tags = List.copyOf(tags);
    }
}
