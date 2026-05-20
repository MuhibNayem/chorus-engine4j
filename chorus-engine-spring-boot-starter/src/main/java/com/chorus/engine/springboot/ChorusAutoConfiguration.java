package com.chorus.engine.springboot;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.a2a.client.A2aClient;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.checkpoint.InMemoryCheckpointer;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.evals.*;
import com.chorus.engine.graph.checkpoint.GraphCheckpointer;
import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import com.chorus.engine.guardrails.TieredGuardrailEngine;
import com.chorus.engine.guardrails.redaction.PiiRedactionEngine;
import com.chorus.engine.guardrails.tier.*;
import com.chorus.engine.harness.*;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.llm.embed.OpenAiEmbeddingClient;
import com.chorus.engine.llm.provider.ProviderRegistry;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.memory.*;
import com.chorus.engine.memory.checkpoint.JsonCheckpointSerializer;
import com.chorus.engine.memory.hierarchical.*;
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.server.McpServer;
import com.chorus.engine.mcp.server.ServerCapabilities;
import com.chorus.engine.mcp.transport.HttpSseTransport;
import com.chorus.engine.mcp.transport.McpTransport;
import com.chorus.engine.mcp.transport.StdioTransport;
import com.chorus.engine.rag.agentic.AgenticRagOrchestrator;
import com.chorus.engine.rag.chunking.ChunkingStrategy;
import com.chorus.engine.rag.chunking.FixedSizeChunking;
import com.chorus.engine.rag.corrective.CorrectiveRagEngine;
import com.chorus.engine.rag.pipeline.ContextAssembler;
import com.chorus.engine.rag.pipeline.RAGPipeline;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.self.SelfRagEvaluator;
import com.chorus.engine.rag.store.VectorStore;
import com.chorus.engine.rag.store.VectorStoreFactory;
import com.chorus.engine.rag.streaming.*;
import com.chorus.engine.skills.SkillRegistry;
import com.chorus.engine.skills.SkillRouter;
import com.chorus.engine.swarm.AgentDefinition;
import com.chorus.engine.swarm.HandoffOrchestrator;
import com.chorus.engine.swarm.SwarmConfig;
import com.chorus.engine.swarm.SwarmOrchestrator;
import com.chorus.engine.telemetry.event.EventBus;
import com.chorus.engine.telemetry.event.InMemoryEventBus;
import com.chorus.engine.telemetry.logging.StructuredLogger;
import com.chorus.engine.telemetry.metrics.*;
import com.chorus.engine.telemetry.otel.OpenTelemetryBridge;
import com.chorus.engine.telemetry.otel.OtelConfig;
import com.chorus.engine.telemetry.provenance.ProvenanceTracker;
import com.chorus.engine.tools.ToolRegistry;
import com.chorus.engine.springboot.agent.AgentAnnotationProcessor;
import com.chorus.engine.springboot.swarm.SwarmAnnotationProcessor;
import com.chorus.engine.springboot.graph.GraphAnnotationProcessor;
import com.chorus.engine.springboot.mcp.McpAnnotationProcessor;
import com.chorus.engine.springboot.guardrail.GuardrailAnnotationProcessor;
import com.chorus.engine.springboot.skill.SkillAnnotationProcessor;
import com.chorus.engine.springboot.telemetry.EventHandlerAnnotationProcessor;
import com.chorus.engine.springboot.tool.StandaloneToolAnnotationProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enterprise Spring Boot auto-configuration for Chorus Engine.
 *
 * <p>Wires every module with proper conditional logic:
 * <ul>
 *   <li>{@code @ConditionalOnMissingBean} — respects user overrides</li>
 *   <li>{@code @ConditionalOnProperty} — opt-in for advanced features</li>
 *   <li>{@code @ConditionalOnClass} — graceful degradation when optional deps absent</li>
 * </ul>
 *
 * <p>Features disabled by default (enterprise-safe):
 * graph, harness, guardrails, memory, MCP, A2A, evals, advanced RAG.
 * Core features (llm, rag, agent, swarm, skills, telemetry) are enabled by default.
 */
@AutoConfiguration
@EnableConfigurationProperties(ChorusProperties.class)
@ConditionalOnProperty(prefix = "chorus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChorusAutoConfiguration {

    // ================================================================
    // CORE INFRASTRUCTURE
    // ================================================================

    @Bean
    @ConditionalOnMissingBean(name = "chorusObjectMapper")
    public ObjectMapper chorusObjectMapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    @Bean
    @ConditionalOnMissingBean(name = "chorusHttpClient")
    public HttpClient chorusHttpClient(ChorusProperties props) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(props.getLlm().getConnectTimeoutSeconds()))
            .version(HttpClient.Version.HTTP_2)
            .build();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "chorusExecutor")
    public ExecutorService chorusExecutor(ChorusProperties props) {
        if (props.getThreadPool().isEnabled()) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        return Executors.newCachedThreadPool();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicy chorusRetryPolicy(ChorusProperties props) {
        return new RetryPolicy(
            props.getLlm().getMaxRetries(),
            Duration.ofMillis(500),
            Duration.ofSeconds(30),
            0.2,
            Set.of(429, 500, 502, 503, 504),
            Set.of("rate_limit", "timeout", "server_error"),
            Duration.ofSeconds(60)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreaker chorusCircuitBreaker(ChorusProperties props) {
        if (!props.getLlm().isCircuitBreakerEnabled()) {
            // No-op circuit breaker: never opens
            return new CircuitBreaker(Integer.MAX_VALUE, Duration.ofDays(365));
        }
        return CircuitBreaker.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderRegistry chorusProviderRegistry(
        HttpClient chorusHttpClient,
        ObjectMapper chorusObjectMapper,
        RetryPolicy chorusRetryPolicy,
        CircuitBreaker chorusCircuitBreaker
    ) {
        return new ProviderRegistry(chorusHttpClient, chorusObjectMapper, chorusRetryPolicy, chorusCircuitBreaker);
    }

    // ================================================================
    // LLM LAYER
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public LlmClient llmClient(ProviderRegistry providerRegistry, ChorusProperties props) {
        ChorusProperties.Llm llm = props.getLlm();
        providerRegistry.registerOpenAi(llm.getProvider(), llm.getBaseUrl(), llm.getApiKey(), null);
        return providerRegistry.get(llm.getProvider());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public EmbeddingClient embeddingClient(
        HttpClient chorusHttpClient,
        ObjectMapper chorusObjectMapper,
        RetryPolicy chorusRetryPolicy,
        ChorusProperties props
    ) {
        ChorusProperties.Llm llm = props.getLlm();
        ChorusProperties.Rag rag = props.getRag();
        return new OpenAiEmbeddingClient(
            llm.getProvider(), llm.getBaseUrl(), llm.getApiKey(),
            rag.getEmbeddingModel(), rag.getEmbeddingDimensions(),
            chorusHttpClient, chorusObjectMapper, chorusRetryPolicy
        );
    }

    // ================================================================
    // RAG LAYER
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    public VectorStore vectorStore(ChorusProperties props) {
        return VectorStoreFactory.create(props.getRag().getVectorStoreType(), props.getRag().getVectorStoreConfig());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkingStrategy chunkingStrategy(ChorusProperties props) {
        return new FixedSizeChunking(props.getRag().getChunkSize(), props.getRag().getChunkOverlap());
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextAssembler contextAssembler(ChorusProperties props) {
        return new ContextAssembler(props.getRag().getMaxContextTokens());
    }

    @Bean
    @ConditionalOnMissingBean
    public HybridRetrievalEngine.KeywordIndex keywordIndex() {
        return new InMemoryKeywordIndex();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public RAGPipeline ragPipeline(
        ChunkingStrategy chunkingStrategy,
        EmbeddingClient embeddingClient,
        VectorStore vectorStore,
        HybridRetrievalEngine.KeywordIndex keywordIndex,
        ContextAssembler contextAssembler,
        LlmClient llmClient,
        ChorusProperties props
    ) {
        return new RAGPipeline(
            chunkingStrategy, embeddingClient, vectorStore, keywordIndex,
            null, null, contextAssembler, llmClient, props.getLlm().getModel()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public RetrievalEngine retrievalEngine(
        VectorStore vectorStore,
        EmbeddingClient embeddingClient,
        HybridRetrievalEngine.KeywordIndex keywordIndex
    ) {
        return new HybridRetrievalEngine(vectorStore, embeddingClient, keywordIndex, 20, 20, 60.0);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.rag", name = "incremental-streaming-enabled", havingValue = "true")
    public IncrementalRAGStreamer incrementalRAGStreamer(
        LlmClient llmClient,
        ChorusProperties props
    ) {
        ChorusProperties.Rag rag = props.getRag();
        GenerationStrategy strategy = GenerationStrategy.valueOf(rag.getIncrementalStrategy().toUpperCase());
        return new IncrementalRAGStreamer(
            List.of(), // stages — user provides or empty default
            llmClient,
            props.getLlm().getModel(),
            props.getLlm().getTemperature(),
            rag.getIncrementalMaxGenerationTokens(),
            rag.getMaxContextTokens(),
            strategy,
            AdaptiveThreshold.defaults(),
            null,
            Duration.ofSeconds(rag.getIncrementalMaxLatencySeconds())
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.rag", name = "corrective-rag-enabled", havingValue = "true")
    public CorrectiveRagEngine correctiveRagEngine(
        LlmClient llmClient,
        RetrievalEngine retrievalEngine,
        ChorusProperties props
    ) {
        return new CorrectiveRagEngine(
            llmClient, props.getLlm().getModel(),
            retrievalEngine, props.getRag().getCorrectiveRagThreshold()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.rag", name = "agentic-rag-enabled", havingValue = "true")
    public AgenticRagOrchestrator agenticRagOrchestrator(
        LlmClient llmClient,
        VectorStore vectorStore,
        RetrievalEngine retrievalEngine,
        ChorusProperties props
    ) {
        return new AgenticRagOrchestrator(
            llmClient, props.getLlm().getModel(),
            vectorStore, retrievalEngine, props.getRag().getAgenticRagMaxIterations()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.rag", name = "self-rag-enabled", havingValue = "true")
    public SelfRagEvaluator selfRagEvaluator(
        LlmClient llmClient,
        RetrievalEngine retrievalEngine,
        ChorusProperties props
    ) {
        return new SelfRagEvaluator(
            llmClient, props.getLlm().getModel(), retrievalEngine,
            props.getRag().getSelfRagMaxRefinements(),
            props.getRag().getSelfRagRelevanceThreshold()
        );
    }

    // ================================================================
    // AGENT LAYER
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public AgentLoop agentLoop(
        LlmClient llmClient,
        ExecutorService chorusExecutor,
        ChorusProperties props
    ) {
        return new AgentLoop(
            props.getAgent().getAgentId(),
            props.getAgent().getSystemPrompt(),
            llmClient,
            props.getLlm().getModel(),
            props.getLlm().getTemperature(),
            props.getLlm().getMaxTokens(),
            props.getAgent().getMaxRounds(),
            List.of(),
            null,
            chorusExecutor
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static AgentAnnotationProcessor agentAnnotationProcessor() {
        return new AgentAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static SwarmAnnotationProcessor swarmAnnotationProcessor() {
        return new SwarmAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static GraphAnnotationProcessor graphAnnotationProcessor() {
        return new GraphAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static McpAnnotationProcessor mcpAnnotationProcessor() {
        return new McpAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static GuardrailAnnotationProcessor guardrailAnnotationProcessor() {
        return new GuardrailAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static SkillAnnotationProcessor skillAnnotationProcessor() {
        return new SkillAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static EventHandlerAnnotationProcessor eventHandlerAnnotationProcessor() {
        return new EventHandlerAnnotationProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus", name = "annotations.enabled", havingValue = "true", matchIfMissing = true)
    public static StandaloneToolAnnotationProcessor standaloneToolAnnotationProcessor() {
        return new StandaloneToolAnnotationProcessor();
    }


    // ================================================================
    // SWARM LAYER
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    public SwarmConfig swarmConfig(ChorusProperties props) {
        return new SwarmConfig(
            props.getSwarm().getMaxTurns(),
            Duration.ofSeconds(props.getSwarm().getTimeoutPerAgentSeconds()),
            props.getSwarm().isEnableCircuitBreakers(),
            props.getSwarm().isEnableCostRouting()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public SwarmOrchestrator swarmOrchestrator(
        LlmClient llmClient,
        ToolRegistry toolRegistry,
        SwarmConfig swarmConfig,
        ExecutorService chorusExecutor,
        ChorusProperties props
    ) {
        AgentDefinition defaultAgent = new AgentDefinition(
            "default", props.getAgent().getSystemPrompt(),
            List.of(), props.getLlm().getModel(), List.of(), Map.of()
        );
        return new HandoffOrchestrator(
            Map.of("default", defaultAgent),
            llmClient, toolRegistry, swarmConfig, chorusExecutor
        );
    }

    // ================================================================
    // SKILLS LAYER
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRouter skillRouter() {
        return new SkillRouter();
    }

    // ================================================================
    // GRAPH LAYER (opt-in)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.graph", name = "enabled", havingValue = "true")
    static class GraphConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public GraphCheckpointer<Map<String, Object>> graphCheckpointer(
            ChorusProperties props,
            ObjectMapper chorusObjectMapper
        ) {
            String type = props.getGraph().getCheckpointType();
            Checkpointer delegate = switch (type) {
                case "jdbc" -> throw new IllegalStateException(
                    "JDBC checkpointing requires DataSource bean. " +
                    "Define a JdbcCheckpointer bean manually or add chorus.graph.checkpoint-type=jdbc with DataSource on classpath."
                );
                case "redis" -> throw new IllegalStateException(
                    "Redis checkpointing requires JedisPool bean. Define a RedisCheckpointer bean manually."
                );
                default -> new InMemoryCheckpointer();
            };
            return GraphCheckpointer.ofMap(delegate);
        }
    }

    // ================================================================
    // HARNESS LAYER (opt-in)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.harness", name = "enabled", havingValue = "true")
    static class HarnessConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public HarnessConfig harnessConfig(ChorusProperties props) {
            ChorusProperties.Harness h = props.getHarness();
            return HarnessConfig.builder()
                .projectMemoryPath(h.getProjectMemoryPath())
                .approvalLogPath(h.getApprovalLogPath())
                .trajectoryLogPath(h.getTrajectoryLogPath())
                .maxConcurrentWorkers(h.getMaxConcurrentWorkers())
                .workerTimeout(Duration.ofSeconds(h.getWorkerTimeoutSeconds()))
                .taskTimeout(Duration.ofSeconds(h.getTaskTimeoutSeconds()))
                .enableSemanticRouting(h.isEnableSemanticRouting())
                .enableSafetyAudit(h.isEnableSafetyAudit())
                .enableTimeTravel(h.isEnableTimeTravel())
                .enableResultCache(h.isEnableResultCache())
                .semanticConfidenceThreshold(h.getSemanticConfidenceThreshold())
                .defaultApprovalPolicy(ApprovalPolicy.valueOf(h.getDefaultApprovalPolicy()))
                .build();
        }

        @Bean
        @ConditionalOnMissingBean
        public WorkerPool workerPool() {
            return new WorkerPool();
        }

        @Bean
        @ConditionalOnMissingBean
        public ProjectMemoryStore projectMemoryStore(ChorusProperties props) {
            return new ProjectMemoryStore(
                props.getHarness().getProjectMemoryPath(),
                "default"
            );
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
        public SemanticTaskRouter semanticTaskRouter(
            EmbeddingClient embeddingClient,
            ChorusProperties props
        ) {
            return new SemanticTaskRouter(
                embeddingClient,
                VectorOperations.autoDetect(),
                props.getHarness().getSemanticConfidenceThreshold()
            );
        }
    }

    // ================================================================
    // GUARDRAILS LAYER (opt-in)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.guardrails", name = "enabled", havingValue = "true")
    static class GuardrailsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public PiiRedactionEngine piiRedactionEngine(ChorusProperties props) {
            if (props.getGuardrails().isPiiRedactionEnabled()) {
                return new PiiRedactionEngine();
            }
            return null;
        }

        @Bean
        @ConditionalOnMissingBean
        public List<Guardrail> guardrails(
            ChorusProperties props,
            LlmClient llmClient,
            EmbeddingClient embeddingClient
        ) {
            ChorusProperties.Guardrails g = props.getGuardrails();
            var builder = new java.util.ArrayList<Guardrail>();

            for (ChorusProperties.Guardrails.RegexRule rule : g.getRegexRules()) {
                GuardrailResult.Action action = "REDACT".equalsIgnoreCase(rule.getAction())
                    ? GuardrailResult.Action.REDACT : GuardrailResult.Action.BLOCK;
                builder.add(new RegexGuardrail(
                    rule.getName(),
                    Pattern.compile(rule.getPattern()),
                    action,
                    1.0
                ));
            }

            for (ChorusProperties.Guardrails.KeywordRule rule : g.getKeywordRules()) {
                GuardrailResult.Action action = "REDACT".equalsIgnoreCase(rule.getAction())
                    ? GuardrailResult.Action.REDACT : GuardrailResult.Action.BLOCK;
                builder.add(new KeywordGuardrail(
                    rule.getName(),
                    Set.copyOf(rule.getKeywords()),
                    action
                ));
            }

            if (g.isLlmJudgeEnabled()) {
                builder.add(new LlmJudgeGuardrail(
                    "llm-judge",
                    g.getLlmJudgePolicy(),
                    g.getLlmJudgeBlockThreshold(),
                    (input, policy) -> {
                        // Adapter: LlmClient -> LlmJudgeClient
                        String prompt = "Evaluate whether the following input violates this policy. " +
                            "Policy: " + policy + "\nInput: " + input + "\n" +
                            "Respond ONLY with JSON: {\"violatesPolicy\": true/false, \"confidence\": 0.0-1.0, \"reasoning\": \"...\"}";
                        try {
                            var request = new com.chorus.engine.llm.ChatRequest(
                                props.getLlm().getModel(),
                                List.of(com.chorus.engine.core.context.Message.user(prompt)),
                                List.of(),
                                0.0,
                                256,
                                null,
                                null,
                                null
                            );
                            var result = llmClient.complete(request, CancellationToken.create());
                            String text = result.message().content().toLowerCase(Locale.ROOT);
                            boolean violates = text.contains("\"violatespolicy\": true") || text.contains("violates");
                            double confidence = violates ? g.getLlmJudgeBlockThreshold() : 0.1;
                            return new LlmJudgeGuardrail.LlmJudgeClient.JudgeResult(violates, confidence, result.message().content());
                        } catch (Exception e) {
                            return new LlmJudgeGuardrail.LlmJudgeClient.JudgeResult(false, 0.0, "Judge failed: " + e.getMessage());
                        }
                    },
                    100
                ));
            }

            if (g.isEmbeddingSimilarityEnabled()) {
                builder.add(new EmbeddingSimilarityGuardrail(
                    "embedding-similarity",
                    List.of(), // forbidden embeddings — user must configure manually
                    g.getEmbeddingSimilarityThreshold(),
                    embeddingClient,
                    props.getRag().getEmbeddingModel(),
                    1000
                ));
            }

            return List.copyOf(builder);
        }

        @Bean
        @ConditionalOnMissingBean
        public TieredGuardrailEngine tieredGuardrailEngine(
            List<Guardrail> guardrails,
            ExecutorService chorusExecutor,
            ChorusProperties props
        ) {
            ChorusProperties.Guardrails g = props.getGuardrails();
            return new TieredGuardrailEngine(
                guardrails,
                chorusExecutor,
                Duration.ofMillis(g.getTier2TimeoutMillis()),
                Duration.ofMillis(g.getTier3TimeoutMillis())
            );
        }
    }

    // ================================================================
    // TELEMETRY LAYER (enabled by default)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.telemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class TelemetryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EventBus eventBus() {
            return new InMemoryEventBus();
        }

        @Bean
        @ConditionalOnMissingBean
        public MetricsCollector metricsCollector(ChorusProperties props) {
            return new MetricsCollector(props.getTelemetry().getMaxHistogramSize());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "chorus.telemetry", name = "cost-tracking-enabled", havingValue = "true")
        public CostTracker costTracker() {
            return new CostTracker(PricingTable.defaults());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnExpression("${chorus.telemetry.budget-limit:-1} >= 0")
        public BudgetEnforcer budgetEnforcer(ChorusProperties props) {
            return new BudgetEnforcer(props.getTelemetry().getBudgetLimit());
        }

        @Bean
        @ConditionalOnMissingBean
        public ProvenanceTracker provenanceTracker(ChorusProperties props) {
            return new ProvenanceTracker(props.getTelemetry().getProvenanceMaxEntries());
        }

        @Bean
        @ConditionalOnMissingBean
        public StructuredLogger structuredLogger() {
            return new StructuredLogger();
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "io.opentelemetry.api.trace.Tracer")
        @ConditionalOnProperty(prefix = "chorus.telemetry", name = "open-telemetry-enabled", havingValue = "true")
        public OpenTelemetryBridge openTelemetryBridge(EventBus eventBus, ChorusProperties props) {
            OtelConfig config = new OtelConfig(
                props.getTelemetry().getOpenTelemetryEndpoint(),
                props.getTelemetry().getOpenTelemetryHeaders(),
                props.getTelemetry().getOpenTelemetrySamplingRate()
            );
            return new OpenTelemetryBridge(eventBus, config);
        }
    }

    // ================================================================
    // MEMORY LAYER (opt-in)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.memory", name = "enabled", havingValue = "true")
    static class MemoryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ShortTermMemory shortTermMemory(ChorusProperties props) {
            return new ShortTermMemory(
                props.getMemory().getShortTermMaxTokens(),
                props.getMemory().getShortTermMaxMessages()
            );
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
        public LongTermMemory longTermMemory(
            EmbeddingClient embeddingClient,
            ChorusProperties props
        ) {
            return new LongTermMemory(
                embeddingClient,
                props.getRag().getEmbeddingModel(),
                1.2, 0.75,
                props.getMemory().getLongTermBm25Weight(),
                props.getMemory().getLongTermSemanticWeight()
            );
        }

        @Bean
        @ConditionalOnMissingBean
        public ContextCompactor contextCompactor(ChorusProperties props) {
            return new ContextCompactor(props.getMemory().getCompactionTargetTokens());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "chorus.memory", name = "hierarchical-enabled", havingValue = "true")
        public HierarchicalMemoryManager hierarchicalMemoryManager(
            ShortTermMemory shortTermMemory,
            LongTermMemory longTermMemory,
            ChorusProperties props
        ) {
            return new HierarchicalMemoryManager(
                shortTermMemory,
                new EpisodicMemory(1000, 30),
                longTermMemory,
                new ProceduralMemory(500)
            );
        }

        @Bean
        @ConditionalOnMissingBean(name = "chorusCheckpointer")
        @ConditionalOnProperty(prefix = "chorus.memory", name = "checkpointing-enabled", havingValue = "true")
        public Checkpointer chorusCheckpointer(ChorusProperties props, ObjectMapper chorusObjectMapper) {
            String type = props.getMemory().getCheckpointType();
            JsonCheckpointSerializer serializer = new JsonCheckpointSerializer(chorusObjectMapper);
            return switch (type) {
                case "jdbc" -> throw new IllegalStateException(
                    "JDBC checkpointing requires DataSource bean. Define a JdbcCheckpointer bean manually."
                );
                case "redis" -> throw new IllegalStateException(
                    "Redis checkpointing requires JedisPool bean. Define a RedisCheckpointer bean manually."
                );
                default -> new InMemoryCheckpointer();
            };
        }
    }

    // ================================================================
    // JDBC CHECKPOINTER (conditional on DataSource)
    // ================================================================

    @Configuration
    @ConditionalOnClass(DataSource.class)
    @ConditionalOnProperty(prefix = "chorus.memory", name = "checkpointing-enabled", havingValue = "true")
    static class JdbcCheckpointerConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "jdbcCheckpointer")
        @ConditionalOnProperty(prefix = "chorus.memory", name = "checkpoint-type", havingValue = "jdbc")
        public com.chorus.engine.memory.checkpoint.JdbcCheckpointer jdbcCheckpointer(
            DataSource dataSource,
            ChorusProperties props,
            ObjectMapper chorusObjectMapper
        ) {
            return new com.chorus.engine.memory.checkpoint.JdbcCheckpointer(
                dataSource,
                props.getMemory().getCheckpointJdbcTable(),
                new JsonCheckpointSerializer(chorusObjectMapper)
            );
        }
    }

    // ================================================================
    // MCP LAYER (opt-in)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.mcp", name = "enabled", havingValue = "true")
    static class McpConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public McpTransport mcpTransport(ChorusProperties props, ObjectMapper chorusObjectMapper, HttpClient chorusHttpClient) {
            ChorusProperties.Mcp mcp = props.getMcp();
            return switch (mcp.getTransportType()) {
                case "stdio" -> new StdioTransport(mcp.getStdioCommand(), chorusObjectMapper);
                default -> new HttpSseTransport(URI.create(mcp.getHttpSseEndpoint()), chorusHttpClient, chorusObjectMapper);
            };
        }

        @Bean
        @ConditionalOnMissingBean
        public McpClient mcpClient(McpTransport mcpTransport, ObjectMapper chorusObjectMapper) {
            return new McpClient(mcpTransport, chorusObjectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "chorus.mcp", name = "server-enabled", havingValue = "true")
        public McpServer mcpServer(McpTransport mcpTransport, ChorusProperties props, ObjectMapper chorusObjectMapper) {
            ChorusProperties.Mcp mcp = props.getMcp();
            ServerCapabilities capabilities = new ServerCapabilities(
                mcp.isServerTools(),
                mcp.isServerResources(),
                mcp.isServerPrompts(),
                mcp.isServerLogging(),
                mcp.isServerCompletion()
            );
            return new McpServer(mcpTransport, capabilities, chorusObjectMapper);
        }
    }

    // ================================================================
    // A2A LAYER (opt-in)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.a2a", name = "enabled", havingValue = "true")
    static class A2aConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public A2aClient a2aClient(HttpClient chorusHttpClient, ChorusProperties props) {
            return new A2aClient(
                chorusHttpClient,
                props.getA2a().getBaseUrl(),
                props.getA2a().getAuthToken().isEmpty() ? null : props.getA2a().getAuthToken()
            );
        }
    }

    // ================================================================
    // EVALS LAYER (opt-in)
    // ================================================================

    @Configuration
    @ConditionalOnProperty(prefix = "chorus.evals", name = "enabled", havingValue = "true")
    static class EvalsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EvalRunner evalRunner(ChorusProperties props) {
            return new EvalRunner("spring-boot-runner");
        }

        @Bean
        @ConditionalOnMissingBean
        public ParallelEvalRunner parallelEvalRunner(ChorusProperties props) {
            int concurrency = props.getEvals().getParallelMaxConcurrency();
            if (concurrency <= 0) {
                concurrency = Runtime.getRuntime().availableProcessors();
            }
            return new ParallelEvalRunner("spring-boot-parallel", concurrency);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
        public LlmJudgeScorer llmJudgeScorer(LlmClient llmClient, ChorusProperties props) {
            return new LlmJudgeScorer(
                llmClient, props.getLlm().getModel(),
                props.getEvals().getLlmJudgePassThreshold()
            );
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
        public SemanticSimilarityScorer semanticSimilarityScorer(
            EmbeddingClient embeddingClient,
            ChorusProperties props
        ) {
            return new SemanticSimilarityScorer(
                embeddingClient,
                props.getEvals().getSemanticSimilarityThreshold(),
                props.getRag().getEmbeddingModel()
            );
        }
    }
}
