package com.chorus.engine.skills;

import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillRouterTest {

    @Test
    void exactNameMatch() {
        Skill webResearcher = new Skill("web-researcher", "Web Researcher", "Searches web", "Prompt", List.of(), Map.of(), List.of());
        Skill codeWriter = new Skill("code-writer", "Code Writer", "Writes code", "Prompt", List.of(), Map.of(), List.of());

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("Web Researcher", List.of(webResearcher, codeWriter));

        assertTrue(result.isOk());
        assertEquals("web-researcher", result.unwrap().id());
    }

    @Test
    void tagMatch() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("research"));
        Skill s2 = new Skill("s2", "B", "Desc", "Prompt", List.of(), Map.of(), List.of("coding"));

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("I need research help", List.of(s1, s2));

        assertTrue(result.isOk());
        assertEquals("s1", result.unwrap().id());
    }

    @Test
    void noMatchReturnsErr() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("coding"));

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("xyz unrelated", List.of(s1));

        assertTrue(result.isErr());
    }

    @Test
    void emptyCandidatesReturnsErr() {
        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("hello", List.of());

        assertTrue(result.isErr());
    }

    @Test
    void descriptionWordMatch() {
        Skill s1 = new Skill("s1", "A", "Helps you search the web", "Prompt", List.of(), Map.of(), List.of());
        Skill s2 = new Skill("s2", "B", "Writes code for you", "Prompt", List.of(), Map.of(), List.of());

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("search the web", List.of(s1, s2));

        assertTrue(result.isOk());
        assertEquals("s1", result.unwrap().id());
    }
}
