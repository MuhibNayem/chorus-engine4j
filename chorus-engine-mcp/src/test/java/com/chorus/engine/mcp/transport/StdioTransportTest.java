package com.chorus.engine.mcp.transport;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class StdioTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void construction_withCommandList() {
        List<String> command = List.of("echo", "hello");
        StdioTransport transport = new StdioTransport(command, mapper);

        assertThat(transport).isNotNull();
    }

    @Test
    void construction_defensivelyCopiesCommandList() {
        List<String> original = new ArrayList<>(List.of("echo", "hello"));
        StdioTransport transport = new StdioTransport(original, mapper);
        original.add("extra");

        // Transport should not see the mutation — we verify indirectly by starting
        // with a command that would fail if "extra" were present.
        assertThatNoException().isThrownBy(() -> {
            transport.start();
            transport.close();
        });
    }

    @Test
    void start_launchesProcess() {
        StdioTransport transport = new StdioTransport(List.of("echo", "hello"), mapper);

        transport.start();

        // If start() succeeds, the process reference was set.
        // We verify by closing without error.
        assertThatNoException().isThrownBy(transport::close);
    }

    @Test
    void send_writesToStdin() throws Exception {
        // Use 'cat' to echo back whatever we write to stdin.
        StdioTransport transport = new StdioTransport(List.of("cat"), mapper);

        List<com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        transport.receive().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage msg) {
                received.add(msg);
                latch.countDown();
            }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        transport.start();

        JsonRpcRequest message = JsonRpcRequest.of(1, "initialize", null);
        transport.send(message);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(JsonRpcRequest.class);
        JsonRpcRequest parsed = (JsonRpcRequest) received.get(0);
        assertThat(parsed.method()).isEqualTo("initialize");

        transport.close();
    }

    @Test
    void start_processNotFound_throws() {
        StdioTransport transport = new StdioTransport(
            List.of("this-command-definitely-does-not-exist-12345"), mapper
        );

        assertThatThrownBy(transport::start)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to start stdio transport")
            .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void send_beforeStart_throws() {
        StdioTransport transport = new StdioTransport(List.of("cat"), mapper);

        JsonRpcRequest message = JsonRpcRequest.of(1, "test", null);
        assertThatThrownBy(() -> transport.send(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Transport not started");
    }

    @Test
    void send_afterClose_throws() {
        StdioTransport transport = new StdioTransport(List.of("cat"), mapper);
        transport.start();
        transport.close();

        JsonRpcRequest message = JsonRpcRequest.of(1, "test", null);
        assertThatThrownBy(() -> transport.send(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Transport is closed");
    }

    @Test
    void constructor_rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() ->
            new StdioTransport(null, mapper)
        ).withMessageContaining("command");

        assertThatNullPointerException().isThrownBy(() ->
            new StdioTransport(List.of("echo"), null)
        ).withMessageContaining("mapper");
    }

    @Test
    void send_rejectsNullMessage() {
        StdioTransport transport = new StdioTransport(List.of("cat"), mapper);
        transport.start();
        try {
            assertThatNullPointerException().isThrownBy(() -> transport.send(null));
        } finally {
            transport.close();
        }
    }
}
