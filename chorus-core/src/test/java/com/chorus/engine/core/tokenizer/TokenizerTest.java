package com.chorus.engine.core.tokenizer;

import com.chorus.engine.core.event.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizerTest {

    @Test
    void testApproximateTokenizers() {
        // English text
        String english = "The quick brown fox jumps over the lazy dog.";

        var claude = ApproximateTokenizer.claude();
        int claudeTokens = claude.countTokens(english);
        assertThat(claudeTokens).isBetween(8, 16); // ~42 chars / 3.5 = ~12

        var gemini = ApproximateTokenizer.gemini();
        int geminiTokens = gemini.countTokens(english);
        assertThat(geminiTokens).isBetween(8, 16); // ~42 chars / 3.8 = ~11

        var qwen = ApproximateTokenizer.qwen();
        int qwenTokens = qwen.countTokens(english);
        assertThat(qwenTokens).isBetween(8, 16); // ~42 chars / 3.5 = ~12
    }

    @Test
    void testChineseText() {
        String chinese = "人工智能正在改变世界。";

        var qwen = ApproximateTokenizer.qwen();
        int qwenTokens = qwen.countTokens(chinese);
        // 9 CJK chars / 3.2 = ~3 tokens
        assertThat(qwenTokens).isBetween(2, 6);

        var claude = ApproximateTokenizer.claude();
        int claudeTokens = claude.countTokens(chinese);
        // 9 CJK chars / 2.5 = ~4 tokens
        assertThat(claudeTokens).isBetween(2, 6);
    }

    @Test
    void testCodeText() {
        String code = "public static void main(String[] args) { System.out.println(\"Hello\"); }";

        var claude = ApproximateTokenizer.claude();
        int claudeTokens = claude.countTokens(code);
        // Code is typically more token-dense
        assertThat(claudeTokens).isGreaterThan(5);
    }

    @Test
    void testRegistryLookup() {
        var registry = new TokenizerRegistry();

        assertThat(registry.hasTokenizer("gpt-4")).isTrue();
        assertThat(registry.hasTokenizer("claude-opus-4-7")).isTrue();
        assertThat(registry.hasTokenizer("gemini-2-5-pro")).isTrue();
        assertThat(registry.hasTokenizer("llama-4")).isTrue();
        assertThat(registry.hasTokenizer("qwen-3")).isTrue();
        assertThat(registry.hasTokenizer("deepseek-v3")).isTrue();
        assertThat(registry.hasTokenizer("grok-4")).isTrue();
        assertThat(registry.hasTokenizer("kimi-k2-6")).isTrue();
        assertThat(registry.hasTokenizer("minimax")).isTrue();
        assertThat(registry.hasTokenizer("xiaomi-mimo")).isTrue();

        // Unknown model falls back to generic
        var unknown = registry.get("unknown-model-xyz");
        assertThat(unknown.isExact()).isFalse();
        assertThat(unknown.encodingName()).isEqualTo("generic");
    }

    @Test
    void testTokenCountEstimator() {
        String text = "Hello world, this is a test message for token counting.";

        int count = TokenCountEstimator.count("gpt-4", text);
        assertThat(count).isGreaterThan(0);

        var estimator = new TokenCountEstimator("claude");
        assertThat(estimator.count(text)).isGreaterThan(0);
        assertThat(estimator.fitsInContext(text, 1000)).isTrue();
        assertThat(estimator.fitsInContext(text, 5)).isFalse();
    }

    @Test
    void testMessageCounting() {
        var messages = List.of(
            ChatMessage.system("You are a helpful assistant."),
            ChatMessage.user("What is the capital of France?"),
            ChatMessage.assistant("The capital of France is Paris.")
        );

        int count = TokenCountEstimator.countMessages("claude", messages);
        assertThat(count).isGreaterThan(10); // Should be more than raw tokens due to overhead
    }

    @Test
    void testTruncate() {
        String longText = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        var estimator = new TokenCountEstimator("claude");

        String truncated = estimator.truncate(longText, 10);
        assertThat(truncated.length()).isLessThan(longText.length());

        // Should fit within limit
        assertThat(estimator.count(truncated)).isLessThanOrEqualTo(12); // Allow small margin
    }

    @Test
    void testEmptyText() {
        var tokenizer = ApproximateTokenizer.claude();
        assertThat(tokenizer.countTokens("")).isEqualTo(0);
        assertThat(tokenizer.encode("")).isEmpty();
    }

    @Test
    void testRegistryGlobal() {
        // Global registry should have defaults
        assertThat(TokenizerRegistry.GLOBAL.hasTokenizer("gpt-4o")).isTrue();
        assertThat(TokenizerRegistry.GLOBAL.hasTokenizer("claude-sonnet-4-6")).isTrue();
    }
}
