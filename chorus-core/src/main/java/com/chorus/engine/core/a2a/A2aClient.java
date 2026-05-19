package com.chorus.engine.core.a2a;

import com.chorus.engine.core.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * A2A client for discovering agents via Agent Cards and delegating tasks.
 * Supports W3C Trace Context propagation for distributed tracing across agent boundaries.
 */
public class A2aClient {

    private static final Logger log = LoggerFactory.getLogger(A2aClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final Duration timeout;
    private volatile TraceContext currentTraceContext;

    public A2aClient() {
        this(Duration.ofSeconds(30));
    }

    public A2aClient(Duration timeout) {
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();
    }

    /**
     * Set the trace context to propagate on all outgoing requests.
     */
    public void setTraceContext(TraceContext traceContext) {
        this.currentTraceContext = traceContext;
    }

    /**
     * Fetch an Agent Card from the well-known endpoint.
     */
    public CompletableFuture<AgentCard> fetchAgentCard(String baseUrl) {
        String wellKnownUrl = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + ".well-known/agent-card.json";

        return httpGet(wellKnownUrl).thenApply(body -> {
            try {
                return MAPPER.readValue(body, AgentCard.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse Agent Card from " + wellKnownUrl, e);
            }
        });
    }

    /**
     * Submit a task to an agent.
     */
    public CompletableFuture<A2aTask> submitTask(String taskEndpoint, A2aTask task) {
        return httpPost(taskEndpoint, task).thenApply(body -> {
            try {
                return MAPPER.readValue(body, A2aTask.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse task response", e);
            }
        });
    }

    /**
     * Poll for task status.
     */
    public CompletableFuture<A2aTask> getTask(String taskEndpoint, String taskId) {
        String url = taskEndpoint.endsWith("/") ? taskEndpoint : taskEndpoint + "/";
        url += taskId;
        return httpGet(url).thenApply(body -> {
            try {
                return MAPPER.readValue(body, A2aTask.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse task status", e);
            }
        });
    }

    /**
     * Subscribe to SSE stream for task updates.
     */
    public Flow.Publisher<String> streamTaskUpdates(String taskEndpoint, String taskId) {
        String url = taskEndpoint.endsWith("/") ? taskEndpoint : taskEndpoint + "/";
        url += taskId + "/stream";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Accept", "text/event-stream")
            .GET();

        injectTraceContext(builder);
        HttpRequest request = builder.build();

        return subscriber -> {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    // Simple SSE parsing: split by "data: " lines
                    String body = response.body();
                    for (String line : body.split("\n")) {
                        if (line.startsWith("data: ")) {
                            subscriber.onNext(line.substring(6));
                        }
                    }
                    subscriber.onComplete();
                })
                .exceptionally(ex -> {
                    subscriber.onError(ex);
                    return null;
                });
        };
    }

    private CompletableFuture<String> httpGet(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Accept", "application/json")
            .GET();

        injectTraceContext(builder);
        HttpRequest request = builder.build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() >= 400) {
                    throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                }
                return response.body();
            });
    }

    private CompletableFuture<String> httpPost(String url, Object body) {
        try {
            String json = MAPPER.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

            injectTraceContext(builder);
            HttpRequest request = builder.build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                    }
                    return response.body();
                });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void injectTraceContext(HttpRequest.Builder builder) {
        if (currentTraceContext != null) {
            Map<String, String> headers = new java.util.HashMap<>();
            currentTraceContext.inject(headers);
            headers.forEach(builder::header);
        }
    }
}
