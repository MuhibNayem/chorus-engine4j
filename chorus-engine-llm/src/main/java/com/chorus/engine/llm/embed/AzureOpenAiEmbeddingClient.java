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
 * Azure OpenAI Embeddings client.
 *
 * <p>Identical to {@link OpenAiEmbeddingClient} in response format, but uses:
 * <ul>
 *   <li>URL: {@code https://{resource}.openai.azure.com/openai/deployments/{deployment}/embeddings?api-version={version}}</li>
 *   <li>Auth: {@code api-key: {key}} header (not Bearer)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * EmbeddingClient client = new AzureOpenAiEmbeddingClient(
 *     "https://my-resource.openai.azure.com",
 *     "text-embedding-3-large",  // deployment name
 *     "2024-02-01",              // API version
 *     System.getenv("AZURE_OPENAI_KEY"),
 *     "text-embedding-3-large",
 *     3072,
 *     RetryPolicy.DEFAULT
 * );
 * }</pre>
 */
public final class AzureOpenAiEmbeddingClient implements EmbeddingClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final String embeddingUrl;
    private final String apiKey;
    private final String defaultModel;
    private final int defaultDimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;

    public AzureOpenAiEmbeddingClient(
        @NonNull String resourceEndpoint,
        @NonNull String deploymentId,
        @NonNull String apiVersion,
        @NonNull String apiKey,
        @NonNull String defaultModel,
        int defaultDimensions,
        @NonNull RetryPolicy retryPolicy
    ) {
        this(resourceEndpoint, deploymentId, apiVersion, apiKey, defaultModel, defaultDimensions,
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build(),
            new ObjectMapper(), retryPolicy);
    }

    public AzureOpenAiEmbeddingClient(
        @NonNull String resourceEndpoint,
        @NonNull String deploymentId,
        @NonNull String apiVersion,
        @NonNull String apiKey,
        @NonNull String defaultModel,
        int defaultDimensions,
        @NonNull HttpClient httpClient,
        @NonNull ObjectMapper objectMapper,
        @NonNull RetryPolicy retryPolicy
    ) {
        String base = resourceEndpoint.endsWith("/") ? resourceEndpoint.substring(0, resourceEndpoint.length() - 1) : resourceEndpoint;
        this.embeddingUrl = base + "/openai/deployments/" + deploymentId + "/embeddings?api-version=" + apiVersion;
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
            if (list.isEmpty()) throw new IllegalStateException("Empty embedding response from Azure OpenAI");
            return list.get(0);
        });
    }

    @Override
    public @NonNull Result<List<float[]>, EmbeddingError> embedBatch(@NonNull List<String> texts, @NonNull EmbedOptions options) {
        int attempt = 0;
        Exception lastError = null;

        while (attempt < retryPolicy.maxAttempts()) {
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("encoding_format", "float");
                if (options.dimensions() > 0) body.put("dimensions", options.dimensions());

                ArrayNode inputs = body.putArray("input");
                for (String t : texts) inputs.add(t);

                // Azure OpenAI ignores model in the body — deployment name in URL determines it
                body.put("model", defaultModel);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingUrl))
                    .header("Content-Type", "application/json")
                    .header("api-key", apiKey)          // Azure auth
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode dataNode = root.get("data");
                    if (dataNode == null || !dataNode.isArray()) {
                        return Result.err(EmbeddingError.of("PARSE_ERROR", "Missing 'data' array in response", "azure-openai"));
                    }
                    List<float[]> results = new ArrayList<>(dataNode.size());
                    for (JsonNode item : dataNode) {
                        JsonNode embNode = item.get("embedding");
                        if (embNode == null || !embNode.isArray()) {
                            return Result.err(EmbeddingError.of("PARSE_ERROR", "Missing 'embedding' array", "azure-openai"));
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
            lastError != null ? lastError.getMessage() : "Max retries exceeded", "azure-openai"));
    }

    @Override
    public @NonNull String providerName() { return "azure-openai"; }

    @Override
    public @NonNull String modelName() { return defaultModel; }

    @Override
    public int nativeDimensions() { return defaultDimensions; }

    @Override
    public boolean isLocal() { return false; }

    @Override
    public @NonNull HealthStatus health() { return HealthStatus.HEALTHY; }

    private void normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += v * v;
        if (sum == 0) return;
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) vec[i] *= (float) inv;
    }
}
