package com.chorus.engine.llm.embed;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Generic HTTP embedding client for ANY OpenAI-compatible embedding API.
 *
 * <p>Works with: OpenAI, Azure OpenAI, vLLM, SGLang, TGI, llama.cpp server,
 * LocalAI, FastEmbed-API, Fireworks, Together, Anyscale, Baseten, Replicate,
 * or any custom endpoint speaking {@code POST /v1/embeddings}.
 *
 * <p>Fully configurable via {@link Config} — no hardcoded URLs, models, or auth.
 * The user provides the base URL, model name, auth header, and dimension count.
 */
public final class HttpEmbeddingClient implements EmbeddingClient {

    private final Config config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;

    public HttpEmbeddingClient(@NonNull Config config, @NonNull HttpClient httpClient,
                               @NonNull ObjectMapper objectMapper, @NonNull RetryPolicy retryPolicy) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public @NonNull Result<float[], EmbeddingError> embed(@NonNull String text, @NonNull EmbedOptions options) {
        return embedBatch(List.of(text), options).map(list -> list.get(0));
    }

    @Override
    public @NonNull Result<List<float[]>, EmbeddingError> embedBatch(@NonNull List<String> texts, @NonNull EmbedOptions options) {
        int attempt = 0;
        Exception lastError = null;

        while (attempt < retryPolicy.maxAttempts()) {
            try {
                ObjectNode body = config.bodyBuilder.apply(objectMapper, new BodyContext(texts, options));

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl + config.endpointPath))
                    .header("Content-Type", "application/json")
                    .timeout(config.timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));

                for (Map.Entry<String, String> h : config.headers.entrySet()) {
                    reqBuilder.header(h.getKey(), h.getValue());
                }

                HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 200) {
                    return config.responseParser.apply(objectMapper, response.body())
                        .map(embs -> {
                            if (options.normalize()) embs.forEach(HttpEmbeddingClient::normalize);
                            return embs;
                        })
                        .mapErr(e -> new EmbeddingError("PARSE_ERROR", e, config.providerName, 0));
                }

                lastError = new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                if (!retryPolicy.isRetryable(response.statusCode())) break;

            } catch (IOException | InterruptedException e) {
                lastError = e;
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }

            attempt++;
            if (attempt < retryPolicy.maxAttempts()) {
                try { Thread.sleep(retryPolicy.computeDelay(attempt).toMillis()); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        return Result.err(EmbeddingError.of("EMBED_FAILED",
            lastError != null ? lastError.getMessage() : "Max retries exceeded", config.providerName));
    }

    @Override public @NonNull String providerName() { return config.providerName; }
    @Override public @NonNull String modelName() { return config.modelName; }
    @Override public int nativeDimensions() { return config.nativeDimensions; }
    @Override public boolean isLocal() { return config.isLocal; }

    @Override
    public @NonNull HealthStatus health() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl + config.healthPath))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? HealthStatus.HEALTHY : HealthStatus.DEGRADED;
        } catch (Exception e) {
            return HealthStatus.UNAVAILABLE;
        }
    }

    // ---- Configuration ----

    public record Config(
        @NonNull String providerName,
        @NonNull String modelName,
        @NonNull String baseUrl,
        @NonNull String endpointPath,
        @NonNull String healthPath,
        @NonNull Map<String, String> headers,
        int nativeDimensions,
        boolean isLocal,
        @NonNull Duration timeout,
        @NonNull BiFunction<ObjectMapper, BodyContext, ObjectNode> bodyBuilder,
        @NonNull BiFunction<ObjectMapper, String, Result<List<float[]>, String>> responseParser
    ) {
        public Config {
            headers = Map.copyOf(headers);
            if (!baseUrl.endsWith("/")) baseUrl = baseUrl + "/";
            if (endpointPath.startsWith("/")) endpointPath = endpointPath.substring(1);
        }
    }

    public record BodyContext(@NonNull List<String> texts, @NonNull EmbedOptions options) {}

    /**
     * Pre-built body builder for standard OpenAI-compatible APIs.
     */
    public static @NonNull BiFunction<ObjectMapper, BodyContext, ObjectNode> openAiBodyBuilder() {
        return (mapper, ctx) -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", ctx.options().model().isEmpty() ? "text-embedding-3-large" : ctx.options().model());
            body.put("encoding_format", "float");
            if (ctx.options().dimensions() > 0) body.put("dimensions", ctx.options().dimensions());
            ArrayNode inputs = body.putArray("input");
            for (String t : ctx.texts()) inputs.add(t);
            if (ctx.options().inputType() == EmbedOptions.InputType.QUERY) body.put("input_type", "query");
            if (ctx.options().inputType() == EmbedOptions.InputType.DOCUMENT) body.put("input_type", "document");
            return body;
        };
    }

    /**
     * Pre-built body builder for Cohere-compatible APIs.
     */
    public static @NonNull BiFunction<ObjectMapper, BodyContext, ObjectNode> cohereBodyBuilder() {
        return (mapper, ctx) -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", ctx.options().model().isEmpty() ? "embed-v4" : ctx.options().model());
            ArrayNode textsArr = body.putArray("texts");
            for (String t : ctx.texts()) textsArr.add(t);
            body.put("input_type", ctx.options().inputType() == EmbedOptions.InputType.QUERY ? "search_query" : "search_document");
            body.putArray("embedding_types").add("float");
            return body;
        };
    }

    /**
     * Pre-built response parser for standard OpenAI-compatible JSON.
     */
    public static @NonNull BiFunction<ObjectMapper, String, Result<List<float[]>, String>> openAiResponseParser() {
        return (mapper, body) -> {
            try {
                JsonNode root = mapper.readTree(body);
                ArrayNode data = (ArrayNode) root.get("data");
                List<float[]> results = new ArrayList<>(data.size());
                for (JsonNode item : data) {
                    ArrayNode arr = (ArrayNode) item.get("embedding");
                    float[] vec = new float[arr.size()];
                    for (int i = 0; i < arr.size(); i++) vec[i] = arr.get(i).floatValue();
                    results.add(vec);
                }
                return Result.ok(results);
            } catch (Exception e) {
                return Result.err(e.getMessage());
            }
        };
    }

    /**
     * Pre-built response parser for Cohere JSON.
     */
    public static @NonNull BiFunction<ObjectMapper, String, Result<List<float[]>, String>> cohereResponseParser() {
        return (mapper, body) -> {
            try {
                JsonNode root = mapper.readTree(body);
                ArrayNode embeddings = (ArrayNode) root.get("embeddings").get("float");
                List<float[]> results = new ArrayList<>(embeddings.size());
                for (JsonNode emb : embeddings) {
                    float[] vec = new float[emb.size()];
                    for (int i = 0; i < emb.size(); i++) vec[i] = emb.get(i).floatValue();
                    results.add(vec);
                }
                return Result.ok(results);
            } catch (Exception e) {
                return Result.err(e.getMessage());
            }
        };
    }

    private static void normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += v * v;
        if (sum == 0) return;
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) vec[i] *= inv;
    }
}
