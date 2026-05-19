package com.chorus.engine.core.resilience;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.llm.ChorusChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Decorates a ChorusChatModel with retry, backoff, and circuit breaker logic.
 */
public class RetryableChatModel implements ChorusChatModel {

    private static final Logger log = LoggerFactory.getLogger(RetryableChatModel.class);

    private final ChorusChatModel delegate;
    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final CircuitBreaker circuitBreaker;

    public RetryableChatModel(ChorusChatModel delegate, int maxRetries, Duration baseDelay, Duration maxDelay) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.circuitBreaker = new CircuitBreaker(maxRetries, Duration.ofSeconds(30));
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public CompletableFuture<ModelResponse> generate(List<ChatMessage> messages, String systemPrompt,
                                                      List<ToolDef> tools, String model) {
        return executeWithRetry(() -> delegate.generate(messages, systemPrompt, tools, model));
    }

    @Override
    public CompletableFuture<StreamingResponse> stream(List<ChatMessage> messages, String systemPrompt,
                                                        List<ToolDef> tools, String model) {
        return executeWithRetry(() -> delegate.stream(messages, systemPrompt, tools, model));
    }

    private <T> CompletableFuture<T> executeWithRetry(java.util.function.Supplier<CompletableFuture<T>> operation) {
        if (!circuitBreaker.allowRequest()) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Circuit breaker is OPEN for model: " + delegate.name()));
        }

        return retry(operation, 0).whenComplete((result, error) -> {
            if (error != null) {
                circuitBreaker.recordFailure();
            } else {
                circuitBreaker.recordSuccess();
            }
        });
    }

    private <T> CompletableFuture<T> retry(java.util.function.Supplier<CompletableFuture<T>> operation, int attempt) {
        return operation.get().exceptionallyCompose(error -> {
            if (attempt >= maxRetries) {
                log.error("Exhausted all {} retries for model {}", maxRetries, delegate.name(), error);
                return CompletableFuture.failedFuture(error);
            }

            long delayMs = Math.min(
                baseDelay.toMillis() * (1L << attempt),
                maxDelay.toMillis()
            );
            // Add jitter: +/- 20%
            delayMs = (long) (delayMs * (0.8 + Math.random() * 0.4));

            log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}",
                attempt + 1, maxRetries, delayMs, error.getMessage());

            var executor = CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return CompletableFuture.runAsync(() -> {}, executor)
                .thenCompose(ignored -> retry(operation, attempt + 1));
        });
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
