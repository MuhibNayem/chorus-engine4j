package com.chorus.engine.llm.embed;

import com.chorus.engine.core.result.Result;
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

/**
 * Ollama embedding client. Configurable base URL, model, and dimensions.
 *
 * <p>Works with any embedding model pulled into Ollama:
 * nomic-embed-text, mxbai-embed-large, snowflake-arctic-embed,
 * bge-m3, all-minilm, etc.
 */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    private final String baseUrl;
    private final String modelName;
    private final int nativeDimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaEmbeddingClient(@NonNull String baseUrl, @NonNull String modelName, int nativeDimensions,
                                 @NonNull HttpClient httpClient, @NonNull ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelName = modelName;
        this.nativeDimensions = nativeDimensions;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public @NonNull Result<float[], EmbeddingError> embed(@NonNull String text, @NonNull EmbedOptions options) {
        return embedBatch(List.of(text), options).map(list -> list.get(0));
    }

    @Override
    public @NonNull Result<List<float[]>, EmbeddingError> embedBatch(@NonNull List<String> texts, @NonNull EmbedOptions options) {
        String model = options.model().isEmpty() ? modelName : options.model();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode inputs = body.putArray("input");
            for (String t : texts) inputs.add(t);
            body.put("truncate", true);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                ArrayNode embeddings = (ArrayNode) root.get("embeddings");
                List<float[]> results = new ArrayList<>();
                for (JsonNode emb : embeddings) {
                    float[] vec = jsonToFloatArray(emb);
                    if (options.normalize()) normalize(vec);
                    results.add(vec);
                }
                return Result.ok(results);
            }

            if (response.statusCode() == 404 && texts.size() == 1) {
                Result<float[], EmbeddingError> single = legacySingleEmbed(texts.get(0), model, options);
                return single.map(f -> List.of(f));
            }

            return Result.err(EmbeddingError.of("OLLAMA_ERROR",
                "HTTP " + response.statusCode() + ": " + response.body(), providerName()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Result.err(EmbeddingError.of("NETWORK_ERROR", e.getMessage(), providerName()));
        }
    }

    private @NonNull Result<float[], EmbeddingError> legacySingleEmbed(@NonNull String text, @NonNull String model, @NonNull EmbedOptions options) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", text);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embeddings"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                float[] vec = jsonToFloatArray(objectMapper.readTree(response.body()).get("embedding"));
                if (options.normalize()) normalize(vec);
                return Result.ok(vec);
            }
            return Result.err(EmbeddingError.of("OLLAMA_ERROR", "HTTP " + response.statusCode(), providerName()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Result.err(EmbeddingError.of("NETWORK_ERROR", e.getMessage(), providerName()));
        }
    }

    private float[] jsonToFloatArray(JsonNode node) {
        if (node == null || !node.isArray()) return new float[0];
        float[] arr = new float[node.size()];
        for (int i = 0; i < node.size(); i++) arr[i] = node.get(i).floatValue();
        return arr;
    }

    private static void normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += v * v;
        if (sum == 0) return;
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) vec[i] *= inv;
    }

    @Override public @NonNull String providerName() { return "ollama"; }
    @Override public @NonNull String modelName() { return modelName; }
    @Override public int nativeDimensions() { return nativeDimensions; }
    @Override public boolean isLocal() { return true; }

    @Override
    public @NonNull HealthStatus health() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? HealthStatus.HEALTHY : HealthStatus.UNAVAILABLE;
        } catch (Exception e) { return HealthStatus.UNAVAILABLE; }
    }
}
