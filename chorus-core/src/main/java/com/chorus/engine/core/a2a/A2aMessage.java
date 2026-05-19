package com.chorus.engine.core.a2a;

import java.util.Map;

/**
 * A2A Message — conversational layer between agents.
 */
public record A2aMessage(
    String role, // user, agent
    String content,
    Map<String, Object> parts
) {
    public A2aMessage {
        parts = parts != null ? Map.copyOf(parts) : Map.of();
    }
}
