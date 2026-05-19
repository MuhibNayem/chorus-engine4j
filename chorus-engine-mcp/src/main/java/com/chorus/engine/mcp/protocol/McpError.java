package com.chorus.engine.mcp.protocol;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Error type for MCP operations.
 *
 * @param code    JSON-RPC error code
 * @param message Human-readable message
 * @param cause   Optional underlying cause
 */
public record McpError(int code, @NonNull String message, Throwable cause) {
    public McpError {
        Objects.requireNonNull(message, "message cannot be null");
    }

    public static @NonNull McpError parseError(@NonNull String msg) {
        return new McpError(-32700, msg, null);
    }

    public static @NonNull McpError invalidRequest(@NonNull String msg) {
        return new McpError(-32600, msg, null);
    }

    public static @NonNull McpError methodNotFound(@NonNull String msg) {
        return new McpError(-32601, msg, null);
    }

    public static @NonNull McpError invalidParams(@NonNull String msg) {
        return new McpError(-32602, msg, null);
    }

    public static @NonNull McpError internalError(@NonNull String msg) {
        return new McpError(-32603, msg, null);
    }

    public static @NonNull McpError internalError(@NonNull String msg, @NonNull Throwable cause) {
        return new McpError(-32603, msg, cause);
    }

    public static @NonNull McpError transportError(@NonNull String msg) {
        return new McpError(-32000, msg, null);
    }

    public static @NonNull McpError transportError(@NonNull String msg, @NonNull Throwable cause) {
        return new McpError(-32000, msg, cause);
    }
}
