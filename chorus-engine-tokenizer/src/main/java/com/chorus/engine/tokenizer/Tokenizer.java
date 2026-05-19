package com.chorus.engine.tokenizer;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Production-grade tokenizer interface.
 * All implementations are thread-safe and immutable after construction.
 */
public interface Tokenizer {

    /**
     * Encode text into token IDs.
     */
    @NonNull List<Integer> encode(@NonNull String text);

    /**
     * Decode token IDs back to text.
     */
    @NonNull String decode(@NonNull List<Integer> tokens);

    /**
     * Count tokens in text. May be faster than encode().size() for approximate tokenizers.
     */
    int countTokens(@NonNull String text);

    /**
     * Count tokens for a chat message, including role overhead.
     */
    int countChatTokens(@NonNull String role, @NonNull String content);

    /**
     * Name of this tokenizer backend (e.g., "o200k_base", "llama-3", "approximate-claude").
     */
    @NonNull String name();

    /**
     * Vocabulary size. -1 for approximate tokenizers.
     */
    int vocabularySize();
}
