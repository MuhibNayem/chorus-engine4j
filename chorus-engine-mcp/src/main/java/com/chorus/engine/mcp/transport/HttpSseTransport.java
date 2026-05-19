package com.chorus.engine.mcp.transport;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP+SSE transport for remote MCP servers.
 *
 * <p>Uses POST for client-to-server requests and an SSE stream for
 * server-initiated messages (notifications and responses). Built on
 * JDK {@link HttpClient} with zero external HTTP dependencies.
 */
public final class HttpSseTransport implements McpTransport {

    private final URI endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Flow.Subscriber<? super JsonRpcMessage>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Thread> sseThread = new AtomicReference<>();
    private final AtomicLong requestId = new AtomicLong(0);

    public HttpSseTransport(@NonNull URI endpoint, @NonNull ObjectMapper mapper) {
        this(endpoint, HttpClient.newHttpClient(), mapper);
    }

    public HttpSseTransport(@NonNull URI endpoint, @NonNull HttpClient httpClient, @NonNull ObjectMapper mapper) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = Thread.ofVirtual().name("mcp-sse-reader").start(this::sseLoop);
        sseThread.set(thread);
    }

    @Override
    public void send(@NonNull JsonRpcMessage message) {
        Objects.requireNonNull(message, "message");
        if (closed.get()) {
            throw new IllegalStateException("Transport is closed");
        }
        try {
            String body = mapper.writeValueAsString(message);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new RuntimeException(McpError.transportError("HTTP error " + response.statusCode() + ": " + response.body()).toString());
            }
            if (response.body() != null && !response.body().isBlank()) {
                try {
                    JsonRpcMessage result = mapper.readValue(response.body(), JsonRpcMessage.class);
                    for (var sub : subscribers) {
                        sub.onNext(result);
                    }
                } catch (Exception e) {
                    for (var sub : subscribers) {
                        sub.onError(e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(McpError.transportError("Send interrupted", e).toString(), e);
        } catch (Exception e) {
            throw new RuntimeException(McpError.transportError("Failed to send message", e).toString(), e);
        }
    }

    @Override
    public Flow.@NonNull Publisher<JsonRpcMessage> receive() {
        return new SsePublisher();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (var sub : subscribers) {
            sub.onComplete();
        }
        Thread t = sseThread.get();
        if (t != null) {
            t.interrupt();
        }
    }

    private void sseLoop() {
        while (!closed.get()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/sse"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

                HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    Thread.sleep(1000);
                    continue;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    @Nullable String currentData = null;
                    while (!closed.get() && (line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if (currentData == null) {
                                currentData = data;
                            } else {
                                currentData += "\n" + data;
                            }
                        } else if (line.isEmpty()) {
                            if (currentData != null && !currentData.isBlank()) {
                                try {
                                    JsonRpcMessage message = mapper.readValue(currentData, JsonRpcMessage.class);
                                    for (var sub : subscribers) {
                                        sub.onNext(message);
                                    }
                                } catch (Exception e) {
                                    for (var sub : subscribers) {
                                        sub.onError(e);
                                    }
                                }
                            }
                            currentData = null;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!closed.get()) {
                    for (var sub : subscribers) {
                        sub.onError(e);
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        for (var sub : subscribers) {
            sub.onComplete();
        }
    }

    private final class SsePublisher implements Flow.Publisher<JsonRpcMessage> {
        @Override
        public void subscribe(Flow.Subscriber<? super JsonRpcMessage> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean cancelled = new AtomicBoolean(false);

                @Override
                public void request(long n) {
                    // No back-pressure for SSE
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
