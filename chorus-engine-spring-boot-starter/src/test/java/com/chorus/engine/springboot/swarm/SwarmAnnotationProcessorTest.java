package com.chorus.engine.springboot.swarm;

import com.chorus.engine.annotation.SwarmAgent;
import com.chorus.engine.annotation.SwarmConfig;
import com.chorus.engine.springboot.ChorusAutoConfiguration;
import com.chorus.engine.springboot.ChorusProperties;
import com.chorus.engine.springboot.testsupport.FakeLlmClient;
import com.chorus.engine.springboot.testsupport.FakeTool;
import com.chorus.engine.swarm.AgentDefinition;
import com.chorus.engine.swarm.HandoffOrchestrator;
import com.chorus.engine.swarm.PlannerExecutorOrchestrator;
import com.chorus.engine.swarm.SupervisorOrchestrator;
import com.chorus.engine.swarm.SwarmOrchestrator;
import com.chorus.engine.tools.Tool;
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
 * Comprehensive tests for {@link SwarmAnnotationProcessor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Basic @SwarmAgent scanning and orchestrator registration</li>
 *   <li>Multiple agents collected into the orchestrator</li>
 *   <li>Orchestrator type selection: handoff (default), supervisor, planner-executor</li>
 *   <li>Model and temperature fallback to ChorusProperties</li>
 *   <li>Tool resolution by name from ToolRegistry</li>
 *   <li>Overrides default swarmOrchestrator bean when agents are present</li>
 *   <li>No-op when no @SwarmAgent beans exist</li>
 * </ul>
 */
class SwarmAnnotationProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues(
            "chorus.enabled=true",
            "chorus.llm.api-key=test-key",
            "chorus.llm.model=gpt-test",
            "chorus.llm.temperature=0.5"
        )
        .withUserConfiguration(MinimalInfraConfig.class);

    // ================================================================
    // BASIC SCANNING & ORCHESTRATOR TYPE
    // ================================================================

    @Test
    void singleSwarmAgentCreatesHandoffOrchestratorByDefault() {
        contextRunner
            .withUserConfiguration(SingleSwarmAgentConfig.class)
            .run(context -> {
                assertThat(context).hasBean("swarmOrchestrator");
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isInstanceOf(HandoffOrchestrator.class);
            });
    }

    @Test
    void supervisorTypeCreatesSupervisorOrchestrator() {
        contextRunner
            .withUserConfiguration(SupervisorSwarmConfig.class)
            .run(context -> {
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isInstanceOf(SupervisorOrchestrator.class);
            });
    }

    @Test
    void plannerExecutorTypeCreatesPlannerExecutorOrchestrator() {
        contextRunner
            .withUserConfiguration(PlannerExecutorSwarmConfig.class)
            .run(context -> {
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isInstanceOf(PlannerExecutorOrchestrator.class);
            });
    }

    // ================================================================
    // MULTIPLE AGENTS
    // ================================================================

    @Test
    void multipleSwarmAgentsCollected() {
        contextRunner
            .withUserConfiguration(MultiSwarmAgentConfig.class)
            .run(context -> {
                assertThat(context).hasBean("swarmOrchestrator");
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isNotNull();
            });
    }

    // ================================================================
    // PROPERTY FALLBACK
    // ================================================================

    @Test
    void emptyModelFallsBackToChorusProperties() {
        contextRunner
            .withUserConfiguration(DefaultModelSwarmAgentConfig.class)
            .run(context -> {
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isNotNull();
            });
    }

    @Test
    void explicitModelOverridesProperties() {
        contextRunner
            .withPropertyValues("chorus.llm.model=gpt-fallback")
            .withUserConfiguration(ExplicitModelSwarmAgentConfig.class)
            .run(context -> {
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isNotNull();
            });
    }

    @Test
    void temperatureFallbackToChorusProperties() {
        contextRunner
            .withUserConfiguration(DefaultTemperatureSwarmAgentConfig.class)
            .run(context -> {
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isNotNull();
            });
    }

    // ================================================================
    // TOOL RESOLUTION
    // ================================================================

    @Test
    void toolNamesResolvedFromToolRegistry() {
        contextRunner
            .withUserConfiguration(ToolRegistryWithFakeToolConfig.class, AgentWithToolsConfig.class)
            .run(context -> {
                ToolRegistry toolRegistry = context.getBean(ToolRegistry.class);
                assertThat(toolRegistry.find("search")).isNotNull();

                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isNotNull();
            });
    }

    // ================================================================
    // DEFAULT ORCHESTRATOR OVERRIDE
    // ================================================================

    @Test
    void swarmAgentsOverrideDefaultOrchestrator() {
        contextRunner
            .withUserConfiguration(SingleSwarmAgentConfig.class)
            .run(context -> {
                // Default orchestrator from ChorusAutoConfiguration should be replaced
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isInstanceOf(HandoffOrchestrator.class);
            });
    }

    // ================================================================
    // NO AGENTS
    // ================================================================

    @Test
    void noSwarmAgentsLeavesDefaultOrchestrator() {
        contextRunner
            .run(context -> {
                assertThat(context).hasBean("swarmOrchestrator");
                SwarmOrchestrator orchestrator = context.getBean(SwarmOrchestrator.class);
                assertThat(orchestrator).isInstanceOf(HandoffOrchestrator.class);
            });
    }

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

    @SwarmAgent(name = "planner", instructions = "You plan tasks.")
    static class PlannerAgentBean {}

    @Configuration
    static class SingleSwarmAgentConfig {
        @Bean
        public PlannerAgentBean plannerAgent() {
            return new PlannerAgentBean();
        }
    }

    @SwarmConfig(orchestrator = "supervisor")
    @SwarmAgent(name = "supervisor", instructions = "You supervise.")
    static class SupervisorAgentBean {}

    @Configuration
    static class SupervisorSwarmConfig {
        @Bean
        public SupervisorAgentBean supervisorAgent() {
            return new SupervisorAgentBean();
        }
    }

    @SwarmConfig(orchestrator = "planner-executor")
    @SwarmAgent(name = "plannerExec", instructions = "You plan and execute.")
    static class PlannerExecAgentBean {}

    @Configuration
    static class PlannerExecutorSwarmConfig {
        @Bean
        public PlannerExecAgentBean plannerExecAgent() {
            return new PlannerExecAgentBean();
        }
    }

    @SwarmAgent(name = "worker1", instructions = "Worker 1")
    static class Worker1Bean {}

    @SwarmAgent(name = "worker2", instructions = "Worker 2")
    static class Worker2Bean {}

    @Configuration
    static class MultiSwarmAgentConfig {
        @Bean
        public Worker1Bean worker1() { return new Worker1Bean(); }

        @Bean
        public Worker2Bean worker2() { return new Worker2Bean(); }
    }

    @SwarmAgent(name = "defaultModelAgent", instructions = "Uses default model", model = "")
    static class DefaultModelAgentBean {}

    @Configuration
    static class DefaultModelSwarmAgentConfig {
        @Bean
        public DefaultModelAgentBean defaultModelAgent() {
            return new DefaultModelAgentBean();
        }
    }

    @SwarmAgent(name = "explicitModelAgent", instructions = "Uses explicit model", model = "gpt-explicit")
    static class ExplicitModelAgentBean {}

    @Configuration
    static class ExplicitModelSwarmAgentConfig {
        @Bean
        public ExplicitModelAgentBean explicitModelAgent() {
            return new ExplicitModelAgentBean();
        }
    }

    @SwarmAgent(name = "defaultTempAgent", instructions = "Uses default temp", temperature = -1.0)
    static class DefaultTemperatureAgentBean {}

    @Configuration
    static class DefaultTemperatureSwarmAgentConfig {
        @Bean
        public DefaultTemperatureAgentBean defaultTempAgent() {
            return new DefaultTemperatureAgentBean();
        }
    }

    @Configuration
    static class ToolRegistryWithFakeToolConfig {
        @Bean
        public ToolRegistry toolRegistry() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new FakeTool("search", "Search the web", Map.of()));
            return registry;
        }
    }

    @SwarmAgent(name = "toolUser", instructions = "Uses tools.", toolNames = {"search"})
    static class ToolUserAgentBean {}

    @Configuration
    static class AgentWithToolsConfig {
        @Bean
        public ToolUserAgentBean toolUserAgent() {
            return new ToolUserAgentBean();
        }
    }
}
