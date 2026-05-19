package com.chorus.engine.mcp.transport;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stdio transport for local MCP servers.
 *
 * <p>Launches the server as a subprocess via {@link ProcessBuilder} and communicates
 * via line-delimited JSON on the child's stdin/stdout. Stderr is forwarded to the
 * parent process stderr.
 */
public final class StdioTransport implements McpTransport {

    private final List<String> command;
    private final ObjectMapper mapper;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private final CopyOnWriteArrayList<Flow.Subscriber<? super JsonRpcMessage>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Thread> readerThread = new AtomicReference<>();
    private volatile PrintWriter writer;

    public StdioTransport(@NonNull List<String> command, @NonNull ObjectMapper mapper) {
        this.command = List.copyOf(command);
        this.mapper = mapper;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            processRef.set(process);
            this.writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);

            Thread thread = Thread.ofVirtual().name("mcp-stdio-reader").start(this::readLoop);
            readerThread.set(thread);
        } catch (IOException e) {
            started.set(false);
            throw new RuntimeException(McpError.transportError("Failed to start stdio transport", e).toString(), e);
        }
    }

    @Override
    public void send(@NonNull JsonRpcMessage message) {
        if (closed.get()) {
            throw new IllegalStateException("Transport is closed");
        }
        PrintWriter w = writer;
        if (w == null) {
            throw new IllegalStateException("Transport not started");
        }
        try {
            String line = mapper.writeValueAsString(message);
            w.println(line);
        } catch (Exception e) {
            throw new RuntimeException(McpError.transportError("Failed to serialize message", e).toString(), e);
        }
    }

    @Override
    public Flow.@NonNull Publisher<JsonRpcMessage> receive() {
        return new StdioPublisher();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        PrintWriter w = writer;
        if (w != null) {
            w.close();
        }
        Process process = processRef.get();
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        for (var sub : subscribers) {
            sub.onComplete();
        }
        Thread t = readerThread.get();
        if (t != null) {
            t.interrupt();
        }
    }

    private void readLoop() {
        Process process = processRef.get();
        if (process == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonRpcMessage message = mapper.readValue(line, JsonRpcMessage.class);
                    for (var sub : subscribers) {
                        sub.onNext(message);
                    }
                } catch (Exception e) {
                    for (var sub : subscribers) {
                        sub.onError(e);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                for (var sub : subscribers) {
                    sub.onError(e);
                }
            }
        } finally {
            for (var sub : subscribers) {
                sub.onComplete();
            }
        }
    }

    private final class StdioPublisher implements Flow.Publisher<JsonRpcMessage> {
        @Override
        public void subscribe(Flow.Subscriber<? super JsonRpcMessage> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean cancelled = new AtomicBoolean(false);

                @Override
                public void request(long n) {
                    // Back-pressure not applicable for stdio — messages arrive as they come
                }

                @Override
                public void cancel() {
                    if (cancelled.compareAndSet(false, true)) {
                        subscribers.remove(subscriber);
                    }
                }
            });
            subscribers.add(subscriber);
        }
    }
}
