package com.chorus.engine.a2a.server;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

public record A2aHttpResponse(
    int statusCode,
    @Nullable String body,
    Flow.@Nullable Publisher<String> stream,
    @NonNull Map<String, List<String>> headers
) {
    public A2aHttpResponse {
        headers = Map.copyOf(headers);
    }

    public static @NonNull A2aHttpResponse json(int statusCode, @NonNull String body) {
        return new A2aHttpResponse(statusCode, body, null,
            Map.of("Content-Type", List.of("application/json")));
    }

    public static @NonNull A2aHttpResponse sse(Flow.@NonNull Publisher<String> stream) {
        return new A2aHttpResponse(200, null, stream,
            Map.of(
                "Content-Type", List.of("text/event-stream"),
                "Cache-Control", List.of("no-cache"),
                "Connection", List.of("keep-alive")
            ));
    }
}
