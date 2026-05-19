package com.chorus.engine.llm.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void starts_closed() {
        CircuitBreaker cb = CircuitBreaker.defaults();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowsRequest()).isTrue();
        assertThat(cb.isOpen()).isFalse();
    }

    @Test
    void opens_after_threshold_failures() {
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofMinutes(1));
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowsRequest()).isFalse();
        assertThat(cb.isOpen()).isTrue();
    }

    @Test
    void success_resets_failures() {
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofMinutes(1));
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();
        // Still closed: 2 failures since last success
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void half_open_after_timeout() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        Thread.sleep(100);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(cb.allowsRequest()).isTrue();
    }

    @Test
    void half_open_success_closes() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        Thread.sleep(100);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.failureCount()).isEqualTo(0);
    }

    @Test
    void half_open_failure_reopens() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        Thread.sleep(100);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void metrics_format() {
        CircuitBreaker cb = CircuitBreaker.defaults();
        assertThat(cb.metrics()).contains("state=CLOSED");
        assertThat(cb.metrics()).contains("failures=0");
    }
}
