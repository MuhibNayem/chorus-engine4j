package com.chorus.engine.llm.retry;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configurable retry policy with exponential backoff and jitter.
 *
 * <p>Immutable, thread-safe. Default policies for common scenarios.
 */
public record RetryPolicy(
    int maxAttempts,
    @NonNull Duration baseDelay,
    @NonNull Duration maxDelay,
    double jitterFactor, // 0.0 - 1.0
    @NonNull Set<Integer> retryableStatusCodes,
    @NonNull Set<String> retryableErrorCodes,
    @NonNull Duration perChunkTimeout
) {
    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseDelay.isNegative()) throw new IllegalArgumentException("baseDelay must be non-negative");
        if (maxDelay.compareTo(baseDelay) < 0) throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        if (jitterFactor < 0 || jitterFactor > 1) throw new IllegalArgumentException("jitterFactor must be in [0, 1]");
        if (perChunkTimeout.isNegative()) throw new IllegalArgumentException("perChunkTimeout must be non-negative");
        retryableStatusCodes = Set.copyOf(retryableStatusCodes);
        retryableErrorCodes = Set.copyOf(retryableErrorCodes);
    }

    public static final RetryPolicy DEFAULT = new RetryPolicy(
        3,
        Duration.ofMillis(500),
        Duration.ofSeconds(8),
        0.2,
        Set.of(429, 502, 503, 504),
        Set.of("rate_limit", "overloaded", "timeout", "temporarily_unavailable"),
        Duration.ofSeconds(30)
    );

    public static final RetryPolicy RATE_LIMIT = new RetryPolicy(
        5,
        Duration.ofSeconds(1),
        Duration.ofSeconds(30),
        0.3,
        Set.of(429, 502, 503, 504),
        Set.of("rate_limit", "overloaded"),
        Duration.ofSeconds(30)
    );

    public static final RetryPolicy NONE = new RetryPolicy(
        1,
        Duration.ZERO,
        Duration.ZERO,
        0.0,
        Set.of(),
        Set.of(),
        Duration.ofSeconds(30)
    );

    /**
     * Compute the delay before the next attempt.
     */
    public @NonNull Duration computeDelay(int attempt) {
        if (attempt <= 0) return Duration.ZERO;
        long baseMs = baseDelay.toMillis();
        long maxDelayMs = maxDelay.toMillis();
        // Safe exponential backoff: cap exponent to avoid overflow
        int exp = Math.min(attempt - 1, 62);
        long multiplier = 1L << exp;
        long delayMs;
        try {
            delayMs = Math.multiplyExact(baseMs, multiplier);
        } catch (ArithmeticException e) {
            delayMs = Long.MAX_VALUE;
        }
        delayMs = Math.min(delayMs, maxDelayMs);

        long jitterMs = (long) (delayMs * jitterFactor);
        if (jitterMs > 0) {
            delayMs = delayMs - jitterMs + ThreadLocalRandom.current().nextLong(jitterMs);
        }
        return Duration.ofMillis(delayMs);
    }

    public boolean isRetryable(int statusCode) {
        return retryableStatusCodes.contains(statusCode);
    }

    public boolean isRetryable(@NonNull String errorCode) {
        return retryableErrorCodes.contains(errorCode);
    }
}
