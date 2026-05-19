package com.chorus.engine.swarm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void defaults_are_closed_and_allow_requests() {
        CircuitBreaker cb = new CircuitBreaker();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
        assertThat(cb.failureCount()).isZero();
    }

    @Test
    void failures_below_threshold_stay_closed() {
        CircuitBreaker cb = new CircuitBreaker(5, Duration.ofSeconds(30));
        for (int i = 0; i < 4; i++) {
            cb.recordFailure();
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
        assertThat(cb.failureCount()).isEqualTo(4);
    }

    @Test
    void failures_at_threshold_open_circuit() {
        CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(30));
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void success_resets_failure_count() {
        CircuitBreaker cb = new CircuitBreaker(5, Duration.ofSeconds(30));
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.failureCount()).isZero();
    }

    @Test
    void half_open_allows_one_probe() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();

        Thread.sleep(100);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void half_open_success_closes_circuit() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        Thread.sleep(100);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void half_open_failure_reopens_circuit() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        Thread.sleep(100);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void last_failure_time_is_tracked() {
        CircuitBreaker cb = new CircuitBreaker();
        assertThat(cb.lastFailureTime()).isEqualTo(java.time.Instant.EPOCH);
        cb.recordFailure();
        assertThat(cb.lastFailureTime()).isAfter(java.time.Instant.EPOCH);
    }
}
