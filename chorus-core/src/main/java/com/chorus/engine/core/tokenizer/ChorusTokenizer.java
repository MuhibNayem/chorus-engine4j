package com.chorus.engine.core.tokenizer;

import java.util.List;

/**
 * Unified tokenizer interface for all LLM models.
 * Supports exact BPE tokenization (for OpenAI, Llama, etc.)
 * and approximate estimation (for Claude, Gemini, etc.).
 */
public interface ChorusTokenizer {

    /**
     * Encode text into token IDs.
     */
    List<Integer> encode(String text);

    /**
     * Encode text, allowing/disallowing special tokens.
     */
    List<Integer> encode(String text, SpecialTokenHandling handling);

    /**
     * Decode token IDs back to text.
     */
    String decode(List<Integer> tokens);

    /**
     * Count tokens in text (faster than encode when you only need the count).
     */
    int countTokens(String text);

    /**
     * Count tokens with special token handling.
     */
    int countTokens(String text, SpecialTokenHandling handling);

    /**
     * Get the vocabulary size.
     */
    int vocabSize();

    /**
     * Get the encoding name (e.g., "cl100k_base", "o200k_base").
     */
    String encodingName();

    /**
     * Whether this is an exact tokenizer (true) or approximate (false).
     */
    boolean isExact();

    /**
     * Special token handling modes.
     */
    enum SpecialTokenHandling {
        RAISE,      // Throw on special tokens
        ALLOW,      // Allow special tokens
        IGNORE      // Treat special tokens as regular text
    }
}
