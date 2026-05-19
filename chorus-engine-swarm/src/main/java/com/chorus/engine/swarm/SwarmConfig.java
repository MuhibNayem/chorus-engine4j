package com.chorus.engine.swarm;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for swarm orchestration.
 *
 * @param maxTurns              Maximum agent turns before forced termination
 * @param timeoutPerAgent       Max duration for a single agent invocation
 * @param enableCircuitBreakers Whether to use per-agent circuit breakers
 * @param enableCostRouting     Whether to use dynamic model routing
 */
public record SwarmConfig(
    int maxTurns,
    @NonNull Duration timeoutPerAgent,
    boolean enableCircuitBreakers,
    boolean enableCostRouting
) {

    public SwarmConfig {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be >= 1");
        }
        Objects.requireNonNull(timeoutPerAgent, "timeoutPerAgent cannot be null");
    }

    public static @NonNull SwarmConfig defaults() {
        return new SwarmConfig(10, Duration.ofSeconds(60), true, false);
    }
}
