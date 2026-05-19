package com.chorus.engine.core.context;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.middleware.AgentMiddleware;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetMiddlewareTest {

    @Test
    void recordsAndRetrievesTokenUsage() {
        TokenBudgetMiddleware mw = new TokenBudgetMiddleware(1000);
        mw.recordUsage("t1", 50, 30);
        mw.recordUsage("t1", 20, 10);

        assertThat(mw.getUsage("t1")).isEqualTo(110);
    }

    @Test
    void returnsZeroForUnknownThread() {
        TokenBudgetMiddleware mw = new TokenBudgetMiddleware(1000);
        assertThat(mw.getUsage("unknown")).isZero();
    }

    @Test
    void compactionTriggeredAboveThreshold() {
        // Very low budget to force compaction even with short messages
        TokenBudgetMiddleware mw = new TokenBudgetMiddleware(10);
        List<ChatMessage> history = List.of(
            ChatMessage.system("You are a very helpful assistant. Be concise and accurate."),
            ChatMessage.user("What is the capital of France?"),
            ChatMessage.assistant("The capital of France is Paris."),
            ChatMessage.user("What is the population?"),
            ChatMessage.assistant("Paris has a population of about 2.1 million people."),
            ChatMessage.user("Tell me more about the history of Paris and its significance in European culture.")
        );

        AgentMiddleware.CompactResult result = mw.maybeCompact(history,
            new AgentMiddleware.CompactOptions("generic", "sys")).join();

        assertThat(result).isNotNull();
        assertThat(result.savedTokens()).isPositive();
        assertThat(result.removedMessages()).isPositive();
    }

    @Test
    void noCompactionWhenUnderThreshold() {
        TokenBudgetMiddleware mw = new TokenBudgetMiddleware(100_000);
        List<ChatMessage> history = List.of(
            ChatMessage.system("You are helpful"),
            ChatMessage.user("Short message")
        );

        AgentMiddleware.CompactResult result = mw.maybeCompact(history,
            new AgentMiddleware.CompactOptions("generic", "sys")).join();

        assertThat(result).isNull();
    }

    @Test
    void cacheEvictsWhenMaxSizeExceeded() {
        // Very small cache to force eviction
        TokenBudgetMiddleware mw = new TokenBudgetMiddleware(1000, "generic", 5, Duration.ofHours(1));

        // Add many entries to force eviction of older ones
        IntStream.range(0, 100).forEach(i -> mw.recordUsage("thread-" + i, 10, 10));

        // Count how many entries are still accessible
        long accessible = IntStream.range(0, 100)
            .filter(i -> mw.getUsage("thread-" + i) > 0)
            .count();

        // The cache should not retain all 100 entries
        assertThat(accessible).isLessThan(100);
        assertThat(accessible).isLessThanOrEqualTo(5);
    }

    @Test
    void invalidateRemovesThreadUsage() {
        TokenBudgetMiddleware mw = new TokenBudgetMiddleware(1000);
        mw.recordUsage("t1", 100, 50);
        assertThat(mw.getUsage("t1")).isEqualTo(150);

        mw.invalidate("t1");
        assertThat(mw.getUsage("t1")).isZero();
    }

    @Test
    void cacheStatsAvailable() {
        TokenBudgetMiddleware mw = new TokenBudgetMiddleware(1000);
        assertThat(mw.stats()).isNotNull();
        assertThat(mw.estimatedSize()).isZero();
    }
}
