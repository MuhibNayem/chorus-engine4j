package com.chorus.engine.core.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public interface RetryPolicy {

    int maxAttempts();

    boolean shouldRetry(Throwable error, int attempt);

    Duration delay(int attempt);

    RetryPolicy DEFAULT = new RetryPolicy() {
        @Override public int maxAttempts() { return 3; }
        @Override public boolean shouldRetry(Throwable error, int attempt) {
            return attempt < 3 && isRetryable(error);
        }
        @Override public Duration delay(int attempt) {
            return Duration.ofMillis(Math.min(500L * (1L << (attempt - 1)), 8000L));
        }
    };

    RetryPolicy RATE_LIMIT = new RetryPolicy() {
        @Override public int maxAttempts() { return 5; }
        @Override public boolean shouldRetry(Throwable error, int attempt) {
            return attempt < 5 && isRetryable(error);
        }
        @Override public Duration delay(int attempt) {
            long base = Math.min(1000L * (1L << attempt), 30000L);
            double jitter = ThreadLocalRandom.current().nextDouble(0.0, 0.2) * base;
            return Duration.ofMillis(Math.round(base + jitter));
        }
    };

    static boolean isRetryable(Throwable error) {
        if (error instanceof java.net.http.HttpTimeoutException) return true;
        String msg = error.getMessage();
        if (msg == null) return false;
        return msg.matches(".*(429|503|502|504|rate.?limit|too.?many.?req|temporar|timeout|timed.?out|service.?unavail|overload).*");
    }
}
