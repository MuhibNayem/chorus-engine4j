package com.chorus.engine.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "chorus")
public class ChorusProperties {

    private boolean enabled = true;
    private String checkpointDir = "${user.home}/.chorus/checkpoints";
    private String checkpointMode = "sync"; // sync, async, exit
    private int maxRounds = 500;
    private long streamTimeoutMs = 120_000;
    private List<String> middleware = List.of("summarization", "observability");
    private Guardrails guardrails = new Guardrails();
    private VectorStore vectorStore = new VectorStore();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCheckpointDir() { return checkpointDir; }
    public void setCheckpointDir(String checkpointDir) { this.checkpointDir = checkpointDir; }
    public String getCheckpointMode() { return checkpointMode; }
    public void setCheckpointMode(String checkpointMode) { this.checkpointMode = checkpointMode; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public long getStreamTimeoutMs() { return streamTimeoutMs; }
    public void setStreamTimeoutMs(long streamTimeoutMs) { this.streamTimeoutMs = streamTimeoutMs; }
    public List<String> getMiddleware() { return middleware; }
    public void setMiddleware(List<String> middleware) { this.middleware = middleware; }
    public Guardrails getGuardrails() { return guardrails; }
    public void setGuardrails(Guardrails guardrails) { this.guardrails = guardrails; }
    public VectorStore getVectorStore() { return vectorStore; }
    public void setVectorStore(VectorStore vectorStore) { this.vectorStore = vectorStore; }

    public static class VectorStore {
        private double similarityThreshold = 0.7;
        private int topK = 5;

        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

    public static class Guardrails {
        private boolean enabled = true;
        private String haltOn = "critical";
        private boolean runAll = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHaltOn() { return haltOn; }
        public void setHaltOn(String haltOn) { this.haltOn = haltOn; }
        public boolean isRunAll() { return runAll; }
        public void setRunAll(boolean runAll) { this.runAll = runAll; }
    }
}
