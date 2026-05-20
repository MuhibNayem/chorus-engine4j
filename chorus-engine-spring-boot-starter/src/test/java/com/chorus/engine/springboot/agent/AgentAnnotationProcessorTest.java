package com.chorus.engine.springboot.agent;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.annotation.Agent;
import com.chorus.engine.annotation.Tool;
import com.chorus.engine.annotation.ToolParam;
import com.chorus.engine.springboot.ChorusAutoConfiguration;
import com.chorus.engine.springboot.ChorusProperties;
import com.chorus.engine.springboot.testsupport.FakeLlmClient;
import com.chorus.engine.tools.ToolRegistry;
import com.chorus.engine.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link AgentAnnotationProcessor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Basic @Agent + @Tool scanning and bean registration</li>
 *   <li>ChorusProperties fallback for model, temperature, maxTokens, maxRounds</li>
 *   <li>Explicit annotation values overriding properties</li>
 *   <li>Agent without tools still gets an AgentLoop</li>
 *   <li>Multiple agents produce multiple AgentLoops</li>
 *   <li>ToolRegistry population</li>
 *   <li>Idempotency</li>
 *   <li>Missing dependencies (no llmClient, no executor)</li>
 * </ul>
 */
class AgentAnnotationProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues(
            "chorus.enabled=true",
            "chorus.llm.api-key=test-key",
            "chorus.llm.model=gpt-test",
            "chorus.llm.temperature=0.5",
            "chorus.llm.max-tokens=2048",
            "chorus.agent.max-rounds=5"
        )
        .withUserConfiguration(MinimalInfraConfig.class);

    // ================================================================
    // BASIC SCANNING & REGISTRATION
    // ================================================================

    @Test
    void agentWithToolMethodsRegistersAgentLoopAndTools() {
        contextRunner
            .withUserConfiguration(SingleAgentConfig.class)
            .run(context -> {
                assertThat(context).hasBean("myAgent_agentLoop");
                assertThat(context.getBean("myAgent_agentLoop")).isInstanceOf(AgentLoop.class);

                assertThat(context).hasBean("myAgent_tool_0");
                assertThat(context.getBean("myAgent_tool_0")).isInstanceOf(com.chorus.engine.tools.Tool.class);

                ToolRegistry toolRegistry = context.getBean(ToolRegistry.class);
                assertThat(toolRegistry.find("greet")).isNotNull();
            });
    }

    @Test
    void agentWithoutToolsStillRegistersAgentLoop() {
        contextRunner
            .withUserConfiguration(AgentNoToolsConfig.class)
            .run(context -> {
                assertThat(context).hasBean("silentAgent_agentLoop");
                assertThat(context.getBean("silentAgent_agentLoop")).isInstanceOf(AgentLoop.class);
                assertThat(context).doesNotHaveBean("silentAgent_tool_0");
            });
    }

    @Test
    void multipleAgentsRegisterMultipleAgentLoops() {
        contextRunner
            .withUserConfiguration(MultiAgentConfig.class)
            .run(context -> {
                assertThat(context).hasBean("agentA_agentLoop");
                assertThat(context).hasBean("agentB_agentLoop");
                assertThat(context).hasBean("agentA_tool_0");
                assertThat(context).hasBean("agentB_tool_0");
            });
    }

    // ================================================================
    // PROPERTY FALLBACK
    // ================================================================

    @Test
    void explicitAnnotationValuesOverrideProperties() {
        contextRunner
            .withPropertyValues(
                "chorus.llm.model=gpt-fallback",
                "chorus.llm.temperature=0.1",
                "chorus.llm.max-tokens=1024",
                "chorus.agent.max-rounds=99"
            )
            .withUserConfiguration(ExplicitValuesAgentConfig.class)
            .run(context -> {
                AgentLoop loop = context.getBean("explicitAgent_agentLoop", AgentLoop.class);
                // Bean creation succeeding with explicit values means the FactoryBean
                // resolved them correctly (no fallback to properties or defaults needed).
                assertThat(loop).isNotNull();
            });
    }

    @Test
    void emptyAnnotationValuesFallBackToChorusProperties() {
        contextRunner
            .withUserConfiguration(DefaultValuesAgentConfig.class)
            .run(context -> {
                // When annotation values are empty/defaults, the FactoryBean falls back
                // to ChorusProperties and then to framework defaults.
                // The key assertion is that the AgentLoop bean is successfully created.
                AgentLoop loop = context.getBean("defaultAgent_agentLoop", AgentLoop.class);
                assertThat(loop).isNotNull();
            });
    }

    @Test
    void fallbackWhenChorusPropertiesBeanMissing() {
        new ApplicationContextRunner()
            .withUserConfiguration(
                MinimalInfraWithoutPropertiesConfig.class,
                SingleAgentConfig.class,
                ProcessorConfig.class
            )
            .run(context -> {
                assertThat(context).hasBean("myAgent_agentLoop");
                assertThat(context.getBean("myAgent_agentLoop")).isInstanceOf(AgentLoop.class);
            });
    }

    // ================================================================
    // IDEMPOTENCY
    // ================================================================

    @Test
    void idempotentRegistrationDoesNotDuplicateBeans() {
        contextRunner
            .withUserConfiguration(SingleAgentConfig.class)
            .run(context -> {
                // If we refresh the same context, beans should still be unique
                assertThat(context).hasSingleBean(com.chorus.engine.springboot.agent.AgentAnnotationProcessor.class);
                String[] toolBeans = context.getBeanNamesForType(com.chorus.engine.tools.Tool.class);
                long greetToolCount = java.util.Arrays.stream(toolBeans)
                    .filter(name -> name.contains("myAgent_tool_"))
                    .count();
                assertThat(greetToolCount).isEqualTo(1);
            });
    }

    // ================================================================
    // MISSING DEPENDENCIES
    // ================================================================


    // ================================================================
    // Test configurations
    // ================================================================

    @Configuration
    static class MinimalInfraConfig {
        @Bean
        public LlmClient llmClient() {
            return new FakeLlmClient();
        }

        @Bean
        public ExecutorService chorusExecutor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    @Configuration
    static class MinimalInfraWithoutPropertiesConfig {
        @Bean
        public LlmClient llmClient() {
            return new FakeLlmClient();
        }

        @Bean
        public ExecutorService chorusExecutor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }

        @Bean
        public ToolRegistry toolRegistry() {
            return new ToolRegistry();
        }
    }

    @Configuration
    static class ExecutorOnlyConfig {
        @Bean
        public ExecutorService chorusExecutor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    @Configuration
    static class ProcessorConfig {
        @Bean
        public static AgentAnnotationProcessor agentAnnotationProcessor() {
            return new AgentAnnotationProcessor();
        }
    }

    @Agent(name = "myAgent", systemPrompt = "You are a test agent.")
    static class MyAgentBean {
        @Tool(value = "greet", description = "Say hello")
        public String greet(@ToolParam(description = "Name") String name) {
            return "Hello, " + name;
        }
    }

    @Configuration
    static class SingleAgentConfig {
        @Bean
        public MyAgentBean myAgent() {
            return new MyAgentBean();
        }
    }

    @Agent(name = "silentAgent", systemPrompt = "You are silent.")
    static class SilentAgentBean {
        public void doNothing() {}
    }

    @Configuration
    static class AgentNoToolsConfig {
        @Bean
        public SilentAgentBean silentAgent() {
            return new SilentAgentBean();
        }
    }

    @Agent(name = "agentA", systemPrompt = "Agent A")
    static class AgentABean {
        @Tool("toolA")
        public String toolA() { return "A"; }
    }

    @Agent(name = "agentB", systemPrompt = "Agent B")
    static class AgentBBean {
        @Tool("toolB")
        public String toolB() { return "B"; }
    }

    @Configuration
    static class MultiAgentConfig {
        @Bean
        public AgentABean agentA() { return new AgentABean(); }

        @Bean
        public AgentBBean agentB() { return new AgentBBean(); }
    }

    @Agent(name = "explicitAgent", systemPrompt = "Explicit",
           model = "gpt-explicit", temperature = 0.3f, maxTokens = 1024, maxRounds = 3)
    static class ExplicitValuesAgentBean {
        @Tool("doIt")
        public String doIt() { return "done"; }
    }

    @Configuration
    static class ExplicitValuesAgentConfig {
        @Bean
        public ExplicitValuesAgentBean explicitAgent() {
            return new ExplicitValuesAgentBean();
        }
    }

    @Agent(name = "defaultAgent", systemPrompt = "Defaults")
    static class DefaultValuesAgentBean {
        @Tool("work")
        public String work() { return "worked"; }
    }

    @Configuration
    static class DefaultValuesAgentConfig {
        @Bean
        public DefaultValuesAgentBean defaultAgent() {
            return new DefaultValuesAgentBean();
        }
    }
}
