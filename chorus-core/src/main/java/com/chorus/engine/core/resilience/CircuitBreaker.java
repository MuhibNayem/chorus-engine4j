package com.chorus.engine.core.resilience;

import java.time.Duration;
import java.time.Instant;

/**
 * Simple circuit breaker for LLM calls.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration cooldownDuration;
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private Instant lastFailureTime;

    public CircuitBreaker(int failureThreshold, Duration cooldownDuration) {
        this.failureThreshold = failureThreshold;
        this.cooldownDuration = cooldownDuration;
    }

    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (lastFailureTime != null && Duration.between(lastFailureTime, Instant.now()).compareTo(cooldownDuration) > 0) {
                state = State.HALF_OPEN;
                consecutiveFailures = 0;
                return true;
            }
            return false;
        }
        return true; // HALF_OPEN
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        lastFailureTime = Instant.now();
        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
        }
    }

    public State getState() {
        return state;
    }
}
