package com.chorus.engine.llm.retry;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for LLM provider resilience.
 *
 * <p>States: CLOSED → OPEN → HALF_OPEN → CLOSED
 * <ul>
 *   <li>CLOSED: Normal operation, failures counted</li>
 *   <li>OPEN: All calls fail fast, no requests sent</li>
 *   <li>HALF_OPEN: One probe allowed to test recovery</li>
 * </ul>
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private volatile Instant lastFailureTime;
    private volatile Instant openedAt;

    public CircuitBreaker(int failureThreshold, @NonNull Duration openDuration) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public static @NonNull CircuitBreaker defaults() {
        return new CircuitBreaker(5, Duration.ofSeconds(30));
    }

    public @NonNull State state() {
        if (state.get() == State.OPEN && Duration.between(openedAt, Instant.now()).compareTo(openDuration) > 0) {
            // Try to transition to HALF_OPEN
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }
        return state.get();
    }

    /**
     * Record a successful call.
     */
    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            // Reset on first success in half-open
            failureCount.set(0);
            successCount.incrementAndGet();
            state.set(State.CLOSED);
        } else {
            successCount.incrementAndGet();
            failureCount.set(0); // Reset failures on any success in closed state
        }
    }

    /**
     * Record a failed call.
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();
        int failures = failureCount.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            // Failed probe → back to OPEN
            openedAt = Instant.now();
            state.set(State.OPEN);
        } else if (failures >= failureThreshold) {
            State current = state.get();
            if (current == State.CLOSED && state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt = Instant.now();
            }
        }
    }

    /**
     * Returns true if the circuit allows a request through.
     */
    public boolean allowsRequest() {
        State s = state();
        return s == State.CLOSED || s == State.HALF_OPEN;
    }

    public boolean isOpen() {
        return state() == State.OPEN;
    }

    public int failureCount() {
        return failureCount.get();
    }

    public @NonNull String metrics() {
        return String.format("CircuitBreaker[state=%s, failures=%d, successes=%d, threshold=%d]",
            state(), failureCount.get(), successCount.get(), failureThreshold);
    }
}
