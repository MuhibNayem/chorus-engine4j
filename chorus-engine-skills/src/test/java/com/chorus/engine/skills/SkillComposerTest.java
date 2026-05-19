package com.chorus.engine.skills;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillComposerTest {

    @Test
    void composePipeline() {
        Skill step1 = new Skill("s1", "Research", "Research topic",
            "You are a researcher. Find info on: ",
            List.of("web_search"), Map.of("maxResults", 5), List.of("research"));

        Skill step2 = new Skill("s2", "Writer", "Write summary",
            "You are a writer. Summarize: ",
            List.of(), Map.of("style", "concise"), List.of("writing"));

        SkillComposer composer = new SkillComposer();
        composer.then(step1).then(step2);

        Skill composed = composer.compose("pipeline-1", "ResearchWriter", "Research then write");

        assertThat(composed.id()).isEqualTo("pipeline-1");
        assertThat(composed.systemPrompt()).contains("STEP 1: Research");
        assertThat(composed.systemPrompt()).contains("STEP 2: Writer");
        assertThat(composed.toolNames()).contains("web_search");
        assertThat(composed.config()).containsEntry("maxResults", 5);
        assertThat(composed.config()).containsEntry("style", "concise");
        assertThat(composed.tags()).contains("research", "writing");
    }

    @Test
    void emptyComposer() {
        SkillComposer composer = new SkillComposer();
        assertThat(composer.isEmpty()).isTrue();
        assertThat(composer.size()).isEqualTo(0);
    }
}
