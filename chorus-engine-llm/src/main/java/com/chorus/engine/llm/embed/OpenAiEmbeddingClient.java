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
 * OpenAI-compatible embedding client.
 *
 * <p>Covers: OpenAI, Azure OpenAI, vLLM, SGLang, TGI, llama.cpp server,
 * and any provider speaking the {@code /v1/embeddings} protocol.
 */
public final class OpenAiEmbeddingClient implements EmbeddingClient {

    private final String providerName;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final int defaultDimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;

    public OpenAiEmbeddingClient(
        @NonNull String providerName,
        @NonNull String baseUrl,
        @NonNull String apiKey,
        @NonNull String defaultModel,
        int defaultDimensions,
        @NonNull HttpClient httpClient,
        @NonNull ObjectMapper objectMapper,
        @NonNull RetryPolicy retryPolicy
    ) {
        this.providerName = providerName;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.defaultDimensions = defaultDimensions;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public @NonNull Result<float[], EmbeddingError> embed(@NonNull String text, @NonNull EmbedOptions options) {
        Result<List<float[]>, EmbeddingError> batch = embedBatch(List.of(text), options);
        return batch.map(list -> {
            if (list.isEmpty()) throw new IllegalStateException("Empty embedding response from provider");
            return list.get(0);
        });
    }

    @Override
    public @NonNull Result<List<float[]>, EmbeddingError> embedBatch(@NonNull List<String> texts, @NonNull EmbedOptions options) {
        String model = options.model().isEmpty() ? defaultModel : options.model();
        int attempt = 0;
        Exception lastError = null;

        while (attempt < retryPolicy.maxAttempts()) {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("encoding_format", "float");
                if (options.dimensions() > 0) {
                    body.put("dimensions", options.dimensions());
                }

                ArrayNode inputs = body.putArray("input");
                for (String t : texts) inputs.add(t);

                // Some providers (vLLM, SGLang) support input_type
                if (options.inputType() == EmbedOptions.InputType.QUERY) {
                    body.put("input_type", "query");
                } else if (options.inputType() == EmbedOptions.InputType.DOCUMENT) {
                    body.put("input_type", "document");
                }

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode dataNode = root.get("data");
                    if (dataNode == null || !dataNode.isArray()) {
                        return Result.err(EmbeddingError.of("PARSE_ERROR", "Missing or invalid 'data' array", providerName));
                    }
                    ArrayNode data = (ArrayNode) dataNode;
                    List<float[]> results = new ArrayList<>(data.size());
                    for (JsonNode item : data) {
                        JsonNode embNode = item.get("embedding");
                        if (embNode == null || !embNode.isArray()) {
                            return Result.err(EmbeddingError.of("PARSE_ERROR", "Missing or invalid 'embedding' array", providerName));
                        }
                        ArrayNode arr = (ArrayNode) embNode;
                        float[] vec = new float[arr.size()];
                        for (int i = 0; i < arr.size(); i++) vec[i] = arr.get(i).floatValue();
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
                try { Thread.sleep(retryPolicy.computeDelay(attempt).toMillis()); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        return Result.err(EmbeddingError.of("EMBED_FAILED",
            lastError != null ? lastError.getMessage() : "Max retries exceeded", providerName));
    }

    @Override
    public @NonNull String providerName() { return providerName; }

    @Override
    public @NonNull String modelName() { return defaultModel; }

    @Override
    public int nativeDimensions() { return defaultDimensions; }

    @Override
    public boolean isLocal() { return false; }

    @Override
    public @NonNull HealthStatus health() {
        return HealthStatus.HEALTHY; // Simplified; could ping /v1/models
    }

    private void normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += v * v;
        if (sum == 0) return;
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) vec[i] *= inv;
    }
}
