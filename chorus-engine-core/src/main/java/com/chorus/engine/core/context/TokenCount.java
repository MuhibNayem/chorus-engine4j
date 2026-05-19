package com.chorus.engine.core.context;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Immutable token accounting for a single LLM interaction.
 */
public record TokenCount(
    int inputTokens,
    int outputTokens,
    @NonNull String tokenizerName
) {
    public TokenCount {
        if (inputTokens < 0) throw new IllegalArgumentException("inputTokens must be >= 0");
        if (outputTokens < 0) throw new IllegalArgumentException("outputTokens must be >= 0");
        Objects.requireNonNull(tokenizerName);
    }

    public int total() { return inputTokens + outputTokens; }

    public @NonNull TokenCount plus(@NonNull TokenCount other) {
        if (!tokenizerName.equals(other.tokenizerName)) {
            throw new IllegalArgumentException("Cannot combine token counts from different tokenizers");
        }
        return new TokenCount(inputTokens + other.inputTokens, outputTokens + other.outputTokens, tokenizerName);
    }

    public @NonNull TokenCount withOutput(int newOutput) {
        return new TokenCount(inputTokens, newOutput, tokenizerName);
    }
}
