package com.chorus.engine.skills;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillTemplatingTest {

    @Test
    void renderSimpleVariables() {
        String template = "Hello {{name}}, welcome to {{place}}!";
        String result = SkillTemplating.render(template, Map.of("name", "Alice", "place", "Wonderland"));
        assertThat(result).isEqualTo("Hello Alice, welcome to Wonderland!");
    }

    @Test
    void renderWithDefaults() {
        String template = "Timeout: {{timeout:30}}s, Retries: {{retries:3}}";
        String result = SkillTemplating.render(template, Map.of("timeout", 60));
        assertThat(result).isEqualTo("Timeout: 60s, Retries: 3");
    }

    @Test
    void renderMissingVariable() {
        String template = "Value: {{missing}}";
        String result = SkillTemplating.render(template, Map.of());
        assertThat(result).isEqualTo("Value: {{missing}}");
    }

    @Test
    void hasUnresolvedVariables() {
        assertThat(SkillTemplating.hasUnresolvedVariables("{{a}}", Map.of())).isTrue();
        assertThat(SkillTemplating.hasUnresolvedVariables("{{a:default}}", Map.of())).isFalse();
        assertThat(SkillTemplating.hasUnresolvedVariables("{{a}}", Map.of("a", 1))).isFalse();
    }

    @Test
    void renderSkill() {
        Skill skill = new Skill("s1", "Test", "Test skill",
            "Process {{count}} items using {{model:gpt-4}}",
            List.of(), Map.of(), List.of());

        Skill rendered = SkillTemplating.renderSkill(skill, Map.of("count", 10));
        assertThat(rendered.systemPrompt()).isEqualTo("Process 10 items using gpt-4");
        assertThat(rendered.id()).isEqualTo("s1");
    }
}
