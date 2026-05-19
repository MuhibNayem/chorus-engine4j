package com.chorus.engine.core.event;

import java.util.List;
import java.util.Optional;

public record ChatMessage(
    Role role,
    String content,
    Optional<String> reasoningContent,
    Optional<List<ToolCall>> toolCalls,
    Optional<String> toolCallId
) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public record ToolCall(String id, String name, String arguments) {}

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, Optional.empty(), Optional.of(toolCalls), Optional.empty());
    }

    public static ChatMessage assistantWithReasoning(String content, String reasoning) {
        return new ChatMessage(Role.ASSISTANT, content, Optional.of(reasoning), Optional.empty(), Optional.empty());
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, Optional.empty(), Optional.empty(), Optional.of(toolCallId));
    }
}
