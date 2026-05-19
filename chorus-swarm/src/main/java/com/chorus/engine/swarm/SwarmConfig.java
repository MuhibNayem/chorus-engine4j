package com.chorus.engine.swarm;

import com.chorus.engine.core.tool.AgentTool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SwarmConfig(
    String executionModel, // "handoff", "graph", "supervisor", "group-chat"
    List<SwarmAgent> agents,
    String initialAgent,
    Optional<CostBudget> costBudget,
    Optional<CircuitBreaker> circuitBreaker,
    int maxRounds
) {
    public SwarmConfig {
        if (maxRounds <= 0) maxRounds = 500;
    }

    public record SwarmAgent(
        String name,
        String systemPrompt,
        List<AgentTool> tools,
        List<String> handoffDestinations,
        List<String> dependsOn,
        List<String> requiredArtifacts,
        String contextMode, // "shared", "isolated", "filtered"
        Optional<java.util.function.Function<String, Boolean>> outputValidator
    ) {}

    public record CostBudget(double totalUsd) {}

    public record CircuitBreaker(
        int maxConsecutiveSameAgent,
        int maxConsecutiveRounds,
        int maxAgentRounds,
        int maxTokensPerAgent,
        long maxDurationMs,
        double maxCostUsdPerAgent,
        double maxCostUsd,
        long stallTimeoutMs
    ) {}
}
