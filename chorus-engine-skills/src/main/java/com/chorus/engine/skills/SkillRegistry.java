package com.chorus.engine.skills;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for {@link Skill} instances.
 * Supports registration, lookup by id, filtering by tag, and keyword search.
 */
public final class SkillRegistry {

    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();

    public void register(@NonNull Skill skill) {
        Skill existing = skills.putIfAbsent(skill.id(), skill);
        if (existing != null) {
            throw new IllegalArgumentException("Skill already registered: " + skill.id());
        }
    }

    public @NonNull Optional<Skill> findById(@NonNull String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public @NonNull List<Skill> findByTag(@NonNull String tag) {
        String lowerTag = tag.toLowerCase(Locale.ROOT);
        return skills.values().stream()
            .filter(s -> s.tags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).equals(lowerTag)))
            .collect(Collectors.toList());
    }

    /**
     * Simple keyword search on skill names, descriptions, and tags.
     */
    public @NonNull List<Skill> search(@NonNull String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        return skills.values().stream()
            .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(lowerQuery)
                      || s.description().toLowerCase(Locale.ROOT).contains(lowerQuery)
                      || s.tags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(lowerQuery)))
            .collect(Collectors.toList());
    }

    public @NonNull List<Skill> allSkills() {
        return List.copyOf(skills.values());
    }

    public int size() {
        return skills.size();
    }
}
