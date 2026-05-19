package com.chorus.engine.skills;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A reusable, packaged agent capability — prompt + tools + configuration.
 *
 * @param id           Unique identifier for this skill
 * @param name         Human-readable name
 * @param description  What this skill does
 * @param systemPrompt System prompt used when executing this skill
 * @param toolNames    Names of tools this skill can use
 * @param config       Arbitrary configuration parameters
 * @param tags         Tags for discovery and categorization
 */
public record Skill(
    @NonNull String id,
    @NonNull String name,
    @NonNull String description,
    @NonNull String systemPrompt,
    @NonNull List<String> toolNames,
    @NonNull Map<String, Object> config,
    @NonNull List<String> tags
) {
    public Skill {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt cannot be null");
        toolNames = List.copyOf(toolNames);
        config = Map.copyOf(config);
        tags = List.copyOf(tags);
    }
}
