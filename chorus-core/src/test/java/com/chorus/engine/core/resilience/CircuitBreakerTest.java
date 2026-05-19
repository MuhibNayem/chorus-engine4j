package com.chorus.engine.core.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    @Test
    void startsClosedAndAllowsRequests() {
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(30));

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void opensAfterThresholdFailures() {
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(30));

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void resetsToClosedOnSuccess() {
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(30));

        cb.recordFailure();
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Manual reset via success while OPEN (simulating half-open probe)
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void transitionsToHalfOpenAfterCooldown() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, Duration.ofMillis(50));

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();

        Thread.sleep(100); // Wait for cooldown

        assertThat(cb.allowRequest()).isTrue();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenClosesOnSuccess() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, Duration.ofMillis(50));

        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(100);

        assertThat(cb.allowRequest()).isTrue(); // HALF_OPEN
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenReOpensOnFailure() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));

        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        Thread.sleep(100);

        assertThat(cb.allowRequest()).isTrue(); // HALF_OPEN
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void threadSafetyStressTest() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(100, Duration.ofSeconds(1));
        int threads = 10;
        int iterations = 1000;

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < iterations; j++) {
                    cb.allowRequest();
                    if (Math.random() < 0.3) {
                        cb.recordFailure();
                    } else {
                        cb.recordSuccess();
                    }
                }
            });
            workers[i].start();
        }
        for (Thread t : workers) {
            t.join();
        }

        // After all operations, the breaker should be in a valid state
        assertThat(cb.getState()).isIn(CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN);
    }
}
