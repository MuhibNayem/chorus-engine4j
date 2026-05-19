package com.chorus.engine.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration properties for Chorus Engine.
 */
@ConfigurationProperties(prefix = "chorus")
public class ChorusProperties {

    private boolean enabled = true;
    private Llm llm = new Llm();
    private Rag rag = new Rag();
    private Agent agent = new Agent();
    private Swarm swarm = new Swarm();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Rag getRag() {
        return rag;
    }

    public void setRag(Rag rag) {
        this.rag = rag;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Swarm getSwarm() {
        return swarm;
    }

    public void setSwarm(Swarm swarm) {
        this.swarm = swarm;
    }

    public static class Llm {
        private String provider = "openai";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o";
        private double temperature = 0.7;
        private int maxTokens = 4096;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class Rag {
        private boolean enabled = true;
        private String vectorStoreType = "memory";
        private int chunkSize = 512;
        private int chunkOverlap = 50;
        private int maxContextTokens = 2048;
        private String embeddingModel = "text-embedding-3-small";
        private int embeddingDimensions = 1536;
        private Map<String, Object> vectorStoreConfig = Map.of();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVectorStoreType() {
            return vectorStoreType;
        }

        public void setVectorStoreType(String vectorStoreType) {
            this.vectorStoreType = vectorStoreType;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getMaxContextTokens() {
            return maxContextTokens;
        }

        public void setMaxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public Map<String, Object> getVectorStoreConfig() {
            return vectorStoreConfig;
        }

        public void setVectorStoreConfig(Map<String, Object> vectorStoreConfig) {
            this.vectorStoreConfig = vectorStoreConfig != null ? Map.copyOf(vectorStoreConfig) : Map.of();
        }
    }

    public static class Agent {
        private boolean enabled = true;
        private String agentId = "chorus-agent";
        private String systemPrompt = "You are a helpful assistant.";
        private int maxRounds = 10;
        private int hitlTimeoutMinutes = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        public int getHitlTimeoutMinutes() {
            return hitlTimeoutMinutes;
        }

        public void setHitlTimeoutMinutes(int hitlTimeoutMinutes) {
            this.hitlTimeoutMinutes = hitlTimeoutMinutes;
        }
    }

    public static class Swarm {
        private boolean enabled = true;
        private int maxTurns = 10;
        private long timeoutPerAgentSeconds = 60;
        private boolean enableCircuitBreakers = true;
        private boolean enableCostRouting = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        public long getTimeoutPerAgentSeconds() {
            return timeoutPerAgentSeconds;
        }

        public void setTimeoutPerAgentSeconds(long timeoutPerAgentSeconds) {
            this.timeoutPerAgentSeconds = timeoutPerAgentSeconds;
        }

        public boolean isEnableCircuitBreakers() {
            return enableCircuitBreakers;
        }

        public void setEnableCircuitBreakers(boolean enableCircuitBreakers) {
            this.enableCircuitBreakers = enableCircuitBreakers;
        }

        public boolean isEnableCostRouting() {
            return enableCostRouting;
        }

        public void setEnableCostRouting(boolean enableCostRouting) {
            this.enableCostRouting = enableCostRouting;
        }
    }
}
