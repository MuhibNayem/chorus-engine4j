package com.chorus.engine.a2a.server;

import com.chorus.engine.a2a.task.Task;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

public final class A2aHttpHandler {

    private final A2aServer server;
    private final ObjectMapper mapper;

    public A2aHttpHandler(@NonNull A2aServer server) {
        this(server, new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL));
    }

    public A2aHttpHandler(@NonNull A2aServer server, @NonNull ObjectMapper mapper) {
        this.server = server;
        this.mapper = mapper;
    }

    public @NonNull A2aHttpResponse handle(@NonNull HttpRequest request) {
        String path = request.uri().getPath();
        String method = request.method();

        try {
            if ("GET".equals(method) && "/.well-known/agent.json".equals(path)) {
                return A2aHttpResponse.json(200, mapper.writeValueAsString(server.getAgentCard()));
            }

            if ("POST".equals(method) && "/tasks/send".equals(path)) {
                String body = readBody(request);
                Task task = mapper.readValue(body, Task.class);
                Task result = server.onSendTask(task);
                return A2aHttpResponse.json(200, mapper.writeValueAsString(result));
            }

            if ("GET".equals(method) && path.startsWith("/tasks/")) {
                String remainder = path.substring("/tasks/".length());
                if (!remainder.isEmpty() && !remainder.contains("/")) {
                    Task result = server.onGetTask(remainder);
                    return A2aHttpResponse.json(200, mapper.writeValueAsString(result));
                }
            }

            if ("POST".equals(method) && path.startsWith("/tasks/")) {
                String remainder = path.substring("/tasks/".length());
                if (remainder.endsWith("/cancel")) {
                    String taskId = remainder.substring(0, remainder.length() - "/cancel".length());
                    if (!taskId.isEmpty() && !taskId.contains("/")) {
                        Task result = server.onCancelTask(taskId);
                        return A2aHttpResponse.json(200, mapper.writeValueAsString(result));
                    }
                }
            }

            if (path.startsWith("/tasks/") && path.endsWith("/subscribe")) {
                String remainder = path.substring("/tasks/".length());
                String taskId = remainder.substring(0, remainder.length() - "/subscribe".length());
                if (!taskId.isEmpty() && !taskId.contains("/")) {
                    Flow.Publisher<TaskUpdate> publisher = server.onSubscribeTask(taskId);
                    return A2aHttpResponse.sse(adaptToSse(publisher));
                }
            }

            return A2aHttpResponse.json(404, "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            return A2aHttpResponse.json(500, "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}");
        }
    }

    private static @NonNull String readBody(@NonNull HttpRequest request) throws java.io.IOException, InterruptedException {
        java.util.Optional<HttpRequest.BodyPublisher> publisherOpt = request.bodyPublisher();
        if (publisherOpt.isEmpty()) {
            return "";
        }
        HttpRequest.BodyPublisher publisher = publisherOpt.get();

        CompletableFuture<byte[]> future = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            private final List<ByteBuffer> buffers = new ArrayList<>();
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                buffers.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                int total = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
                byte[] result = new byte[total];
                int pos = 0;
                for (ByteBuffer buf : buffers) {
                    int len = buf.remaining();
                    buf.get(result, pos, len);
                    pos += len;
                }
                future.complete(result);
            }
        });

        try {
            return new String(future.get(), StandardCharsets.UTF_8);
        } catch (ExecutionException e) {
            throw new java.io.IOException("Failed to read request body", e.getCause());
        }
    }

    private Flow.@NonNull Publisher<String> adaptToSse(Flow.@NonNull Publisher<TaskUpdate> publisher) {
        return subscriber -> publisher.subscribe(new Flow.Subscriber<TaskUpdate>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        s.request(n);
                    }

                    @Override
                    public void cancel() {
                        s.cancel();
                    }
                });
            }

            @Override
            public void onNext(TaskUpdate update) {
                try {
                    String json = mapper.writeValueAsString(update);
                    subscriber.onNext("data: " + json + "\n\n");
                } catch (JsonProcessingException e) {
                    subscriber.onError(e);
                    subscription.cancel();
                }
            }

            @Override
            public void onError(Throwable t) {
                subscriber.onError(t);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }
}
