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

/**
 * Cohere Embed v3 embedding client.
 *
 * <p>Supports {@code embed-english-v3.0}, {@code embed-multilingual-v3.0}, and later models.
 * Cohere embeddings support input-type discrimination ({@code search_query}, {@code search_document},
 * {@code classification}, {@code clustering}) which improves retrieval quality significantly.
 *
 * <p>Usage:
 * <pre>{@code
 * EmbeddingClient cohere = new CohereEmbeddingClient(
 *     System.getenv("COHERE_API_KEY"),
 *     "embed-english-v3.0",
 *     1024,
 *     RetryPolicy.DEFAULT
 * );
 * }</pre>
 */
public final class CohereEmbeddingClient implements EmbeddingClient {

    private static final String BASE_URL = "https://api.cohere.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey;
    private final String defaultModel;
    private final int defaultDimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;

    public CohereEmbeddingClient(
        @NonNull String apiKey,
        @NonNull String defaultModel,
        int defaultDimensions,
        @NonNull RetryPolicy retryPolicy
    ) {
        this(apiKey, defaultModel, defaultDimensions,
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build(),
            new ObjectMapper(), retryPolicy);
    }

    public CohereEmbeddingClient(
        @NonNull String apiKey,
        @NonNull String defaultModel,
        int defaultDimensions,
        @NonNull HttpClient httpClient,
        @NonNull ObjectMapper objectMapper,
        @NonNull RetryPolicy retryPolicy
    ) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.defaultDimensions = defaultDimensions;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public @NonNull Result<float[], EmbeddingError> embed(@NonNull String text, @NonNull EmbedOptions options) {
        return embedBatch(List.of(text), options).map(list -> {
            if (list.isEmpty()) throw new IllegalStateException("Empty embedding response from Cohere");
            return list.get(0);
        });
    }

    @Override
    public @NonNull Result<List<float[]>, EmbeddingError> embedBatch(@NonNull List<String> texts, @NonNull EmbedOptions options) {
        String model = options.model().isEmpty() ? defaultModel : options.model();
        String inputType = cohereInputType(options.inputType());
        int attempt = 0;
        Exception lastError = null;

        while (attempt < retryPolicy.maxAttempts()) {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("input_type", inputType);
                body.put("embedding_types", objectMapper.createArrayNode().add("float"));

                ArrayNode inputs = body.putArray("texts");
                for (String t : texts) inputs.add(t);

                // Cohere supports truncation to avoid token limit errors
                body.put("truncate", "END");

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/embed"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Client-Name", "chorus-engine4j")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    // Cohere v1 returns embeddings under "embeddings.float" (v3 embed types)
                    JsonNode embeddingsNode = root.path("embeddings").path("float");
                    if (!embeddingsNode.isArray()) {
                        // Fallback: older API format with direct "embeddings" array
                        embeddingsNode = root.path("embeddings");
                    }
                    if (!embeddingsNode.isArray()) {
                        return Result.err(EmbeddingError.of("PARSE_ERROR", "Missing embeddings in response", "cohere"));
                    }

                    List<float[]> results = new ArrayList<>(embeddingsNode.size());
                    for (JsonNode embNode : embeddingsNode) {
                        if (!embNode.isArray()) {
                            return Result.err(EmbeddingError.of("PARSE_ERROR", "Embedding is not an array", "cohere"));
                        }
                        float[] vec = new float[embNode.size()];
                        for (int i = 0; i < embNode.size(); i++) vec[i] = embNode.get(i).floatValue();
                        if (options.normalize()) normalize(vec);
                        results.add(vec);
                    }
                    return Result.ok(results);
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
            lastError != null ? lastError.getMessage() : "Max retries exceeded", "cohere"));
    }

    @Override
    public @NonNull String providerName() { return "cohere"; }

    @Override
    public @NonNull String modelName() { return defaultModel; }

    @Override
    public int nativeDimensions() { return defaultDimensions; }

    @Override
    public boolean isLocal() { return false; }

    @Override
    public @NonNull HealthStatus health() { return HealthStatus.HEALTHY; }

    private @NonNull String cohereInputType(EmbedOptions.@NonNull InputType inputType) {
        return switch (inputType) {
            case QUERY -> "search_query";
            case DOCUMENT -> "search_document";
            default -> "search_document";
        };
    }

    private void normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += v * v;
        if (sum == 0) return;
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) vec[i] *= (float) inv;
    }
}
