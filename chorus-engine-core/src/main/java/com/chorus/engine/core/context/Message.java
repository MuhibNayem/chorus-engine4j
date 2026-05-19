package com.chorus.engine.core.context;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable chat message. Carries role, content, optional name/tool call ID,
 * and metadata for token accounting.
 */
public record Message(
    @NonNull Role role,
    @NonNull String content,
    @Nullable String name,
    @Nullable String toolCallId,
    @Nullable Map<String, Object> metadata
) {

    public Message {
        Objects.requireNonNull(role, "role cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
        metadata = metadata != null ? Map.copyOf(metadata) : null;
    }

    public static @NonNull Message system(@NonNull String content) {
        return new Message(Role.SYSTEM, content, null, null, null);
    }

    public static @NonNull Message user(@NonNull String content) {
        return new Message(Role.USER, content, null, null, null);
    }

    public static @NonNull Message user(@NonNull String content, @NonNull String name) {
        return new Message(Role.USER, content, name, null, null);
    }

    public static @NonNull Message assistant(@NonNull String content) {
        return new Message(Role.ASSISTANT, content, null, null, null);
    }

    public static @NonNull Message tool(@NonNull String content, @NonNull String toolCallId) {
        return new Message(Role.TOOL, content, null, toolCallId, null);
    }

    public @NonNull Message withContent(@NonNull String newContent) {
        return new Message(role, newContent, name, toolCallId, metadata);
    }

    public @NonNull Message withMetadata(@NonNull Map<String, Object> newMetadata) {
        return new Message(role, content, name, toolCallId, newMetadata);
    }
}
