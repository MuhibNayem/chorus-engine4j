package com.chorus.engine.mcp.transport;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.Flow;

/**
 * Transport abstraction for MCP communication.
 *
 * <p>Implementations are responsible for the wire-level details (stdio, HTTP+SSE, etc.)
 * while exposing a uniform {@link JsonRpcMessage} interface.
 */
public interface McpTransport {

    /**
     * Start the transport. Idempotent.
     */
    void start();

    /**
     * Send a message. Thread-safe.
     */
    void send(@NonNull JsonRpcMessage message);

    /**
     * Receive messages as a reactive stream.
     */
    Flow.@NonNull Publisher<JsonRpcMessage> receive();

    /**
     * Close the transport and release resources. Idempotent.
     */
    void close();
}
