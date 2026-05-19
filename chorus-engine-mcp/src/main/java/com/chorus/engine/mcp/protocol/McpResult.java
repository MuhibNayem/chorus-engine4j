package com.chorus.engine.mcp.protocol;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result types returned by MCP operations.
 */
public final class McpResult {

    private McpResult() {}

    /**
     * Result of calling a tool.
     *
     * @param content List of content items (text, image, or embedded resource)
     * @param isError Whether the tool invocation resulted in an error
     */
    public record CallToolResult(
        @NonNull List<Content> content,
        boolean isError
    ) {
        public CallToolResult {
            Objects.requireNonNull(content, "content cannot be null");
        }

        public static @NonNull CallToolResult text(@NonNull String text) {
            return new CallToolResult(List.of(new Content.TextContent(text)), false);
        }

        public static @NonNull CallToolResult error(@NonNull String text) {
            return new CallToolResult(List.of(new Content.TextContent(text)), true);
        }
    }

    /**
     * Result of reading a resource.
     *
     * @param contents List of resource contents
     */
    public record ReadResourceResult(
        @NonNull List<ResourceContent> contents
    ) {
        public ReadResourceResult {
            Objects.requireNonNull(contents, "contents cannot be null");
        }
    }

    /**
     * Result of getting a prompt.
     *
     * @param messages List of prompt messages
     * @param description Optional description
     */
    public record GetPromptResult(
        @Nullable String description,
        @NonNull List<PromptMessage> messages
    ) {
        public GetPromptResult {
            Objects.requireNonNull(messages, "messages cannot be null");
        }
    }

    /**
     * Union of possible content types in a tool result.
     */
    public sealed interface Content {
        String type();

        record TextContent(@NonNull String text) implements Content {
            @Override public String type() { return "text"; }
            public TextContent { Objects.requireNonNull(text, "text cannot be null"); }
        }

        record ImageContent(@NonNull String data, @NonNull String mimeType) implements Content {
            @Override public String type() { return "image"; }
            public ImageContent {
                Objects.requireNonNull(data, "data cannot be null");
                Objects.requireNonNull(mimeType, "mimeType cannot be null");
            }
        }

        record EmbeddedResourceContent(@NonNull McpResource resource, @Nullable String text, @Nullable String blob) implements Content {
            @Override public String type() { return "resource"; }
            public EmbeddedResourceContent {
                Objects.requireNonNull(resource, "resource cannot be null");
            }
        }
    }

    /**
     * Content of a resource read result.
     */
    public record ResourceContent(
        @NonNull String uri,
        @Nullable String mimeType,
        @Nullable String text,
        @Nullable String blob
    ) {
        public ResourceContent {
            Objects.requireNonNull(uri, "uri cannot be null");
        }
    }

    /**
     * A single message in a prompt result.
     */
    public record PromptMessage(
        @NonNull String role,
        @NonNull Content content
    ) {
        public PromptMessage {
            Objects.requireNonNull(role, "role cannot be null");
            Objects.requireNonNull(content, "content cannot be null");
        }
    }
}
