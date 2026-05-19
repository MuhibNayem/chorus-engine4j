package com.chorus.engine.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Enterprise configuration properties for Chorus Engine.
 * Every module has a dedicated section for zero-conf or fine-grained tuning.
 */
@ConfigurationProperties(prefix = "chorus")
public class ChorusProperties {

    private boolean enabled = true;
    private ThreadPool threadPool = new ThreadPool();
    private Llm llm = new Llm();
    private Rag rag = new Rag();
    private Agent agent = new Agent();
    private Swarm swarm = new Swarm();
    private Graph graph = new Graph();
    private Harness harness = new Harness();
    private Guardrails guardrails = new Guardrails();
    private Telemetry telemetry = new Telemetry();
    private Memory memory = new Memory();
    private Mcp mcp = new Mcp();
    private A2a a2a = new A2a();
    private Evals evals = new Evals();
    private Enterprise enterprise = new Enterprise();

    // ------------------------------------------------------------------
    // Getters / Setters
    // ------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public ThreadPool getThreadPool() { return threadPool; }
    public void setThreadPool(ThreadPool threadPool) { this.threadPool = threadPool; }

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Rag getRag() { return rag; }
    public void setRag(Rag rag) { this.rag = rag; }

    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }

    public Swarm getSwarm() { return swarm; }
    public void setSwarm(Swarm swarm) { this.swarm = swarm; }

    public Graph getGraph() { return graph; }
    public void setGraph(Graph graph) { this.graph = graph; }

    public Harness getHarness() { return harness; }
    public void setHarness(Harness harness) { this.harness = harness; }

    public Guardrails getGuardrails() { return guardrails; }
    public void setGuardrails(Guardrails guardrails) { this.guardrails = guardrails; }

    public Telemetry getTelemetry() { return telemetry; }
    public void setTelemetry(Telemetry telemetry) { this.telemetry = telemetry; }

    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }

    public Mcp getMcp() { return mcp; }
    public void setMcp(Mcp mcp) { this.mcp = mcp; }

    public A2a getA2a() { return a2a; }
    public void setA2a(A2a a2a) { this.a2a = a2a; }

    public Evals getEvals() { return evals; }
    public void setEvals(Evals evals) { this.evals = evals; }

    public Enterprise getEnterprise() { return enterprise; }
    public void setEnterprise(Enterprise enterprise) { this.enterprise = enterprise; }

    // ------------------------------------------------------------------
    // Nested property classes
    // ------------------------------------------------------------------

    public static class ThreadPool {
        private boolean enabled = true;
        private int maxVirtualThreads = 10_000;
        private String namePrefix = "chorus-";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxVirtualThreads() { return maxVirtualThreads; }
        public void setMaxVirtualThreads(int maxVirtualThreads) { this.maxVirtualThreads = maxVirtualThreads; }
        public String getNamePrefix() { return namePrefix; }
        public void setNamePrefix(String namePrefix) { this.namePrefix = namePrefix; }
    }

    public static class Llm {
        private boolean enabled = true;
        private String provider = "openai";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o";
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private int connectTimeoutSeconds = 30;
        private int readTimeoutSeconds = 120;
        private int maxRetries = 3;
        private boolean circuitBreakerEnabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public boolean isCircuitBreakerEnabled() { return circuitBreakerEnabled; }
        public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) { this.circuitBreakerEnabled = circuitBreakerEnabled; }
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

        // Advanced RAG
        private boolean agenticRagEnabled = false;
        private int agenticRagMaxIterations = 5;
        private boolean correctiveRagEnabled = false;
        private double correctiveRagThreshold = 0.7;
        private boolean selfRagEnabled = false;
        private int selfRagMaxRefinements = 3;
        private double selfRagRelevanceThreshold = 0.6;
        private boolean incrementalStreamingEnabled = false;
        private String incrementalStrategy = "ADAPTIVE"; // WAIT_FOR_ALL, PIPELINE, ADAPTIVE
        private int incrementalMaxGenerationTokens = 2048;
        private int incrementalMaxLatencySeconds = 30;

        // Enterprise
        private boolean tenantIsolationEnabled = false;
        private String tenantIsolationLevel = "SOFT"; // SOFT, HARD

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getVectorStoreType() { return vectorStoreType; }
        public void setVectorStoreType(String vectorStoreType) { this.vectorStoreType = vectorStoreType; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getEmbeddingDimensions() { return embeddingDimensions; }
        public void setEmbeddingDimensions(int embeddingDimensions) { this.embeddingDimensions = embeddingDimensions; }
        public Map<String, Object> getVectorStoreConfig() { return vectorStoreConfig; }
        public void setVectorStoreConfig(Map<String, Object> vectorStoreConfig) { this.vectorStoreConfig = vectorStoreConfig != null ? Map.copyOf(vectorStoreConfig) : Map.of(); }

        public boolean isAgenticRagEnabled() { return agenticRagEnabled; }
        public void setAgenticRagEnabled(boolean agenticRagEnabled) { this.agenticRagEnabled = agenticRagEnabled; }
        public int getAgenticRagMaxIterations() { return agenticRagMaxIterations; }
        public void setAgenticRagMaxIterations(int agenticRagMaxIterations) { this.agenticRagMaxIterations = agenticRagMaxIterations; }
        public boolean isCorrectiveRagEnabled() { return correctiveRagEnabled; }
        public void setCorrectiveRagEnabled(boolean correctiveRagEnabled) { this.correctiveRagEnabled = correctiveRagEnabled; }
        public double getCorrectiveRagThreshold() { return correctiveRagThreshold; }
        public void setCorrectiveRagThreshold(double correctiveRagThreshold) { this.correctiveRagThreshold = correctiveRagThreshold; }
        public boolean isSelfRagEnabled() { return selfRagEnabled; }
        public void setSelfRagEnabled(boolean selfRagEnabled) { this.selfRagEnabled = selfRagEnabled; }
        public int getSelfRagMaxRefinements() { return selfRagMaxRefinements; }
        public void setSelfRagMaxRefinements(int selfRagMaxRefinements) { this.selfRagMaxRefinements = selfRagMaxRefinements; }
        public double getSelfRagRelevanceThreshold() { return selfRagRelevanceThreshold; }
        public void setSelfRagRelevanceThreshold(double selfRagRelevanceThreshold) { this.selfRagRelevanceThreshold = selfRagRelevanceThreshold; }
        public boolean isIncrementalStreamingEnabled() { return incrementalStreamingEnabled; }
        public void setIncrementalStreamingEnabled(boolean incrementalStreamingEnabled) { this.incrementalStreamingEnabled = incrementalStreamingEnabled; }
        public String getIncrementalStrategy() { return incrementalStrategy; }
        public void setIncrementalStrategy(String incrementalStrategy) { this.incrementalStrategy = incrementalStrategy; }
        public int getIncrementalMaxGenerationTokens() { return incrementalMaxGenerationTokens; }
        public void setIncrementalMaxGenerationTokens(int incrementalMaxGenerationTokens) { this.incrementalMaxGenerationTokens = incrementalMaxGenerationTokens; }
        public int getIncrementalMaxLatencySeconds() { return incrementalMaxLatencySeconds; }
        public void setIncrementalMaxLatencySeconds(int incrementalMaxLatencySeconds) { this.incrementalMaxLatencySeconds = incrementalMaxLatencySeconds; }

        public boolean isTenantIsolationEnabled() { return tenantIsolationEnabled; }
        public void setTenantIsolationEnabled(boolean tenantIsolationEnabled) { this.tenantIsolationEnabled = tenantIsolationEnabled; }
        public String getTenantIsolationLevel() { return tenantIsolationLevel; }
        public void setTenantIsolationLevel(String tenantIsolationLevel) { this.tenantIsolationLevel = tenantIsolationLevel; }
    }

    public static class Agent {
        private boolean enabled = true;
        private String agentId = "chorus-agent";
        private String systemPrompt = "You are a helpful assistant.";
        private int maxRounds = 10;
        private int hitlTimeoutMinutes = 5;
        private boolean selfHealingEnabled = false;
        private int selfHealingMaxRetries = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public int getMaxRounds() { return maxRounds; }
        public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
        public int getHitlTimeoutMinutes() { return hitlTimeoutMinutes; }
        public void setHitlTimeoutMinutes(int hitlTimeoutMinutes) { this.hitlTimeoutMinutes = hitlTimeoutMinutes; }
        public boolean isSelfHealingEnabled() { return selfHealingEnabled; }
        public void setSelfHealingEnabled(boolean selfHealingEnabled) { this.selfHealingEnabled = selfHealingEnabled; }
        public int getSelfHealingMaxRetries() { return selfHealingMaxRetries; }
        public void setSelfHealingMaxRetries(int selfHealingMaxRetries) { this.selfHealingMaxRetries = selfHealingMaxRetries; }
    }

    public static class Swarm {
        private boolean enabled = true;
        private int maxTurns = 10;
        private long timeoutPerAgentSeconds = 60;
        private boolean enableCircuitBreakers = true;
        private boolean enableCostRouting = false;
        private boolean consensusEnabled = false;
        private int consensusMinAgents = 3;
        private double consensusThreshold = 0.66;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public long getTimeoutPerAgentSeconds() { return timeoutPerAgentSeconds; }
        public void setTimeoutPerAgentSeconds(long timeoutPerAgentSeconds) { this.timeoutPerAgentSeconds = timeoutPerAgentSeconds; }
        public boolean isEnableCircuitBreakers() { return enableCircuitBreakers; }
        public void setEnableCircuitBreakers(boolean enableCircuitBreakers) { this.enableCircuitBreakers = enableCircuitBreakers; }
        public boolean isEnableCostRouting() { return enableCostRouting; }
        public void setEnableCostRouting(boolean enableCostRouting) { this.enableCostRouting = enableCostRouting; }
        public boolean isConsensusEnabled() { return consensusEnabled; }
        public void setConsensusEnabled(boolean consensusEnabled) { this.consensusEnabled = consensusEnabled; }
        public int getConsensusMinAgents() { return consensusMinAgents; }
        public void setConsensusMinAgents(int consensusMinAgents) { this.consensusMinAgents = consensusMinAgents; }
        public double getConsensusThreshold() { return consensusThreshold; }
        public void setConsensusThreshold(double consensusThreshold) { this.consensusThreshold = consensusThreshold; }
    }

    public static class Graph {
        private boolean enabled = false;
        private boolean speculativeExecutionEnabled = false;
        private double speculativeHitRateThreshold = 0.8;
        private int speculativeMaxIterations = 100;
        private boolean checkpointingEnabled = true;
        private String checkpointType = "memory"; // memory, jdbc, redis
        private boolean hitlInterruptEnabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isSpeculativeExecutionEnabled() { return speculativeExecutionEnabled; }
        public void setSpeculativeExecutionEnabled(boolean speculativeExecutionEnabled) { this.speculativeExecutionEnabled = speculativeExecutionEnabled; }
        public double getSpeculativeHitRateThreshold() { return speculativeHitRateThreshold; }
        public void setSpeculativeHitRateThreshold(double speculativeHitRateThreshold) { this.speculativeHitRateThreshold = speculativeHitRateThreshold; }
        public int getSpeculativeMaxIterations() { return speculativeMaxIterations; }
        public void setSpeculativeMaxIterations(int speculativeMaxIterations) { this.speculativeMaxIterations = speculativeMaxIterations; }
        public boolean isCheckpointingEnabled() { return checkpointingEnabled; }
        public void setCheckpointingEnabled(boolean checkpointingEnabled) { this.checkpointingEnabled = checkpointingEnabled; }
        public String getCheckpointType() { return checkpointType; }
        public void setCheckpointType(String checkpointType) { this.checkpointType = checkpointType; }
        public boolean isHitlInterruptEnabled() { return hitlInterruptEnabled; }
        public void setHitlInterruptEnabled(boolean hitlInterruptEnabled) { this.hitlInterruptEnabled = hitlInterruptEnabled; }
    }

    public static class Harness {
        private boolean enabled = false;
        private Path projectMemoryPath = Path.of(".chorus", "memory");
        private Path approvalLogPath = Path.of(".chorus", "approvals");
        private Path trajectoryLogPath = Path.of(".chorus", "trajectory");
        private int maxConcurrentWorkers = 8;
        private long workerTimeoutSeconds = 300;
        private long taskTimeoutSeconds = 600;
        private boolean enableSemanticRouting = true;
        private boolean enableSafetyAudit = true;
        private boolean enableTimeTravel = true;
        private boolean enableResultCache = true;
        private double semanticConfidenceThreshold = 0.75;
        private String defaultApprovalPolicy = "SUGGEST";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Path getProjectMemoryPath() { return projectMemoryPath; }
        public void setProjectMemoryPath(Path projectMemoryPath) { this.projectMemoryPath = projectMemoryPath; }
        public Path getApprovalLogPath() { return approvalLogPath; }
        public void setApprovalLogPath(Path approvalLogPath) { this.approvalLogPath = approvalLogPath; }
        public Path getTrajectoryLogPath() { return trajectoryLogPath; }
        public void setTrajectoryLogPath(Path trajectoryLogPath) { this.trajectoryLogPath = trajectoryLogPath; }
        public int getMaxConcurrentWorkers() { return maxConcurrentWorkers; }
        public void setMaxConcurrentWorkers(int maxConcurrentWorkers) { this.maxConcurrentWorkers = maxConcurrentWorkers; }
        public long getWorkerTimeoutSeconds() { return workerTimeoutSeconds; }
        public void setWorkerTimeoutSeconds(long workerTimeoutSeconds) { this.workerTimeoutSeconds = workerTimeoutSeconds; }
        public long getTaskTimeoutSeconds() { return taskTimeoutSeconds; }
        public void setTaskTimeoutSeconds(long taskTimeoutSeconds) { this.taskTimeoutSeconds = taskTimeoutSeconds; }
        public boolean isEnableSemanticRouting() { return enableSemanticRouting; }
        public void setEnableSemanticRouting(boolean enableSemanticRouting) { this.enableSemanticRouting = enableSemanticRouting; }
        public boolean isEnableSafetyAudit() { return enableSafetyAudit; }
        public void setEnableSafetyAudit(boolean enableSafetyAudit) { this.enableSafetyAudit = enableSafetyAudit; }
        public boolean isEnableTimeTravel() { return enableTimeTravel; }
        public void setEnableTimeTravel(boolean enableTimeTravel) { this.enableTimeTravel = enableTimeTravel; }
        public boolean isEnableResultCache() { return enableResultCache; }
        public void setEnableResultCache(boolean enableResultCache) { this.enableResultCache = enableResultCache; }
        public double getSemanticConfidenceThreshold() { return semanticConfidenceThreshold; }
        public void setSemanticConfidenceThreshold(double semanticConfidenceThreshold) { this.semanticConfidenceThreshold = semanticConfidenceThreshold; }
        public String getDefaultApprovalPolicy() { return defaultApprovalPolicy; }
        public void setDefaultApprovalPolicy(String defaultApprovalPolicy) { this.defaultApprovalPolicy = defaultApprovalPolicy; }
    }

    public static class Guardrails {
        private boolean enabled = false;
        private boolean piiRedactionEnabled = true;
        private List<RegexRule> regexRules = List.of();
        private List<KeywordRule> keywordRules = List.of();
        private boolean embeddingSimilarityEnabled = false;
        private double embeddingSimilarityThreshold = 0.85;
        private boolean llmJudgeEnabled = false;
        private String llmJudgePolicy = "Block harmful, illegal, or toxic content.";
        private double llmJudgeBlockThreshold = 0.8;
        private long tier2TimeoutMillis = 500;
        private long tier3TimeoutMillis = 5000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isPiiRedactionEnabled() { return piiRedactionEnabled; }
        public void setPiiRedactionEnabled(boolean piiRedactionEnabled) { this.piiRedactionEnabled = piiRedactionEnabled; }
        public List<RegexRule> getRegexRules() { return regexRules; }
        public void setRegexRules(List<RegexRule> regexRules) { this.regexRules = regexRules != null ? List.copyOf(regexRules) : List.of(); }
        public List<KeywordRule> getKeywordRules() { return keywordRules; }
        public void setKeywordRules(List<KeywordRule> keywordRules) { this.keywordRules = keywordRules != null ? List.copyOf(keywordRules) : List.of(); }
        public boolean isEmbeddingSimilarityEnabled() { return embeddingSimilarityEnabled; }
        public void setEmbeddingSimilarityEnabled(boolean embeddingSimilarityEnabled) { this.embeddingSimilarityEnabled = embeddingSimilarityEnabled; }
        public double getEmbeddingSimilarityThreshold() { return embeddingSimilarityThreshold; }
        public void setEmbeddingSimilarityThreshold(double embeddingSimilarityThreshold) { this.embeddingSimilarityThreshold = embeddingSimilarityThreshold; }
        public boolean isLlmJudgeEnabled() { return llmJudgeEnabled; }
        public void setLlmJudgeEnabled(boolean llmJudgeEnabled) { this.llmJudgeEnabled = llmJudgeEnabled; }
        public String getLlmJudgePolicy() { return llmJudgePolicy; }
        public void setLlmJudgePolicy(String llmJudgePolicy) { this.llmJudgePolicy = llmJudgePolicy; }
        public double getLlmJudgeBlockThreshold() { return llmJudgeBlockThreshold; }
        public void setLlmJudgeBlockThreshold(double llmJudgeBlockThreshold) { this.llmJudgeBlockThreshold = llmJudgeBlockThreshold; }
        public long getTier2TimeoutMillis() { return tier2TimeoutMillis; }
        public void setTier2TimeoutMillis(long tier2TimeoutMillis) { this.tier2TimeoutMillis = tier2TimeoutMillis; }
        public long getTier3TimeoutMillis() { return tier3TimeoutMillis; }
        public void setTier3TimeoutMillis(long tier3TimeoutMillis) { this.tier3TimeoutMillis = tier3TimeoutMillis; }

        public static class RegexRule {
            private String name;
            private String pattern;
            private String action = "BLOCK"; // BLOCK or REDACT

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getPattern() { return pattern; }
            public void setPattern(String pattern) { this.pattern = pattern; }
            public String getAction() { return action; }
            public void setAction(String action) { this.action = action; }
        }

        public static class KeywordRule {
            private String name;
            private List<String> keywords = List.of();
            private String action = "BLOCK";

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public List<String> getKeywords() { return keywords; }
            public void setKeywords(List<String> keywords) { this.keywords = keywords != null ? List.copyOf(keywords) : List.of(); }
            public String getAction() { return action; }
            public void setAction(String action) { this.action = action; }
        }
    }

    public static class Telemetry {
        private boolean enabled = true;
        private boolean metricsEnabled = true;
        private boolean costTrackingEnabled = false;
        private boolean provenanceTrackingEnabled = true;
        private boolean structuredLoggingEnabled = true;
        private boolean openTelemetryEnabled = false;
        private String openTelemetryEndpoint = "http://localhost:4317";
        private Map<String, String> openTelemetryHeaders = Map.of();
        private double openTelemetrySamplingRate = 1.0;
        private BigDecimal budgetLimit = BigDecimal.valueOf(-1); // -1 = unlimited
        private int maxHistogramSize = 10_000;
        private int provenanceMaxEntries = 100_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isMetricsEnabled() { return metricsEnabled; }
        public void setMetricsEnabled(boolean metricsEnabled) { this.metricsEnabled = metricsEnabled; }
        public boolean isCostTrackingEnabled() { return costTrackingEnabled; }
        public void setCostTrackingEnabled(boolean costTrackingEnabled) { this.costTrackingEnabled = costTrackingEnabled; }
        public boolean isProvenanceTrackingEnabled() { return provenanceTrackingEnabled; }
        public void setProvenanceTrackingEnabled(boolean provenanceTrackingEnabled) { this.provenanceTrackingEnabled = provenanceTrackingEnabled; }
        public boolean isStructuredLoggingEnabled() { return structuredLoggingEnabled; }
        public void setStructuredLoggingEnabled(boolean structuredLoggingEnabled) { this.structuredLoggingEnabled = structuredLoggingEnabled; }
        public boolean isOpenTelemetryEnabled() { return openTelemetryEnabled; }
        public void setOpenTelemetryEnabled(boolean openTelemetryEnabled) { this.openTelemetryEnabled = openTelemetryEnabled; }
        public String getOpenTelemetryEndpoint() { return openTelemetryEndpoint; }
        public void setOpenTelemetryEndpoint(String openTelemetryEndpoint) { this.openTelemetryEndpoint = openTelemetryEndpoint; }
        public Map<String, String> getOpenTelemetryHeaders() { return openTelemetryHeaders; }
        public void setOpenTelemetryHeaders(Map<String, String> openTelemetryHeaders) { this.openTelemetryHeaders = openTelemetryHeaders != null ? Map.copyOf(openTelemetryHeaders) : Map.of(); }
        public double getOpenTelemetrySamplingRate() { return openTelemetrySamplingRate; }
        public void setOpenTelemetrySamplingRate(double openTelemetrySamplingRate) { this.openTelemetrySamplingRate = openTelemetrySamplingRate; }
        public BigDecimal getBudgetLimit() { return budgetLimit; }
        public void setBudgetLimit(BigDecimal budgetLimit) { this.budgetLimit = budgetLimit; }
        public int getMaxHistogramSize() { return maxHistogramSize; }
        public void setMaxHistogramSize(int maxHistogramSize) { this.maxHistogramSize = maxHistogramSize; }
        public int getProvenanceMaxEntries() { return provenanceMaxEntries; }
        public void setProvenanceMaxEntries(int provenanceMaxEntries) { this.provenanceMaxEntries = provenanceMaxEntries; }
    }

    public static class Memory {
        private boolean enabled = false;
        private boolean hierarchicalEnabled = false;
        private int shortTermMaxTokens = 4000;
        private int shortTermMaxMessages = 20;
        private boolean longTermEnabled = false;
        private double longTermBm25Weight = 0.3;
        private double longTermSemanticWeight = 0.7;
        private boolean contextCompactionEnabled = true;
        private int compactionTargetTokens = 2000;
        private boolean checkpointingEnabled = false;
        private String checkpointType = "memory"; // memory, jdbc, redis
        private String checkpointJdbcTable = "chorus_checkpoints";
        private String checkpointRedisKeyPrefix = "chorus:cp:";
        private int checkpointRedisTtlSeconds = 86400;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isHierarchicalEnabled() { return hierarchicalEnabled; }
        public void setHierarchicalEnabled(boolean hierarchicalEnabled) { this.hierarchicalEnabled = hierarchicalEnabled; }
        public int getShortTermMaxTokens() { return shortTermMaxTokens; }
        public void setShortTermMaxTokens(int shortTermMaxTokens) { this.shortTermMaxTokens = shortTermMaxTokens; }
        public int getShortTermMaxMessages() { return shortTermMaxMessages; }
        public void setShortTermMaxMessages(int shortTermMaxMessages) { this.shortTermMaxMessages = shortTermMaxMessages; }
        public boolean isLongTermEnabled() { return longTermEnabled; }
        public void setLongTermEnabled(boolean longTermEnabled) { this.longTermEnabled = longTermEnabled; }
        public double getLongTermBm25Weight() { return longTermBm25Weight; }
        public void setLongTermBm25Weight(double longTermBm25Weight) { this.longTermBm25Weight = longTermBm25Weight; }
        public double getLongTermSemanticWeight() { return longTermSemanticWeight; }
        public void setLongTermSemanticWeight(double longTermSemanticWeight) { this.longTermSemanticWeight = longTermSemanticWeight; }
        public boolean isContextCompactionEnabled() { return contextCompactionEnabled; }
        public void setContextCompactionEnabled(boolean contextCompactionEnabled) { this.contextCompactionEnabled = contextCompactionEnabled; }
        public int getCompactionTargetTokens() { return compactionTargetTokens; }
        public void setCompactionTargetTokens(int compactionTargetTokens) { this.compactionTargetTokens = compactionTargetTokens; }
        public boolean isCheckpointingEnabled() { return checkpointingEnabled; }
        public void setCheckpointingEnabled(boolean checkpointingEnabled) { this.checkpointingEnabled = checkpointingEnabled; }
        public String getCheckpointType() { return checkpointType; }
        public void setCheckpointType(String checkpointType) { this.checkpointType = checkpointType; }
        public String getCheckpointJdbcTable() { return checkpointJdbcTable; }
        public void setCheckpointJdbcTable(String checkpointJdbcTable) { this.checkpointJdbcTable = checkpointJdbcTable; }
        public String getCheckpointRedisKeyPrefix() { return checkpointRedisKeyPrefix; }
        public void setCheckpointRedisKeyPrefix(String checkpointRedisKeyPrefix) { this.checkpointRedisKeyPrefix = checkpointRedisKeyPrefix; }
        public int getCheckpointRedisTtlSeconds() { return checkpointRedisTtlSeconds; }
        public void setCheckpointRedisTtlSeconds(int checkpointRedisTtlSeconds) { this.checkpointRedisTtlSeconds = checkpointRedisTtlSeconds; }
    }

    public static class Mcp {
        private boolean enabled = false;
        private String transportType = "http-sse"; // http-sse, stdio
        private String httpSseEndpoint = "http://localhost:3000/sse";
        private List<String> stdioCommand = List.of();
        private boolean serverEnabled = false;
        private boolean serverTools = true;
        private boolean serverResources = true;
        private boolean serverPrompts = false;
        private boolean serverLogging = true;
        private boolean serverCompletion = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTransportType() { return transportType; }
        public void setTransportType(String transportType) { this.transportType = transportType; }
        public String getHttpSseEndpoint() { return httpSseEndpoint; }
        public void setHttpSseEndpoint(String httpSseEndpoint) { this.httpSseEndpoint = httpSseEndpoint; }
        public List<String> getStdioCommand() { return stdioCommand; }
        public void setStdioCommand(List<String> stdioCommand) { this.stdioCommand = stdioCommand != null ? List.copyOf(stdioCommand) : List.of(); }
        public boolean isServerEnabled() { return serverEnabled; }
        public void setServerEnabled(boolean serverEnabled) { this.serverEnabled = serverEnabled; }
        public boolean isServerTools() { return serverTools; }
        public void setServerTools(boolean serverTools) { this.serverTools = serverTools; }
        public boolean isServerResources() { return serverResources; }
        public void setServerResources(boolean serverResources) { this.serverResources = serverResources; }
        public boolean isServerPrompts() { return serverPrompts; }
        public void setServerPrompts(boolean serverPrompts) { this.serverPrompts = serverPrompts; }
        public boolean isServerLogging() { return serverLogging; }
        public void setServerLogging(boolean serverLogging) { this.serverLogging = serverLogging; }
        public boolean isServerCompletion() { return serverCompletion; }
        public void setServerCompletion(boolean serverCompletion) { this.serverCompletion = serverCompletion; }
    }

    public static class A2a {
        private boolean enabled = false;
        private String baseUrl = "";
        private String authToken = "";
        private boolean serverEnabled = false;
        private String serverAgentName = "chorus-agent";
        private String serverAgentDescription = "Chorus Engine agent";
        private String serverAgentVersion = "1.0.0";
        private String serverAgentUrl = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAuthToken() { return authToken; }
        public void setAuthToken(String authToken) { this.authToken = authToken; }
        public boolean isServerEnabled() { return serverEnabled; }
        public void setServerEnabled(boolean serverEnabled) { this.serverEnabled = serverEnabled; }
        public String getServerAgentName() { return serverAgentName; }
        public void setServerAgentName(String serverAgentName) { this.serverAgentName = serverAgentName; }
        public String getServerAgentDescription() { return serverAgentDescription; }
        public void setServerAgentDescription(String serverAgentDescription) { this.serverAgentDescription = serverAgentDescription; }
        public String getServerAgentVersion() { return serverAgentVersion; }
        public void setServerAgentVersion(String serverAgentVersion) { this.serverAgentVersion = serverAgentVersion; }
        public String getServerAgentUrl() { return serverAgentUrl; }
        public void setServerAgentUrl(String serverAgentUrl) { this.serverAgentUrl = serverAgentUrl; }
    }

    public static class Evals {
        private boolean enabled = false;
        private boolean parallelEnabled = true;
        private int parallelMaxConcurrency = 0; // 0 = auto = availableProcessors
        private String defaultScorer = "exact_match";
        private double llmJudgePassThreshold = 0.7;
        private double semanticSimilarityThreshold = 0.8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isParallelEnabled() { return parallelEnabled; }
        public void setParallelEnabled(boolean parallelEnabled) { this.parallelEnabled = parallelEnabled; }
        public int getParallelMaxConcurrency() { return parallelMaxConcurrency; }
        public void setParallelMaxConcurrency(int parallelMaxConcurrency) { this.parallelMaxConcurrency = parallelMaxConcurrency; }
        public String getDefaultScorer() { return defaultScorer; }
        public void setDefaultScorer(String defaultScorer) { this.defaultScorer = defaultScorer; }
        public double getLlmJudgePassThreshold() { return llmJudgePassThreshold; }
        public void setLlmJudgePassThreshold(double llmJudgePassThreshold) { this.llmJudgePassThreshold = llmJudgePassThreshold; }
        public double getSemanticSimilarityThreshold() { return semanticSimilarityThreshold; }
        public void setSemanticSimilarityThreshold(double semanticSimilarityThreshold) { this.semanticSimilarityThreshold = semanticSimilarityThreshold; }
    }

    public static class Enterprise {
        private boolean auditLogEnabled = false;
        private boolean accessControlEnabled = false;
        private String defaultTenantId = "default";
        private boolean multiTenancyEnabled = false;

        public boolean isAuditLogEnabled() { return auditLogEnabled; }
        public void setAuditLogEnabled(boolean auditLogEnabled) { this.auditLogEnabled = auditLogEnabled; }
        public boolean isAccessControlEnabled() { return accessControlEnabled; }
        public void setAccessControlEnabled(boolean accessControlEnabled) { this.accessControlEnabled = accessControlEnabled; }
        public String getDefaultTenantId() { return defaultTenantId; }
        public void setDefaultTenantId(String defaultTenantId) { this.defaultTenantId = defaultTenantId; }
        public boolean isMultiTenancyEnabled() { return multiTenancyEnabled; }
        public void setMultiTenancyEnabled(boolean multiTenancyEnabled) { this.multiTenancyEnabled = multiTenancyEnabled; }
    }
}
