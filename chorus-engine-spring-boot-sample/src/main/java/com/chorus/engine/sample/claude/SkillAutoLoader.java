package com.chorus.engine.sample.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

final class SkillAutoLoader {

    record SkillDef(String name, String description, String content, boolean disableModelInvocation) {}

    private final Path skillsDir;
    private final Map<String, SkillDef> skills = new LinkedHashMap<>();

    SkillAutoLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    void scan() {
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException ignored) {}

        if (!Files.isDirectory(skillsDir)) return;

        try (var stream = Files.list(skillsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path skillFile = dir.resolve("SKILL.md");
                if (Files.exists(skillFile)) {
                    parseSkill(skillFile).ifPresent(s -> skills.put(s.name(), s));
                }
            });
        } catch (IOException ignored) {}
    }

    private Optional<SkillDef> parseSkill(Path file) {
        try {
            String content = Files.readString(file);
            String name = "";
            String description = "";
            boolean disableModelInvocation = false;
            String body = content;

            if (content.startsWith("---")) {
                int endFrontmatter = content.indexOf("---", 3);
                if (endFrontmatter > 0) {
                    String frontmatter = content.substring(3, endFrontmatter).trim();
                    for (String line : frontmatter.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            String key = line.substring(0, colon).trim();
                            String value = line.substring(colon + 1).trim();
                            if ("name".equals(key)) name = value;
                            if ("description".equals(key)) description = value;
                            if ("disable-model-invocation".equals(key))
                                disableModelInvocation = "true".equals(value);
                        }
                    }
                    body = content.substring(endFrontmatter + 3).trim();
                }
            }
            if (name.isEmpty()) return Optional.empty();
            return Optional.of(new SkillDef(name, description, body, disableModelInvocation));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    Map<String, SkillDef> getAll() { return Collections.unmodifiableMap(skills); }

    SkillDef get(String name) { return skills.get(name); }

    String getSkillsContext() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("=== AVAILABLE SKILLS ===\n");
        for (var entry : skills.entrySet()) {
            var s = entry.getValue();
            if (!s.disableModelInvocation()) {
                sb.append("- /").append(s.name()).append(": ").append(s.description()).append("\n");
            }
        }
        return sb.toString();
    }

    String getSkillContent(String name) {
        var s = skills.get(name);
        if (s == null) return null;
        return s.content();
    }

    int count() { return skills.size(); }
}
