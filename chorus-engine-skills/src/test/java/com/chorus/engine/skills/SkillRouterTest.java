package com.chorus.engine.skills;

import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRouterTest {

    @Test
    void exactNameMatch() {
        Skill webResearcher = new Skill("web-researcher", "Web Researcher", "Searches web", "Prompt", List.of(), Map.of(), List.of());
        Skill codeWriter = new Skill("code-writer", "Code Writer", "Writes code", "Prompt", List.of(), Map.of(), List.of());

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("Web Researcher", List.of(webResearcher, codeWriter));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().id()).isEqualTo("web-researcher");
    }

    @Test
    void tagMatch() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("research"));
        Skill s2 = new Skill("s2", "B", "Desc", "Prompt", List.of(), Map.of(), List.of("coding"));

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("I need research help", List.of(s1, s2));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().id()).isEqualTo("s1");
    }

    @Test
    void noMatchReturnsErr() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("coding"));

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("xyz unrelated", List.of(s1));

        assertThat(result.isErr()).isTrue();
    }

    @Test
    void emptyCandidatesReturnsErr() {
        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("hello", List.of());

        assertThat(result.isErr()).isTrue();
    }

    @Test
    void descriptionWordMatch() {
        Skill s1 = new Skill("s1", "A", "Helps you search the web", "Prompt", List.of(), Map.of(), List.of());
        Skill s2 = new Skill("s2", "B", "Writes code for you", "Prompt", List.of(), Map.of(), List.of());

        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("search the web", List.of(s1, s2));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().id()).isEqualTo("s1");
    }

    // --- Expanded tests ---

    @Test
    void nullUserInputRejection() {
        SkillRouter router = new SkillRouter();
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of());
        assertThatThrownBy(() -> router.route(null, List.of(s1)))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullCandidatesRejection() {
        SkillRouter router = new SkillRouter();
        assertThatThrownBy(() -> router.route("hello", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tieBreakingFirstSkillWins() {
        // Two skills with identical scores; first one should win due to strict > comparison
        Skill s1 = new Skill("s1", "Search Tool", "Search the web", "Prompt", List.of(), Map.of(), List.of("search"));
        Skill s2 = new Skill("s2", "Search Engine", "Search engine", "Prompt", List.of(), Map.of(), List.of("search"));

        // "search" matches both tags identically (30 points each)
        // Name checks: neither exact match, neither contains full input, input doesn't contain name fully
        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("search", List.of(s1, s2));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().id()).isEqualTo("s1");
    }

    @Test
    void descriptionShortWordsIgnored() {
        Skill s1 = new Skill("s1", "A", "Do it now", "Prompt", List.of(), Map.of(), List.of());
        Skill s2 = new Skill("s2", "B", "Do nothing", "Prompt", List.of(), Map.of(), List.of());

        // Input "do it now" contains "do" (2 chars), "it" (2 chars), "now" (3 chars)
        // Words <= 2 chars are ignored, so "now" should match description of s1
        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("do it now", List.of(s1, s2));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().id()).isEqualTo("s1");
    }

    @Test
    void inputContainingMultipleMatchingTags() {
        Skill s1 = new Skill("s1", "A", "Desc", "Prompt", List.of(), Map.of(), List.of("weather", "forecast"));
        Skill s2 = new Skill("s2", "B", "Desc", "Prompt", List.of(), Map.of(), List.of("weather"));

        // Input contains both "weather" and "forecast" -> s1 gets 60 points, s2 gets 30
        SkillRouter router = new SkillRouter();
        Result<Skill, String> result = router.route("Give me the weather forecast", List.of(s1, s2));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().id()).isEqualTo("s1");
    }
}
