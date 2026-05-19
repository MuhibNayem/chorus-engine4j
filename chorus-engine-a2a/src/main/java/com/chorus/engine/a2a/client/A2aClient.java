package com.chorus.engine.a2a.client;

import com.chorus.engine.a2a.card.AgentCard;
import com.chorus.engine.a2a.task.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

public final class A2aClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final @Nullable String authToken;
    private final ObjectMapper mapper;

    public A2aClient(HttpClient httpClient, String baseUrl, @Nullable String authToken) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authToken = authToken;
        this.mapper = new ObjectMapper();
    }

    public AgentCard fetchAgentCard() throws IOException, InterruptedException {
        HttpRequest request = buildRequest("/.well-known/agent.json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch agent card: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), AgentCard.class);
    }

    public Task sendTask(Task task) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(task);
        HttpRequest request = buildRequest("/tasks/send")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to send task: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), Task.class);
    }

    public Task getTask(String taskId) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("/tasks/" + encode(taskId))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get task: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), Task.class);
    }

    public Task cancelTask(String taskId) throws IOException, InterruptedException {
        HttpRequest request = buildRequest("/tasks/" + encode(taskId) + "/cancel")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to cancel task: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), Task.class);
    }

    public Flow.Publisher<Task> subscribeToTask(String taskId) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean cancelled = new AtomicBoolean(false);
                private volatile boolean started = false;

                @Override
                public synchronized void request(long n) {
                    if (started || cancelled.get()) return;
                    started = true;

                    Thread.ofVirtual().start(() -> {
                        try {
                            HttpRequest request = buildRequest("/tasks/" + encode(taskId) + "/subscribe")
                                .header("Accept", "text/event-stream")
                                .GET()
                                .build();

                            HttpResponse<InputStream> response = httpClient.send(
                                request, HttpResponse.BodyHandlers.ofInputStream());

                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

                                String line;
                                while ((line = reader.readLine()) != null && !cancelled.get()) {
                                    if (line.startsWith("data: ")) {
                                        String json = line.substring(6);
                                        if ("[DONE]".equals(json)) {
                                            break;
                                        }
                                        Task task = mapper.readValue(json, Task.class);
                                        subscriber.onNext(task);
                                    }
                                }
                            }
                            if (!cancelled.get()) {
                                subscriber.onComplete();
                            }
                        } catch (Exception e) {
                            if (!cancelled.get()) {
                                subscriber.onError(e);
                            }
                        }
                    });
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        };
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json");
        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }
        return builder;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
