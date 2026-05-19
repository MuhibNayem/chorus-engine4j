package com.chorus.engine.mcp.protocol;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Definition of an MCP resource.
 *
 * @param uri         Unique URI identifying the resource
 * @param name        Human-readable name
 * @param description Optional description
 * @param mimeType    Optional MIME type hint
 */
public record McpResource(
    @NonNull String uri,
    @NonNull String name,
    @Nullable String description,
    @Nullable String mimeType
) {
    public McpResource {
        Objects.requireNonNull(uri, "uri cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
    }
}
