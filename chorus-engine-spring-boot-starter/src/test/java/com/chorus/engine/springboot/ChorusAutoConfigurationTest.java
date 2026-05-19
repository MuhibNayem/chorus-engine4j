package com.chorus.engine.springboot;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.a2a.client.A2aClient;
import com.chorus.engine.evals.EvalRunner;
import com.chorus.engine.evals.LlmJudgeScorer;
import com.chorus.engine.evals.ParallelEvalRunner;
import com.chorus.engine.evals.SemanticSimilarityScorer;
import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.TieredGuardrailEngine;
import com.chorus.engine.guardrails.redaction.PiiRedactionEngine;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.llm.provider.ProviderRegistry;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.server.McpServer;
import com.chorus.engine.mcp.transport.McpTransport;
import com.chorus.engine.rag.chunking.ChunkingStrategy;
import com.chorus.engine.rag.pipeline.ContextAssembler;
import com.chorus.engine.rag.pipeline.RAGPipeline;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.store.VectorStore;
import com.chorus.engine.skills.SkillRegistry;
import com.chorus.engine.skills.SkillRouter;
import com.chorus.engine.swarm.SwarmConfig;
import com.chorus.engine.swarm.SwarmOrchestrator;
import com.chorus.engine.telemetry.event.EventBus;
import com.chorus.engine.telemetry.logging.StructuredLogger;
import com.chorus.engine.telemetry.metrics.MetricsCollector;
import com.chorus.engine.telemetry.provenance.ProvenanceTracker;
import com.chorus.engine.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

class ChorusAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues("chorus.enabled=true");

    // ================================================================
    // CORE INFRASTRUCTURE
    // ================================================================

    @Test
    void coreBeansCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("chorusObjectMapper");
            assertThat(context).hasBean("chorusHttpClient");
            assertThat(context).hasBean("chorusRetryPolicy");
            assertThat(context).hasBean("chorusCircuitBreaker");
            assertThat(context).hasBean("chorusProviderRegistry");

            assertThat(context.getBean("chorusObjectMapper")).isInstanceOf(ObjectMapper.class);
            assertThat(context.getBean("chorusHttpClient")).isInstanceOf(HttpClient.class);
            assertThat(context.getBean("chorusRetryPolicy")).isInstanceOf(RetryPolicy.class);
            assertThat(context.getBean("chorusCircuitBreaker")).isInstanceOf(CircuitBreaker.class);
            assertThat(context.getBean("chorusProviderRegistry")).isInstanceOf(ProviderRegistry.class);
        });
    }

    // ================================================================
    // LLM LAYER
    // ================================================================

    @Test
    void llmBeansCreatedWhenApiKeyPresent() {
        contextRunner
            .withPropertyValues("chorus.llm.api-key=test-key")
            .run(context -> {
                assertThat(context).hasBean("llmClient");
                assertThat(context).hasBean("embeddingClient");
                assertThat(context.getBean("llmClient")).isInstanceOf(LlmClient.class);
                assertThat(context.getBean("embeddingClient")).isInstanceOf(EmbeddingClient.class);
            });
    }

    @Test
    void llmBeansAbsentWhenNoApiKey() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("llmClient");
            assertThat(context).doesNotHaveBean("embeddingClient");
        });
    }

    // ================================================================
    // RAG LAYER
    // ================================================================

    @Test
    void ragBeansCreated() {
        contextRunner
            .withPropertyValues("chorus.llm.api-key=test-key")
            .run(context -> {
                assertThat(context).hasBean("vectorStore");
                assertThat(context).hasBean("chunkingStrategy");
                assertThat(context).hasBean("contextAssembler");
                assertThat(context).hasBean("ragPipeline");

                assertThat(context.getBean("vectorStore")).isInstanceOf(VectorStore.class);
                assertThat(context.getBean("chunkingStrategy")).isInstanceOf(ChunkingStrategy.class);
                assertThat(context.getBean("contextAssembler")).isInstanceOf(ContextAssembler.class);
                assertThat(context.getBean("ragPipeline")).isInstanceOf(RAGPipeline.class);
            });
    }

    @Test
    void retrievalEngineCreatedWhenApiKeyPresent() {
        contextRunner
            .withPropertyValues("chorus.llm.api-key=test-key")
            .run(context -> {
                assertThat(context).hasBean("retrievalEngine");
                assertThat(context.getBean("retrievalEngine")).isInstanceOf(RetrievalEngine.class);
            });
    }

    // ================================================================
    // AGENT LAYER
    // ================================================================

    @Test
    void agentLoopCreatedWhenApiKeyPresent() {
        contextRunner
            .withPropertyValues("chorus.llm.api-key=test-key")
            .run(context -> {
                assertThat(context).hasBean("agentLoop");
                assertThat(context.getBean("agentLoop")).isInstanceOf(AgentLoop.class);
            });
    }

    @Test
    void toolRegistryCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("toolRegistry");
            assertThat(context.getBean("toolRegistry")).isInstanceOf(ToolRegistry.class);
        });
    }

    // ================================================================
    // SWARM LAYER
    // ================================================================

    @Test
    void swarmConfigCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("swarmConfig");
            assertThat(context.getBean("swarmConfig")).isInstanceOf(SwarmConfig.class);
        });
    }

    @Test
    void swarmOrchestratorCreatedWhenApiKeyPresent() {
        contextRunner
            .withPropertyValues("chorus.llm.api-key=test-key")
            .run(context -> {
                assertThat(context).hasBean("swarmOrchestrator");
                assertThat(context.getBean("swarmOrchestrator")).isInstanceOf(SwarmOrchestrator.class);
            });
    }

    // ================================================================
    // SKILLS LAYER
    // ================================================================

    @Test
    void skillRegistryCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("skillRegistry");
            assertThat(context.getBean("skillRegistry")).isInstanceOf(SkillRegistry.class);
        });
    }

    @Test
    void skillRouterCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("skillRouter");
            assertThat(context.getBean("skillRouter")).isInstanceOf(SkillRouter.class);
        });
    }

    // ================================================================
    // TELEMETRY LAYER
    // ================================================================

    @Test
    void telemetryBeansCreatedByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("eventBus");
            assertThat(context).hasBean("metricsCollector");
            assertThat(context).hasBean("provenanceTracker");
            assertThat(context).hasBean("structuredLogger");

            assertThat(context.getBean("eventBus")).isInstanceOf(EventBus.class);
            assertThat(context.getBean("metricsCollector")).isInstanceOf(MetricsCollector.class);
            assertThat(context.getBean("provenanceTracker")).isInstanceOf(ProvenanceTracker.class);
            assertThat(context.getBean("structuredLogger")).isInstanceOf(StructuredLogger.class);
        });
    }

    @Test
    void telemetryBeansAbsentWhenDisabled() {
        contextRunner
            .withPropertyValues("chorus.telemetry.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean("eventBus");
                assertThat(context).doesNotHaveBean("metricsCollector");
                assertThat(context).doesNotHaveBean("provenanceTracker");
                assertThat(context).doesNotHaveBean("structuredLogger");
            });
    }

    // ================================================================
    // GUARDRAILS LAYER
    // ================================================================

    @Test
    void guardrailsBeansCreatedWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "chorus.guardrails.enabled=true",
                "chorus.llm.api-key=test-key"
            )
            .run(context -> {
                assertThat(context).hasBean("piiRedactionEngine");
                assertThat(context).hasBean("guardrails");
                assertThat(context).hasBean("tieredGuardrailEngine");

                assertThat(context.getBean("piiRedactionEngine")).isInstanceOf(PiiRedactionEngine.class);
                assertThat(context.getBean("guardrails")).isInstanceOf(java.util.List.class);
                assertThat(context.getBean("tieredGuardrailEngine")).isInstanceOf(TieredGuardrailEngine.class);
            });
    }

    @Test
    void guardrailsBeansAbsentWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("piiRedactionEngine");
            assertThat(context).doesNotHaveBean("guardrails");
            assertThat(context).doesNotHaveBean("tieredGuardrailEngine");
        });
    }

    // ================================================================
    // MCP LAYER
    // ================================================================

    @Test
    void mcpBeansCreatedWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "chorus.mcp.enabled=true",
                "chorus.mcp.server-enabled=true"
            )
            .run(context -> {
                assertThat(context).hasBean("mcpTransport");
                assertThat(context).hasBean("mcpClient");
                assertThat(context).hasBean("mcpServer");

                assertThat(context.getBean("mcpTransport")).isInstanceOf(McpTransport.class);
                assertThat(context.getBean("mcpClient")).isInstanceOf(McpClient.class);
                assertThat(context.getBean("mcpServer")).isInstanceOf(McpServer.class);
            });
    }

    @Test
    void mcpBeansAbsentWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("mcpTransport");
            assertThat(context).doesNotHaveBean("mcpClient");
            assertThat(context).doesNotHaveBean("mcpServer");
        });
    }

    // ================================================================
    // A2A LAYER
    // ================================================================

    @Test
    void a2aBeansCreatedWhenEnabled() {
        contextRunner
            .withPropertyValues("chorus.a2a.enabled=true")
            .run(context -> {
                assertThat(context).hasBean("a2aClient");
                assertThat(context.getBean("a2aClient")).isInstanceOf(A2aClient.class);
            });
    }

    @Test
    void a2aBeansAbsentWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("a2aClient");
        });
    }

    // ================================================================
    // EVALS LAYER
    // ================================================================

    @Test
    void evalsBeansCreatedWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "chorus.evals.enabled=true",
                "chorus.llm.api-key=test-key"
            )
            .run(context -> {
                assertThat(context).hasBean("evalRunner");
                assertThat(context).hasBean("parallelEvalRunner");
                assertThat(context).hasBean("llmJudgeScorer");
                assertThat(context).hasBean("semanticSimilarityScorer");

                assertThat(context.getBean("evalRunner")).isInstanceOf(EvalRunner.class);
                assertThat(context.getBean("parallelEvalRunner")).isInstanceOf(ParallelEvalRunner.class);
                assertThat(context.getBean("llmJudgeScorer")).isInstanceOf(LlmJudgeScorer.class);
                assertThat(context.getBean("semanticSimilarityScorer")).isInstanceOf(SemanticSimilarityScorer.class);
            });
    }

    @Test
    void evalsBeansAbsentWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("evalRunner");
            assertThat(context).doesNotHaveBean("parallelEvalRunner");
        });
    }

    // ================================================================
    // CONDITIONAL ON MISSING BEAN
    // ================================================================

    @Test
    void userProvidedBeanOverridesAutoConfiguredOne() {
        contextRunner
            .withUserConfiguration(UserObjectMapperConfig.class)
            .run(context -> {
                assertThat(context).hasBean("chorusObjectMapper");
                assertThat(context.getBean("chorusObjectMapper")).isSameAs(UserObjectMapperConfig.CUSTOM_MAPPER);
            });
    }

    @Configuration
    static class UserObjectMapperConfig {
        static final ObjectMapper CUSTOM_MAPPER = new ObjectMapper();

        @Bean
        public ObjectMapper chorusObjectMapper() {
            return CUSTOM_MAPPER;
        }
    }

    // ================================================================
    // PROPERTIES BINDING
    // ================================================================

    @Test
    void propertiesBinding() {
        contextRunner
            .withPropertyValues(
                "chorus.enabled=true",
                "chorus.llm.api-key=my-secret-key",
                "chorus.llm.model=gpt-test",
                "chorus.llm.temperature=0.5",
                "chorus.llm.max-tokens=2048",
                "chorus.agent.agent-id=test-agent",
                "chorus.agent.max-rounds=5",
                "chorus.rag.chunk-size=256",
                "chorus.rag.chunk-overlap=25",
                "chorus.swarm.max-turns=20",
                "chorus.telemetry.enabled=false",
                "chorus.evals.enabled=true",
                "chorus.evals.llm-judge-pass-threshold=0.75",
                "chorus.guardrails.enabled=true"
            )
            .run(context -> {
                ChorusProperties props = context.getBean(ChorusProperties.class);

                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getLlm().getApiKey()).isEqualTo("my-secret-key");
                assertThat(props.getLlm().getModel()).isEqualTo("gpt-test");
                assertThat(props.getLlm().getTemperature()).isEqualTo(0.5);
                assertThat(props.getLlm().getMaxTokens()).isEqualTo(2048);

                assertThat(props.getAgent().getAgentId()).isEqualTo("test-agent");
                assertThat(props.getAgent().getMaxRounds()).isEqualTo(5);

                assertThat(props.getRag().getChunkSize()).isEqualTo(256);
                assertThat(props.getRag().getChunkOverlap()).isEqualTo(25);

                assertThat(props.getSwarm().getMaxTurns()).isEqualTo(20);

                assertThat(props.getTelemetry().isEnabled()).isFalse();

                assertThat(props.getEvals().isEnabled()).isTrue();
                assertThat(props.getEvals().getLlmJudgePassThreshold()).isEqualTo(0.75);

                assertThat(props.getGuardrails().isEnabled()).isTrue();
            });
    }

    @Test
    void defaultPropertiesValues() {
        contextRunner.run(context -> {
            ChorusProperties props = context.getBean(ChorusProperties.class);

            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getLlm().getProvider()).isEqualTo("openai");
            assertThat(props.getLlm().getBaseUrl()).isEqualTo("https://api.openai.com/v1");
            assertThat(props.getLlm().getApiKey()).isEmpty();
            assertThat(props.getLlm().getModel()).isEqualTo("gpt-4o");
            assertThat(props.getLlm().getTemperature()).isEqualTo(0.7);
            assertThat(props.getLlm().getMaxTokens()).isEqualTo(4096);
            assertThat(props.getLlm().getConnectTimeoutSeconds()).isEqualTo(30);
            assertThat(props.getLlm().getMaxRetries()).isEqualTo(3);
            assertThat(props.getLlm().isCircuitBreakerEnabled()).isTrue();

            assertThat(props.getRag().getVectorStoreType()).isEqualTo("memory");
            assertThat(props.getRag().getChunkSize()).isEqualTo(512);
            assertThat(props.getRag().getChunkOverlap()).isEqualTo(50);
            assertThat(props.getRag().getMaxContextTokens()).isEqualTo(2048);
            assertThat(props.getRag().getEmbeddingModel()).isEqualTo("text-embedding-3-small");
            assertThat(props.getRag().getEmbeddingDimensions()).isEqualTo(1536);

            assertThat(props.getAgent().getAgentId()).isEqualTo("chorus-agent");
            assertThat(props.getAgent().getSystemPrompt()).isEqualTo("You are a helpful assistant.");
            assertThat(props.getAgent().getMaxRounds()).isEqualTo(10);

            assertThat(props.getSwarm().getMaxTurns()).isEqualTo(10);
            assertThat(props.getSwarm().isEnableCircuitBreakers()).isTrue();

            assertThat(props.getTelemetry().isEnabled()).isTrue();
            assertThat(props.getTelemetry().isMetricsEnabled()).isTrue();
            assertThat(props.getTelemetry().isStructuredLoggingEnabled()).isTrue();
            assertThat(props.getTelemetry().isOpenTelemetryEnabled()).isFalse();
        });
    }
}
