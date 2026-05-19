package com.chorus.engine.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads skills from various sources: directory, classpath, and remote URL.
 */
public final class SkillLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Load all {@code .json} skill files from a directory.
     *
     * @param dir directory containing skill JSON files
     * @return list of loaded skills
     */
    public @NonNull List<Skill> loadFromDirectory(@NonNull Path dir) throws IOException {
        List<Skill> loaded = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        loaded.add(parseSkill(Files.readString(p)));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read skill file: " + p, e);
                    }
                });
        }
        return loaded;
    }

    /**
     * Load a skill from the classpath.
     *
     * @param resourcePath classpath resource path, e.g. "skills/web-researcher.json"
     * @return the loaded skill
     */
    public @NonNull Skill loadFromClasspath(@NonNull String resourcePath) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }
        try (is) {
            return parseSkill(new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Load a skill from a remote URL.
     *
     * @param url remote URL pointing to a skill JSON file
     * @return the loaded skill
     */
    public @NonNull Skill loadFromUrl(@NonNull String url) throws IOException {
        try (InputStream is = new URL(url).openStream()) {
            return parseSkill(new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    Skill parseSkill(@NonNull String json) throws IOException {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);

        String id = getString(map, "id");
        String name = getString(map, "name");
        String description = getString(map, "description");
        String systemPrompt = getString(map, "systemPrompt");
        List<String> toolNames = getList(map, "toolNames");
        Map<String, Object> config = getMap(map, "config");
        List<String> tags = getList(map, "tags");

        return new Skill(id, name, description, systemPrompt, toolNames, config, tags);
    }

    @SuppressWarnings("unchecked")
    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> getList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}
