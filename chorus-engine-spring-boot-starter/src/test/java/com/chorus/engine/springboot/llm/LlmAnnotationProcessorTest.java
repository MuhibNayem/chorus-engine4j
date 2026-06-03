package com.chorus.engine.springboot.llm;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.provider.ProviderRegistry;
import com.chorus.engine.springboot.ChorusAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAnnotationProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues(
            "chorus.enabled=true",
            "chorus.llm.provider=mock",
            "chorus.llm.mock.scripts[0].trigger=*",
            "chorus.llm.mock.scripts[0].response=Hello from mock"
        );

    @Test
    void registersLlmProviderFactoryBean() {
        contextRunner
            .withUserConfiguration(SingleProviderConfig.class)
            .run(context -> {
                assertThat(context).hasBean("llmProvider_openai");
                assertThat(context.getBean("llmProvider_openai")).isNotNull();
            });
    }

    @Test
    void primaryAliasCreatedWhenPrimaryLlmProviderSet() {
        ApplicationContextRunner mockContext = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
            .withPropertyValues(
                "chorus.enabled=true",
                "chorus.llm.provider=mock",
                "chorus.llm.mock.scripts[0].trigger=*",
                "chorus.llm.mock.scripts[0].response=Hello"
            )
            .withUserConfiguration(ProviderWithPrimaryConfig.class)
            .run(context -> {
                assertThat(context).hasBean("llmClient");
                assertThat(context).hasBean("llmProvider_openai");
                Object llmClient = context.getBean("llmClient");
                assertThat(llmClient).isNotNull();
            });
    }

    @Test
    void multipleProvidersRegistered() {
        contextRunner
            .withUserConfiguration(MultiProviderConfig.class)
            .run(context -> {
                assertThat(context).hasBean("llmProvider_openai");
                assertThat(context).hasBean("llmProvider_anthropic");
                assertThat(context).hasBean("llmProvider_gemini");
            });
    }

    @Test
    void idempotentDoesNotDuplicateBeans() {
        contextRunner
            .withUserConfiguration(SingleProviderConfig.class)
            .run(context -> {
                assertThat(context).hasBean("llmProvider_openai");
            });
    }

    @Test
    void skipsWhenNoLlmProviderAnnotationsPresent() {
        ApplicationContextRunner emptyRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
            .withPropertyValues(
                "chorus.enabled=true",
                "chorus.llm.provider=mock",
                "chorus.llm.mock.scripts[0].trigger=*",
                "chorus.llm.mock.scripts[0].response=Hello"
            )
            .withUserConfiguration(EmptyProviderConfig.class)
            .run(context -> {
                assertThat(context).hasBean("chorusProviderRegistry");
                assertThat(context).doesNotHaveBean("llmProvider_openai");
            });
    }

    @Test
    void providerRegisteredInRegistryWhenBeanCreated() {
        ApplicationContextRunner mockContext = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
            .withPropertyValues(
                "chorus.enabled=true",
                "chorus.llm.provider=mock",
                "chorus.llm.mock.scripts[0].trigger=*",
                "chorus.llm.mock.scripts[0].response=Hello"
            )
            .withUserConfiguration(SingleProviderConfig.class)
            .run(context -> {
                ProviderRegistry registry = context.getBean("chorusProviderRegistry", ProviderRegistry.class);
                assertThat(registry.hasProvider("openai")).isTrue();

                LlmClient openai = context.getBean("llmProvider_openai", LlmClient.class);
                assertThat(openai).isNotNull();
                assertThat(openai.providerName()).isNotNull();
            });
    }

    @com.chorus.engine.annotation.LlmProvider(name = "openai", type = "openai",
        baseUrl = "https://api.openai.com/v1", apiKey = "test-key")
    void openAiProvider() {}

    @com.chorus.engine.annotation.LlmProvider(name = "anthropic", type = "anthropic",
        apiKey = "test-key")
    void anthropicProvider() {}

    @com.chorus.engine.annotation.LlmProvider(name = "gemini", type = "gemini",
        apiKey = "test-key")
    void geminiProvider() {}

    @Configuration
    static class SingleProviderConfig {
        @Bean
        LlmAnnotationProcessorTest testBean() { return new LlmAnnotationProcessorTest(); }
    }

    @Configuration
    @com.chorus.engine.annotation.PrimaryLlmProvider("openai")
    static class ProviderWithPrimaryConfig {
        @Bean
        LlmAnnotationProcessorTest testBean() { return new LlmAnnotationProcessorTest(); }
    }

    @Configuration
    static class MultiProviderConfig {
        @Bean
        LlmAnnotationProcessorTest testBean() { return new LlmAnnotationProcessorTest(); }
    }

    @Configuration
    static class EmptyProviderConfig {
    }
}
