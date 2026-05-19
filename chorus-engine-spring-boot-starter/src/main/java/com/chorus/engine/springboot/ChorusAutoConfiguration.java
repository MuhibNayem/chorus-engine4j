package com.chorus.engine.springboot;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.llm.embed.OpenAiEmbeddingClient;
import com.chorus.engine.llm.provider.ProviderRegistry;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.rag.chunking.ChunkingStrategy;
import com.chorus.engine.rag.chunking.FixedSizeChunking;
import com.chorus.engine.rag.pipeline.ContextAssembler;
import com.chorus.engine.rag.pipeline.RAGPipeline;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import com.chorus.engine.rag.store.VectorStore;
import com.chorus.engine.rag.store.VectorStoreFactory;
import com.chorus.engine.swarm.SwarmConfig;
import com.chorus.engine.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Spring Boot auto-configuration for Chorus Engine.
 * All beans are conditional — they only instantiate if no user-provided bean exists.
 */
@AutoConfiguration
@EnableConfigurationProperties(ChorusProperties.class)
@ConditionalOnProperty(prefix = "chorus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChorusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper chorusObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpClient chorusHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicy chorusRetryPolicy() {
        return RetryPolicy.DEFAULT;
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreaker chorusCircuitBreaker() {
        return CircuitBreaker.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderRegistry chorusProviderRegistry(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        RetryPolicy retryPolicy,
        CircuitBreaker circuitBreaker
    ) {
        return new ProviderRegistry(httpClient, objectMapper, retryPolicy, circuitBreaker);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public LlmClient llmClient(ProviderRegistry providerRegistry, ChorusProperties props) {
        ChorusProperties.Llm llm = props.getLlm();
        providerRegistry.registerOpenAi(
            llm.getProvider(),
            llm.getBaseUrl(),
            llm.getApiKey(),
            null
        );
        return providerRegistry.get(llm.getProvider());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public EmbeddingClient embeddingClient(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        RetryPolicy retryPolicy,
        ChorusProperties props
    ) {
        ChorusProperties.Llm llm = props.getLlm();
        ChorusProperties.Rag rag = props.getRag();
        return new OpenAiEmbeddingClient(
            llm.getProvider(),
            llm.getBaseUrl(),
            llm.getApiKey(),
            rag.getEmbeddingModel(),
            rag.getEmbeddingDimensions(),
            httpClient,
            objectMapper,
            retryPolicy
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorStore vectorStore(ChorusProperties props) {
        return VectorStoreFactory.create(props.getRag().getVectorStoreType(), props.getRag().getVectorStoreConfig());
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkingStrategy chunkingStrategy(ChorusProperties props) {
        return new FixedSizeChunking(
            props.getRag().getChunkSize(),
            props.getRag().getChunkOverlap()
        );
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
            chunkingStrategy,
            embeddingClient,
            vectorStore,
            keywordIndex,
            null, // QueryTransformer — user can provide if needed
            null, // Reranker — user can provide if needed
            contextAssembler,
            llmClient,
            props.getLlm().getModel()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "chorus.llm", name = "api-key")
    public AgentLoop agentLoop(LlmClient llmClient, ChorusProperties props) {
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
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.chorus.engine.skills.SkillRegistry skillRegistry() {
        return new com.chorus.engine.skills.SkillRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.chorus.engine.skills.SkillRouter skillRouter() {
        return new com.chorus.engine.skills.SkillRouter();
    }

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
    public com.chorus.engine.swarm.SwarmOrchestrator swarmOrchestrator(
        LlmClient llmClient,
        ToolRegistry toolRegistry,
        SwarmConfig swarmConfig,
        ChorusProperties props
    ) {
        var defaultAgent = new com.chorus.engine.swarm.AgentDefinition(
            "default",
            props.getAgent().getSystemPrompt(),
            List.of(),
            props.getLlm().getModel(),
            List.of(),
            Map.of()
        );
        return new com.chorus.engine.swarm.HandoffOrchestrator(
            Map.of("default", defaultAgent),
            llmClient,
            toolRegistry,
            swarmConfig,
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }
}
