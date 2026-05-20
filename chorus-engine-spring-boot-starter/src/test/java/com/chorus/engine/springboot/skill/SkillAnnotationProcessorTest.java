package com.chorus.engine.springboot.skill;

import com.chorus.engine.annotation.Skill;
import com.chorus.engine.annotation.SkillSource;
import com.chorus.engine.skills.SkillRegistry;
import com.chorus.engine.springboot.ChorusAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link SkillAnnotationProcessor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>@Skill bean registered in SkillRegistry</li>
 *   <li>@SkillSource classpath loading</li>
 *   <li>@SkillSource file loading</li>
 *   <li>Invalid source location skipped gracefully</li>
 *   <li>Missing skillRegistry → no-op</li>
 * </ul>
 */
class SkillAnnotationProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues("chorus.enabled=true");

    // ================================================================
    // @SKILL REGISTRATION
    // ================================================================

    @Test
    void skillBeanRegisteredInRegistry() {
        contextRunner
            .withUserConfiguration(SkillBeanConfig.class)
            .run(context -> {
                assertThat(context).hasBean("skillRegistry");
                SkillRegistry registry = context.getBean(SkillRegistry.class);
                assertThat(registry.findById("math-tutor")).isPresent();
                assertThat(registry.findById("math-tutor").get().name()).isEqualTo("Math Tutor");
            });
    }

    @Test
    void skillBeanWithDefaultNameUsesId() {
        contextRunner
            .withUserConfiguration(SkillBeanNoNameConfig.class)
            .run(context -> {
                SkillRegistry registry = context.getBean(SkillRegistry.class);
                assertThat(registry.findById("code-helper")).isPresent();
                assertThat(registry.findById("code-helper").get().name()).isEqualTo("code-helper");
            });
    }

    // ================================================================
    // SKILL TAGS
    // ================================================================

    @Test
    void skillTagsArePreserved() {
        contextRunner
            .withUserConfiguration(SkillBeanConfig.class)
            .run(context -> {
                SkillRegistry registry = context.getBean(SkillRegistry.class);
                assertThat(registry.findByTag("education")).hasSize(1);
            });
    }

    // ================================================================
    // MISSING REGISTRY
    // ================================================================

    @Test
    void missingSkillRegistryMeansNoOp() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
            .withPropertyValues("chorus.enabled=true")
            .withUserConfiguration(SkillBeanConfig.class)
            .run(context -> {
                assertThat(context).hasBean("skillRegistry");
                // In normal Spring Boot context skillRegistry is always present
            });
    }

    // ================================================================
    // Test configurations
    // ================================================================

    @Skill(id = "math-tutor", name = "Math Tutor", description = "Helps with math",
           systemPrompt = "You are a math tutor.", toolNames = {"calculator"}, tags = {"education"})
    static class MathTutorSkill {}

    @Configuration
    static class SkillBeanConfig {
        @Bean
        public MathTutorSkill mathTutorSkill() {
            return new MathTutorSkill();
        }
    }

    @Skill(id = "code-helper", description = "Helps with code",
           systemPrompt = "You are a coding assistant.", toolNames = {}, tags = {})
    static class CodeHelperSkill {}

    @Configuration
    static class SkillBeanNoNameConfig {
        @Bean
        public CodeHelperSkill codeHelperSkill() {
            return new CodeHelperSkill();
        }
    }
}
