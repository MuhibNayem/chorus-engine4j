package com.chorus.engine.core.tokenizer;

import com.chorus.engine.core.event.ChatMessage;

import java.util.List;

/**
 * Utility for estimating token counts across messages, conversations, and code.
 * Provides both exact (when vocabulary available) and approximate counting.
 */
public class TokenCountEstimator {

    private final ChorusTokenizer tokenizer;

    public TokenCountEstimator(String modelName) {
        this(TokenizerRegistry.GLOBAL.get(modelName));
    }

    public TokenCountEstimator(ChorusTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Count tokens in a single text string.
     */
    public int count(String text) {
        return tokenizer.countTokens(text);
    }

    /**
     * Count tokens in a list of chat messages (includes formatting overhead).
     */
    public int countMessages(List<ChatMessage> messages) {
        int total = 0;
        // Add base overhead per message (formatting tokens)
        final int MESSAGE_OVERHEAD = 4;
        // Add base system overhead
        final int SYSTEM_OVERHEAD = 3;

        boolean hasSystem = false;
        for (ChatMessage msg : messages) {
            if (msg.role() == ChatMessage.Role.SYSTEM) {
                hasSystem = true;
            }
            total += tokenizer.countTokens(msg.content());
            total += MESSAGE_OVERHEAD;

            // Tool calls add extra tokens
            if (msg.toolCalls().isPresent()) {
                for (ChatMessage.ToolCall tc : msg.toolCalls().get()) {
                    total += tokenizer.countTokens(tc.name());
                    total += tokenizer.countTokens(tc.arguments());
                    total += 4; // Tool call formatting overhead
                }
            }
        }

        if (hasSystem) {
            total += SYSTEM_OVERHEAD;
        }

        return total;
    }

    /**
     * Count tokens in a conversation thread.
     */
    public int countConversation(List<String> turns) {
        int total = 0;
        for (String turn : turns) {
            total += tokenizer.countTokens(turn);
            total += 4; // Role/formatting overhead per turn
        }
        return total;
    }

    /**
     * Estimate if text fits within a context window.
     */
    public boolean fitsInContext(String text, int contextWindow) {
        return count(text) <= contextWindow;
    }

    /**
     * Estimate if messages fit within a context window.
     */
    public boolean fitsInContext(List<ChatMessage> messages, int contextWindow) {
        return countMessages(messages) <= contextWindow;
    }

    /**
     * Truncate text to fit within max tokens.
     */
    public String truncate(String text, int maxTokens) {
        if (tokenizer.isExact()) {
            List<Integer> tokens = tokenizer.encode(text);
            if (tokens.size() <= maxTokens) {
                return text;
            }
            return tokenizer.decode(tokens.subList(0, maxTokens));
        } else {
            // Approximate truncation by character ratio
            double ratio = (double) text.length() / Math.max(1, tokenizer.countTokens(text));
            int maxChars = (int) (maxTokens * ratio);
            if (maxChars >= text.length()) {
                return text;
            }
            // Try to truncate at word boundary
            int truncateAt = maxChars;
            while (truncateAt > 0 && !Character.isWhitespace(text.charAt(truncateAt - 1))) {
                truncateAt--;
            }
            if (truncateAt < maxChars * 0.8) {
                truncateAt = maxChars; // Fallback to hard cut
            }
            return text.substring(0, truncateAt);
        }
    }

    /**
     * Get the underlying tokenizer.
     */
    public ChorusTokenizer tokenizer() {
        return tokenizer;
    }

    // === Static convenience methods ===

    public static int count(String modelName, String text) {
        return new TokenCountEstimator(modelName).count(text);
    }

    public static int countMessages(String modelName, List<ChatMessage> messages) {
        return new TokenCountEstimator(modelName).countMessages(messages);
    }
}
