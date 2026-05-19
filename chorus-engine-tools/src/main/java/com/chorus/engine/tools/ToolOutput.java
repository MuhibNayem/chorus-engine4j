package com.chorus.engine.tools;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Output of a tool execution.
 *
 * @param content        Human-readable text output (always present)
 * @param structuredData Optional structured payload (maps, lists, scalars)
 */
public record ToolOutput(@NonNull String content, @Nullable Map<String, Object> structuredData) {

    public ToolOutput {
        if (content == null) {
            throw new NullPointerException("content cannot be null");
        }
    }

    public static @NonNull ToolOutput of(@NonNull String content) {
        return new ToolOutput(content, null);
    }
}
