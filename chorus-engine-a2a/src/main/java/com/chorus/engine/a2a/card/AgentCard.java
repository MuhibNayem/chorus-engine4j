package com.chorus.engine.a2a.card;

import org.jspecify.annotations.NonNull;

import java.util.List;

public record AgentCard(
    @NonNull String name,
    @NonNull String description,
    @NonNull String url,
    @NonNull String version,
    @NonNull Capabilities capabilities,
    @NonNull Authentication authentication,
    @NonNull List<Skill> skills
) {
    public AgentCard {
        skills = List.copyOf(skills);
    }
}
