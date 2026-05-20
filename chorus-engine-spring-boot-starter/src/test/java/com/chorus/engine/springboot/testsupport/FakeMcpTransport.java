package com.chorus.engine.springboot.testsupport;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.transport.McpTransport;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Hand-written fake MCP transport for testing.
 */
public final class FakeMcpTransport implements McpTransport {

    private boolean started = false;
    private boolean closed = false;
    private final SubmissionPublisher<JsonRpcMessage> receivePublisher = new SubmissionPublisher<>();

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void send(@NonNull JsonRpcMessage message) {
        // No-op for testing
    }

    @Override
    public Flow.@NonNull Publisher<JsonRpcMessage> receive() {
        return receivePublisher;
    }

    @Override
    public void close() {
        closed = true;
        receivePublisher.close();
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isClosed() {
        return closed;
    }
}
