package com.chorus.engine.llm.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void default_policy_values() {
        RetryPolicy policy = RetryPolicy.DEFAULT;
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.baseDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.maxDelay()).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.perChunkTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void computeDelay_exponential_backoff() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(10), 0.0,
            Set.of(), Set.of(), Duration.ofSeconds(5));

        assertThat(policy.computeDelay(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.computeDelay(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.computeDelay(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.computeDelay(4)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    void computeDelay_capped_at_max() {
        RetryPolicy policy = new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(5), 0.0,
            Set.of(), Set.of(), Duration.ofSeconds(5));

        assertThat(policy.computeDelay(6)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void computeDelay_with_jitter() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(1000), Duration.ofSeconds(10), 0.5,
            Set.of(), Set.of(), Duration.ofSeconds(5));

        Duration d1 = policy.computeDelay(1);
        assertThat(d1.toMillis()).isGreaterThanOrEqualTo(500).isLessThanOrEqualTo(1000);
    }

    @Test
    void isRetryable_statusCode() {
        assertThat(RetryPolicy.DEFAULT.isRetryable(429)).isTrue();
        assertThat(RetryPolicy.DEFAULT.isRetryable(502)).isTrue();
        assertThat(RetryPolicy.DEFAULT.isRetryable(503)).isTrue();
        assertThat(RetryPolicy.DEFAULT.isRetryable(504)).isTrue();
        assertThat(RetryPolicy.DEFAULT.isRetryable(400)).isFalse();
        assertThat(RetryPolicy.DEFAULT.isRetryable(500)).isFalse();
    }

    @Test
    void none_policy_no_retries() {
        assertThat(RetryPolicy.NONE.maxAttempts()).isEqualTo(1);
        assertThat(RetryPolicy.NONE.computeDelay(1)).isEqualTo(Duration.ZERO);
    }

    @Test
    void invalid_construction_rejected() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMillis(100), Duration.ofSeconds(1), 0.0,
            Set.of(), Set.of(), Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(50), 0.0,
            Set.of(), Set.of(), Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1), 1.5,
            Set.of(), Set.of(), Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
