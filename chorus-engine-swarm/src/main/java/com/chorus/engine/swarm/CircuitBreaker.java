package com.chorus.engine.swarm;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-agent failure protection.
 *
 * <p>States:
 * <ul>
 *   <li>CLOSED  – normal operation, failures counted</li>
 *   <li>OPEN    – fast-fail, no requests allowed</li>
 *   <li>HALF_OPEN – one probe allowed to test recovery</li>
 * </ul>
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration resetTimeout;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile Instant lastFailureTime;
    private volatile Instant openedAt;

    public CircuitBreaker() {
        this(5, Duration.ofSeconds(30));
    }

    public CircuitBreaker(int failureThreshold, @NonNull Duration resetTimeout) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
    }

    public @NonNull State state() {
        if (state.get() == State.OPEN && Duration.between(openedAt, Instant.now()).compareTo(resetTimeout) > 0) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }
        return state.get();
    }

    /**
     * Returns true if the circuit allows a request through.
     */
    public boolean allowRequest() {
        State s = state();
        return s == State.CLOSED || s == State.HALF_OPEN;
    }

    /**
     * Record a successful call.
     */
    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            failureCount.set(0);
            state.set(State.CLOSED);
        } else {
            failureCount.set(0);
        }
    }

    /**
     * Record a failed call.
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();
        int failures = failureCount.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            openedAt = Instant.now();
            state.set(State.OPEN);
        } else if (failures >= failureThreshold) {
            State current = state.get();
            if (current == State.CLOSED && state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt = Instant.now();
            }
        }
    }

    public int failureCount() {
        return failureCount.get();
    }

    public @NonNull Instant lastFailureTime() {
        return lastFailureTime != null ? lastFailureTime : Instant.EPOCH;
    }
}
