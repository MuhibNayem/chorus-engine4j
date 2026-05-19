package com.chorus.engine.core;

import java.util.List;

/**
 * First-class control-flow exception for agent handoffs.
 * Thrown by handoff tools and caught by the agent loop to yield a native
 * {@code handoff} event, bypassing fragile JSON parsing.
 */
public class HandoffSignal extends RuntimeException {

    private final String targetAgent;
    private final String taskDescription;
    private final List<String> artifacts;
    private final String reasoning;

    public HandoffSignal(String targetAgent, String taskDescription, List<String> artifacts, String reasoning) {
        super("Handoff to " + targetAgent + ": " + taskDescription);
        this.targetAgent = targetAgent;
        this.taskDescription = taskDescription;
        this.artifacts = artifacts;
        this.reasoning = reasoning;
    }

    public String targetAgent() { return targetAgent; }
    public String taskDescription() { return taskDescription; }
    public List<String> artifacts() { return artifacts; }
    public String reasoning() { return reasoning; }
}
