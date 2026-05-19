package com.chorus.engine.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    @Test
    void registerAndFindById() {
        Skill skill = new Skill("web-researcher", "Web Researcher", "Searches the web",
            "You are a research assistant...", List.of("web_search"), Map.of(), List.of("research", "web"));

        registry.register(skill);

        Optional<Skill> found = registry.findById("web-researcher");
        assertTrue(found.isPresent());
        assertEquals("Web Researcher", found.get().name());
    }

    @Test
    void findByIdMissing() {
        assertTrue(registry.findById("missing").isEmpty());
    }

    @Test
    void duplicateRegistrationThrows() {
        Skill skill = new Skill("s1", "Name", "Desc", "Prompt", List.of(), Map.of(), List.of());
        registry.register(skill);

        assertThrows(IllegalArgumentException.class, () -> registry.register(skill));
    }

    @Test
    void findByTag() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("web", "search"));
        Skill s2 = new Skill("s2", "B", "Desc", "Prompt", List.of(), Map.of(), List.of("code"));
        registry.register(s1);
        registry.register(s2);

        List<Skill> webSkills = registry.findByTag("web");
        assertEquals(1, webSkills.size());
        assertEquals("s1", webSkills.get(0).id());
    }

    @Test
    void findByTagCaseInsensitive() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("WEB"));
        registry.register(s1);

        List<Skill> found = registry.findByTag("web");
        assertEquals(1, found.size());
    }

    @Test
    void searchByName() {
        Skill s1 = new Skill("s1", "Web Researcher", "Desc", "Prompt", List.of(), Map.of(), List.of());
        Skill s2 = new Skill("s2", "Code Writer", "Desc", "Prompt", List.of(), Map.of(), List.of());
        registry.register(s1);
        registry.register(s2);

        List<Skill> results = registry.search("web");
        assertEquals(1, results.size());
        assertEquals("s1", results.get(0).id());
    }

    @Test
    void searchByDescription() {
        Skill s1 = new Skill("s1", "A", "Searches the web", "Prompt", List.of(), Map.of(), List.of());
        registry.register(s1);

        List<Skill> results = registry.search("searches");
        assertEquals(1, results.size());
    }

    @Test
    void searchByTag() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("automation"));
        registry.register(s1);

        List<Skill> results = registry.search("auto");
        assertEquals(1, results.size());
    }

    @Test
    void allSkills() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of());
        registry.register(s1);

        assertEquals(1, registry.allSkills().size());
        assertEquals(1, registry.size());
    }
}
