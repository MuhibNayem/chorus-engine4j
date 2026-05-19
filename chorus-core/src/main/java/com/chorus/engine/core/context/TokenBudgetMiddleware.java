package com.chorus.engine.core.context;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.middleware.AgentMiddleware;
import com.chorus.engine.core.tokenizer.TokenCountEstimator;
import com.chorus.engine.core.tokenizer.TokenizerRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Middleware that tracks token usage and triggers context compaction when a budget is exceeded.
 * Uses the Chorus tokenizer for accurate per-model token estimation.
 *
 * <p>Thread token usage is stored in a bounded Caffeine cache with TTL eviction to prevent
 * memory leaks in long-running deployments. Default: 10,000 threads max, 1 hour idle TTL.</p>
 */
public class TokenBudgetMiddleware implements AgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetMiddleware.class);
    private static final int DEFAULT_MAX_THREADS = 10_000;
    private static final Duration DEFAULT_THREAD_TTL = Duration.ofHours(1);

    private final int tokenBudget;
    private final TokenCountEstimator estimator;
    private final Cache<String, Integer> threadTokenUsage;

    public TokenBudgetMiddleware(int tokenBudget) {
        this(tokenBudget, "generic");
    }

    public TokenBudgetMiddleware(int tokenBudget, String modelName) {
        this(tokenBudget, modelName, DEFAULT_MAX_THREADS, DEFAULT_THREAD_TTL);
    }

    public TokenBudgetMiddleware(int tokenBudget, String modelName, int maxThreads, Duration threadTtl) {
        this.tokenBudget = tokenBudget;
        this.estimator = new TokenCountEstimator(TokenizerRegistry.GLOBAL.get(modelName));
        this.threadTokenUsage = Caffeine.newBuilder()
            .maximumSize(maxThreads)
            .expireAfterAccess(threadTtl)
            .executor(Runnable::run)
            .removalListener((String key, Integer value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                if (log.isDebugEnabled()) {
                    log.debug("Evicted token usage for thread {}, cause={}, tokens={}", key, cause, value);
                }
            })
            .recordStats()
            .build();
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public CompletableFuture<Void> beforeRound(RoundContext ctx) {
        return CompletableFuture.runAsync(() -> {
            Integer used = threadTokenUsage.getIfPresent(ctx.threadId());
            if (used != null && used > tokenBudget * 0.8) {
                log.warn("Thread {} approaching token budget: {}/{} ({}%)",
                    ctx.threadId(), used, tokenBudget, (used * 100) / tokenBudget);
            }
        });
    }

    @Override
    public CompletableFuture<CompactResult> maybeCompact(List<ChatMessage> history, CompactOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            int estimatedTokens = estimateTokens(history);
            if (estimatedTokens < tokenBudget * 0.7) {
                return null; // No compaction needed
            }

            log.info("Token budget exceeded (est. {}). Compacting context...", estimatedTokens);

            // Simple compaction: keep system prompt, last user message, and summarize the rest
            List<ChatMessage> compacted = new ArrayList<>();
            int removed = 0;

            for (ChatMessage msg : history) {
                if (msg.role() == ChatMessage.Role.SYSTEM) {
                    compacted.add(msg);
                } else if (msg == history.get(history.size() - 1)) {
                    compacted.add(msg);
                } else {
                    removed++;
                }
            }

            if (removed > 0) {
                compacted.add(1, ChatMessage.system(
                    "[Context compacted: " + removed + " earlier messages removed to stay within token budget.]"
                ));
            }

            int savedTokens = estimateTokens(history) - estimateTokens(compacted);
            return new CompactResult(compacted, removed, savedTokens);
        });
    }

    public void recordUsage(String threadId, int inputTokens, int outputTokens) {
        threadTokenUsage.asMap().merge(threadId, inputTokens + outputTokens, Integer::sum);
    }

    public int getUsage(String threadId) {
        Integer used = threadTokenUsage.getIfPresent(threadId);
        return used != null ? used : 0;
    }

    /**
     * Returns cache statistics for observability (hits, misses, evictions, current size).
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return threadTokenUsage.stats();
    }

    /**
     * Returns approximate current number of tracked threads.
     */
    public long estimatedSize() {
        return threadTokenUsage.estimatedSize();
    }

    /**
     * Manually invalidate a thread's token usage (e.g., on conversation end).
     */
    public void invalidate(String threadId) {
        threadTokenUsage.invalidate(threadId);
    }

    private int estimateTokens(List<ChatMessage> messages) {
        return estimator.countMessages(messages);
    }
}
