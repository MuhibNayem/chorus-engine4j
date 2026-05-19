package com.chorus.engine.tokenizer.bpe;

import com.chorus.engine.tokenizer.Tokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

class BpeTokenizerTest {

    @Test
    void simple_tokenizer_counts_consistently() {
        // Build a trivial BPE tokenizer for testing
        // Single-byte tokens 0-255 mapped to themselves
        java.util.Map<List<Byte>, Integer> tokenMap = new java.util.HashMap<>();
        for (int i = 0; i < 256; i++) {
            tokenMap.put(List.of((byte) i), i);
        }

        Tokenizer tokenizer = new BpeTokenizer(
            "test",
            256,
            Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS),
            tokenMap,
            Map.of(),
            100
        );

        // "Hello" → 5 bytes = 5 tokens (each char is ASCII)
        assertThat(tokenizer.countTokens("Hello")).isEqualTo(5);
        assertThat(tokenizer.countTokens("Hello World")).isEqualTo(10); // 5 + 1 space + 5 = but space is a token too... wait
        // Actually the pattern splits on whitespace, so "Hello" = 5, "World" = 5
        assertThat(tokenizer.countTokens("Hello World")).isEqualTo(10);
    }

    @Test
    void encode_decode_roundtrip() {
        java.util.Map<List<Byte>, Integer> tokenMap = new java.util.HashMap<>();
        for (int i = 0; i < 256; i++) {
            tokenMap.put(List.of((byte) i), i);
        }

        Tokenizer tokenizer = new BpeTokenizer(
            "test",
            256,
            Pattern.compile("\\S+|\\s+", Pattern.UNICODE_CHARACTER_CLASS),
            tokenMap,
            Map.of(),
            100
        );

        String text = "Hello, World! 123";
        List<Integer> tokens = tokenizer.encode(text);
        assertThat(tokens).isNotEmpty();
        String decoded = tokenizer.decode(tokens);
        // With single-byte tokens capturing both words and whitespace, decode reconstructs exactly
        assertThat(decoded).isEqualTo(text);
    }

    @Test
    void chat_token_count_includes_overhead() {
        java.util.Map<List<Byte>, Integer> tokenMap = new java.util.HashMap<>();
        for (int i = 0; i < 256; i++) {
            tokenMap.put(List.of((byte) i), i);
        }

        Tokenizer tokenizer = new BpeTokenizer(
            "test",
            256,
            Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS),
            tokenMap,
            Map.of(),
            100
        );

        int chatTokens = tokenizer.countChatTokens("user", "Hello");
        // 3 overhead + 4 (user) + 5 (Hello) = 12
        assertThat(chatTokens).isEqualTo(12);
    }

    @Test
    void special_tokens_encoded_separately() {
        java.util.Map<List<Byte>, Integer> tokenMap = new java.util.HashMap<>();
        for (int i = 0; i < 256; i++) {
            tokenMap.put(List.of((byte) i), i);
        }

        Tokenizer tokenizer = new BpeTokenizer(
            "test",
            256,
            Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS),
            tokenMap,
            Map.of("<|endoftext|>", 999),
            100
        );

        List<Integer> tokens = tokenizer.encode("Hello <|endoftext|> World");
        // Should contain the special token ID
        assertThat(tokens).contains(999);
    }

    @Test
    void approximate_tokenizer_heuristics() {
        com.chorus.engine.tokenizer.approximate.ApproximateTokenizer t =
            new com.chorus.engine.tokenizer.approximate.ApproximateTokenizer("approx-test");

        // ASCII text: ~0.3 tokens per char
        int asciiCount = t.countTokens("Hello World");
        assertThat(asciiCount).isGreaterThan(0);

        // CJK: ~2.2 tokens per char
        int cjkCount = t.countTokens("你好世界");
        assertThat(cjkCount).isGreaterThan(asciiCount);
    }

    @Test
    void tokenizer_registry_mappings() {
        com.chorus.engine.tokenizer.TokenizerRegistry registry =
            new com.chorus.engine.tokenizer.TokenizerRegistry();

        assertThat(registry.forModel("gpt-4o").name()).isEqualTo("o200k_base");
        assertThat(registry.forModel("gpt-4").name()).isEqualTo("cl100k_base");
        assertThat(registry.forModel("llama-3-8b").name()).isEqualTo("llama-3");
        assertThat(registry.forModel("claude-3-opus").name()).isEqualTo("approximate-claude");
        assertThat(registry.forModel("gemini-1.5-pro").name()).isEqualTo("approximate-gemini");
        assertThat(registry.forModel("deepseek-chat").name()).isEqualTo("deepseek");
        assertThat(registry.forModel("qwen-2-7b").name()).isEqualTo("qwen");
    }
}
