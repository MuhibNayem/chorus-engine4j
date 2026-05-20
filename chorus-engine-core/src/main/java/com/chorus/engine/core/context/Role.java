package com.chorus.engine.core.context;

import org.jspecify.annotations.NonNull;

import java.util.Locale;

/**
 * Conversation role. Extensible for custom agentic roles.
 */
public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    public static @NonNull Role fromString(@NonNull String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "system" -> SYSTEM;
            case "user", "human" -> USER;
            case "assistant", "ai", "model" -> ASSISTANT;
            case "tool", "function" -> TOOL;
            default -> throw new IllegalArgumentException("Unknown role: " + s);
        };
    }
}
