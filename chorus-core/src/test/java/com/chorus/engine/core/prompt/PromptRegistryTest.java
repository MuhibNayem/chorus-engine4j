package com.chorus.engine.core.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptRegistryTest {

    @Test
    void testPublishAndGet() {
        PromptRegistry registry = new PromptRegistry();
        PromptTemplate prompt = PromptTemplate.builder()
            .id("greeting")
            .name("Greeting Prompt")
            .version("1.0.0")
            .content("Hello, {{ name }}!")
            .build();

        registry.publish(prompt);
        registry.setAlias("production", "greeting", "1.0.0");

        PromptTemplate retrieved = registry.get("greeting");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.version()).isEqualTo("1.0.0");
        assertThat(retrieved.render(Map.of("name", "World"))).isEqualTo("Hello, World!");
    }

    @Test
    void testVersioning() {
        PromptRegistry registry = new PromptRegistry();
        registry.publish(PromptTemplate.builder()
            .id("test").name("Test").version("1.0.0").content("V1").build());
        registry.publish(PromptTemplate.builder()
            .id("test").name("Test").version("2.0.0").content("V2").build());

        registry.setAlias("production", "test", "2.0.0");

        assertThat(registry.get("test").content()).isEqualTo("V2");
        assertThat(registry.get("test", "1.0.0").content()).isEqualTo("V1");
    }

    @Test
    void testRollback() {
        PromptRegistry registry = new PromptRegistry();
        registry.publish(PromptTemplate.builder()
            .id("test").name("Test").version("1.0.0").content("V1").build());
        registry.publish(PromptTemplate.builder()
            .id("test").name("Test").version("2.0.0").content("V2").build());

        registry.setAlias("production", "test", "2.0.0");
        assertThat(registry.get("test").content()).isEqualTo("V2");

        boolean rolledBack = registry.rollback("test");
        assertThat(rolledBack).isTrue();
        assertThat(registry.get("test").content()).isEqualTo("V1");
    }

    @Test
    void testAbExperiment() {
        PromptRegistry registry = new PromptRegistry();
        registry.publish(PromptTemplate.builder()
            .id("exp").name("Exp").version("control").content("Control").build());
        registry.publish(PromptTemplate.builder()
            .id("exp").name("Exp").version("treatment").content("Treatment").build());

        registry.startExperiment("exp", "control", "treatment", 0.5);

        // Deterministic assignment based on session ID
        PromptTemplate v1 = registry.getForSession("exp", "session-a");
        PromptTemplate v2 = registry.getForSession("exp", "session-a"); // Same session = same variant
        assertThat(v1.version()).isEqualTo(v2.version());

        registry.stopExperiment("exp", "control");
        assertThat(registry.get("exp").version()).isEqualTo("control");
    }

    @Test
    void testCaching() {
        PromptRegistry registry = new PromptRegistry();
        registry.setCacheTtl(100); // 100ms cache
        registry.publish(PromptTemplate.builder()
            .id("cached").name("Cached").version("1.0.0").content("V1").build());
        registry.setAlias("production", "cached", "1.0.0");

        // First access populates cache
        PromptTemplate first = registry.get("cached");
        assertThat(first).isNotNull();

        // Should still be in cache
        PromptTemplate second = registry.get("cached");
        assertThat(second).isSameAs(first); // Same instance from cache
    }
}
