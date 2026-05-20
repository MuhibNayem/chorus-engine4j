package com.chorus.engine.llm.retry;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private record StateSnapshot(State state, int failures, int successes, Instant openedAt, boolean probeInFlight) {}

    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicReference<StateSnapshot> snapshot = new AtomicReference<>(
        new StateSnapshot(State.CLOSED, 0, 0, null, false));
    private volatile Instant lastFailureTime;

    public CircuitBreaker(int failureThreshold, @NonNull Duration openDuration) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public static @NonNull CircuitBreaker defaults() {
        return new CircuitBreaker(5, Duration.ofSeconds(30));
    }

    public @NonNull State state() {
        StateSnapshot s = snapshot.get();
        if (s.state == State.OPEN && s.openedAt != null
                && Duration.between(s.openedAt, Instant.now()).compareTo(openDuration) > 0) {
            transitionToHalfOpen();
        }
        return snapshot.get().state;
    }

    private void transitionToHalfOpen() {
        snapshot.updateAndGet(s ->
            s.state == State.OPEN
                ? new StateSnapshot(State.HALF_OPEN, s.failures, s.successes, s.openedAt, false)
                : s);
    }

    /**
     * Record a successful call.
     */
    public void recordSuccess() {
        snapshot.updateAndGet(s -> {
            if (s.state == State.HALF_OPEN) {
                return new StateSnapshot(State.CLOSED, 0, s.successes + 1, null, false);
            }
            return new StateSnapshot(s.state, 0, s.successes + 1, s.openedAt, s.probeInFlight);
        });
    }

    /**
     * Record a failed call.
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();
        snapshot.updateAndGet(s -> {
            int newFailures = s.failures + 1;
            if (s.state == State.HALF_OPEN) {
                return new StateSnapshot(State.OPEN, newFailures, s.successes, Instant.now(), false);
            }
            if (newFailures >= failureThreshold && s.state == State.CLOSED) {
                return new StateSnapshot(State.OPEN, newFailures, s.successes, Instant.now(), s.probeInFlight);
            }
            return new StateSnapshot(s.state, newFailures, s.successes, s.openedAt, s.probeInFlight);
        });
    }

    /**
     * Returns true if the circuit allows a request through.
     */
    public boolean allowsRequest() {
        State s = state();
        if (s == State.CLOSED) return true;
        if (s == State.HALF_OPEN) {
            AtomicBoolean acquired = new AtomicBoolean(false);
            snapshot.updateAndGet(cur -> {
                if (cur.state == State.HALF_OPEN && !cur.probeInFlight) {
                    acquired.set(true);
                    return new StateSnapshot(cur.state, cur.failures, cur.successes, cur.openedAt, true);
                }
                return cur;
            });
            return acquired.get();
        }
        return false;
    }

    public boolean isOpen() {
        return state() == State.OPEN;
    }

    public int failureCount() {
        return snapshot.get().failures;
    }

    public @NonNull String metrics() {
        StateSnapshot s = snapshot.get();
        return String.format("CircuitBreaker[state=%s, failures=%d, successes=%d, threshold=%d]",
            s.state, s.failures, s.successes, failureThreshold);
    }
}
