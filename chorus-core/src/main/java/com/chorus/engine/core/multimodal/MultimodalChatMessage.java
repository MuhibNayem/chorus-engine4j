package com.chorus.engine.core.multimodal;

import com.chorus.engine.core.event.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extension of ChatMessage that supports multimodal media content.
 * When media is present, the {@code content} field serves as the text prompt
 * accompanying the media.
 */
public record MultimodalChatMessage(
    ChatMessage.Role role,
    String content,
    Optional<String> reasoningContent,
    Optional<List<ChatMessage.ToolCall>> toolCalls,
    Optional<String> toolCallId,
    List<MediaContent> media
) {

    public MultimodalChatMessage {
        media = List.copyOf(media != null ? media : List.of());
    }

    public static MultimodalChatMessage user(String content, List<MediaContent> media) {
        return new MultimodalChatMessage(
            ChatMessage.Role.USER, content,
            Optional.empty(), Optional.empty(), Optional.empty(), media);
    }

    public static MultimodalChatMessage user(String content) {
        return user(content, List.of());
    }

    public static MultimodalChatMessage system(String content) {
        return new MultimodalChatMessage(
            ChatMessage.Role.SYSTEM, content,
            Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    public static MultimodalChatMessage assistant(String content) {
        return new MultimodalChatMessage(
            ChatMessage.Role.ASSISTANT, content,
            Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    /**
     * Convert to plain ChatMessage (drops media).
     */
    public ChatMessage toChatMessage() {
        return new ChatMessage(role, content, reasoningContent, toolCalls, toolCallId);
    }

    /**
     * Convert from plain ChatMessage (no media).
     */
    public static MultimodalChatMessage from(ChatMessage msg) {
        return new MultimodalChatMessage(
            msg.role(), msg.content(), msg.reasoningContent(),
            msg.toolCalls(), msg.toolCallId(), List.of());
    }
}
