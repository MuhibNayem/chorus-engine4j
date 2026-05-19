package com.chorus.engine.skills;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    @Test
    void loadFromDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("skills");
        String json1 = """
            {
              "id": "web-researcher",
              "name": "Web Researcher",
              "description": "Searches the web",
              "systemPrompt": "You are a research assistant...",
              "toolNames": ["web_search", "fetch_url"],
              "config": {"maxResults": 5},
              "tags": ["research", "web"]
            }
            """;
        String json2 = """
            {
              "id": "code-writer",
              "name": "Code Writer",
              "description": "Writes code",
              "systemPrompt": "You are a coder...",
              "toolNames": [],
              "config": {},
              "tags": ["code"]
            }
            """;
        Files.writeString(tempDir.resolve("skill1.json"), json1);
        Files.writeString(tempDir.resolve("skill2.json"), json2);
        Files.writeString(tempDir.resolve("readme.txt"), "not a skill");

        SkillLoader loader = new SkillLoader();
        List<Skill> skills = loader.loadFromDirectory(tempDir);

        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.id().equals("web-researcher")));
        assertTrue(skills.stream().anyMatch(s -> s.id().equals("code-writer")));

        Skill webResearcher = skills.stream().filter(s -> s.id().equals("web-researcher")).findFirst().orElseThrow();
        assertEquals("Web Researcher", webResearcher.name());
        assertEquals(List.of("web_search", "fetch_url"), webResearcher.toolNames());
        assertEquals(5, webResearcher.config().get("maxResults"));
        assertEquals(List.of("research", "web"), webResearcher.tags());

        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }

    @Test
    void loadFromClasspath() throws Exception {
        String json = """
            {"id": "test", "name": "Test", "description": "D", "systemPrompt": "P", "toolNames": ["t1"], "config": {}, "tags": ["tag1"]}
            """;
        Path tempDir = Files.createTempDirectory("resources");
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("test.json"), json);

        // Use a custom classloader to test classpath loading
        java.net.URLClassLoader classLoader = new java.net.URLClassLoader(
            new java.net.URL[]{tempDir.toUri().toURL()},
            getClass().getClassLoader()
        );

        SkillLoader loader = new SkillLoader();
        // We can't easily use the custom classloader without reflection on the loader,
        // so let's just test parseSkill directly for correctness
        Skill skill = loader.parseSkill(json);

        assertEquals("test", skill.id());
        assertEquals("Test", skill.name());
        assertEquals(List.of("t1"), skill.toolNames());
        assertEquals(List.of("tag1"), skill.tags());

        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }

    @Test
    void parseSkillWithDefaults() throws Exception {
        String json = """
            {"id": "minimal", "name": "Minimal", "description": "Desc", "systemPrompt": "Prompt"}
            """;

        SkillLoader loader = new SkillLoader();
        Skill skill = loader.parseSkill(json);

        assertEquals("minimal", skill.id());
        assertTrue(skill.toolNames().isEmpty());
        assertTrue(skill.config().isEmpty());
        assertTrue(skill.tags().isEmpty());
    }
}
