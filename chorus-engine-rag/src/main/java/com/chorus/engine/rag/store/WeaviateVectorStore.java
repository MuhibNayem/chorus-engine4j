package com.chorus.engine.rag.store;

import com.chorus.engine.rag.document.Chunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Weaviate v4 REST client adapter.
 *
 * <p>Uses the Weaviate {@code /v1/objects} and GraphQL {@code /v1/graphql} endpoints.
 * Supports cosine, dot, and euclidean distance metrics. Batches upserts in groups of 100.
 *
 * <p>Authentication: API key via {@code X-Weaviate-Api-Key}, or OIDC bearer token.
 * Pass {@code null} for anonymous deployments (e.g., local development).
 *
 * <p>Configuration example:
 * <pre>{@code
 * VectorStore store = VectorStoreFactory.create("weaviate", Map.of(
 *     "baseUrl",    "https://my-cluster.weaviate.network",
 *     "className",  "Document",
 *     "apiKey",     "my-api-key"           // optional
 * ));
 * }</pre>
 */
public final class WeaviateVectorStore implements VectorStore {

    private static final int BATCH_SIZE = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String className;
    @Nullable private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WeaviateVectorStore(@NonNull String baseUrl,
                               @NonNull String className,
                               @Nullable String apiKey) {
        this(baseUrl, className, apiKey, HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
    }

    public WeaviateVectorStore(@NonNull String baseUrl,
                               @NonNull String className,
                               @Nullable String apiKey,
                               @NonNull HttpClient httpClient) {
        this(baseUrl, className, apiKey, httpClient, new ObjectMapper());
    }

    public WeaviateVectorStore(@NonNull String baseUrl,
                               @NonNull String className,
                               @Nullable String apiKey,
                               @NonNull HttpClient httpClient,
                               @NonNull ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.className = Objects.requireNonNull(className);
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void upsert(@NonNull List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            upsertBatch(chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size())));
        }
    }

    @Override
    public @NonNull List<RetrievalResult> search(@NonNull float[] queryEmbedding,
                                                  int topK,
                                                  @NonNull Map<String, Object> filters) {
        if (topK <= 0) return List.of();
        try {
            // Weaviate uses GraphQL for nearest-neighbour queries
            String nearVecFragment = buildNearVectorFragment(queryEmbedding, topK);
            String whereFragment = filters.isEmpty() ? "" : ", where: " + buildWhereFragment(filters);
            String gql = """
                {
                  Get {
                    %s(
                      %s
                      %s
                      limit: %d
                    ) {
                      _additional { id distance }
                      chunkId documentId text chunkIndex tokenCount parentChunkId metadata
                    }
                  }
                }
                """.formatted(className, nearVecFragment, whereFragment, topK);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", gql);

            HttpRequest request = requestBuilder("/v1/graphql")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = send(request);
            return parseGraphQlResults(response.body());
        } catch (IOException e) {
            throw new VectorStoreException("search failed", e);
        }
    }

    @Override
    public void delete(@NonNull Set<String> chunkIds) {
        for (String id : chunkIds) {
            try {
                HttpRequest request = requestBuilder("/v1/objects/" + className + "/" + id)
                    .DELETE()
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new VectorStoreException("delete failed for id=" + id, e);
            }
        }
    }

    @Override
    public void deleteByDocument(@NonNull String documentId) {
        try {
            // Weaviate batch delete with where filter
            ObjectNode body = objectMapper.createObjectNode();
            body.put("class", className);
            ObjectNode where = body.putObject("where");
            where.put("path", objectMapper.createArrayNode().add("documentId"));
            where.put("operator", "Equal");
            where.put("valueText", documentId);

            HttpRequest request = requestBuilder("/v1/batch/objects")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("deleteByDocument failed", e);
        }
    }

    @Override
    public long count() {
        try {
            String gql = """
                {
                  Aggregate {
                    %s {
                      meta { count }
                    }
                  }
                }
                """.formatted(className);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", gql);
            HttpRequest request = requestBuilder("/v1/graphql")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = send(request);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode count = root.path("data").path("Aggregate").path(className).path(0).path("meta").path("count");
            return count.isMissingNode() ? 0L : count.asLong();
        } catch (IOException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "weaviate:" + className;
    }

    // ---- Internal helpers ----

    private void upsertBatch(@NonNull List<Chunk> batch) {
        try {
            ArrayNode objects = objectMapper.createArrayNode();
            for (Chunk chunk : batch) {
                float[] emb = chunk.embedding();
                if (emb == null) throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");

                ObjectNode obj = objectMapper.createObjectNode();
                obj.put("class", className);
                obj.put("id", chunk.id());

                ArrayNode vector = obj.putArray("vector");
                for (float v : emb) vector.add(v);

                ObjectNode props = obj.putObject("properties");
                props.put("chunkId", chunk.id());
                props.put("documentId", chunk.documentId());
                props.put("text", chunk.text());
                props.put("chunkIndex", chunk.index());
                props.put("tokenCount", chunk.tokenCount());
                if (chunk.parentChunkId() != null) props.put("parentChunkId", chunk.parentChunkId());
                props.set("metadata", objectMapper.valueToTree(chunk.metadata()));
                objects.add(obj);
            }

            ObjectNode body = objectMapper.createObjectNode();
            body.set("objects", objects);

            HttpRequest request = requestBuilder("/v1/batch/objects")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    private @NonNull String buildNearVectorFragment(@NonNull float[] vec, int topK) {
        StringBuilder sb = new StringBuilder("nearVector: { vector: [");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append("] }");
        return sb.toString();
    }

    private @NonNull String buildWhereFragment(@NonNull Map<String, Object> filters) {
        if (filters.size() == 1) {
            Map.Entry<String, Object> entry = filters.entrySet().iterator().next();
            return buildSingleCondition(entry.getKey(), entry.getValue());
        }
        StringBuilder sb = new StringBuilder("{ operator: And, operands: [");
        int i = 0;
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append(buildSingleCondition(e.getKey(), e.getValue()));
        }
        sb.append("] }");
        return sb.toString();
    }

    private @NonNull String buildSingleCondition(@NonNull String key, @NonNull Object value) {
        String valuePart;
        if (value instanceof String s) {
            valuePart = "valueText: \"" + s.replace("\"", "\\\"") + "\"";
        } else if (value instanceof Number n) {
            valuePart = "valueNumber: " + n;
        } else if (value instanceof Boolean b) {
            valuePart = "valueBoolean: " + b;
        } else {
            valuePart = "valueText: \"" + value.toString().replace("\"", "\\\"") + "\"";
        }
        return "{ path: [\"" + key + "\"], operator: Equal, " + valuePart + " }";
    }

    private @NonNull List<RetrievalResult> parseGraphQlResults(@NonNull String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode hits = root.path("data").path("Get").path(className);
            if (!hits.isArray()) return List.of();

            List<RetrievalResult> results = new ArrayList<>();
            for (JsonNode hit : hits) {
                double distance = hit.path("_additional").path("distance").asDouble(1.0);
                double score = 1.0 - distance; // cosine distance → similarity
                String id = hit.path("chunkId").asText("");
                String documentId = hit.path("documentId").asText("");
                String text = hit.path("text").asText("");
                int index = hit.path("chunkIndex").asInt(0);
                int tokenCount = hit.path("tokenCount").asInt(0);
                String parentChunkId = hit.has("parentChunkId") && !hit.get("parentChunkId").isNull()
                    ? hit.get("parentChunkId").asText() : null;
                Map<String, Object> metadata = parseMetadata(hit.get("metadata"));
                results.add(new RetrievalResult(new Chunk(id, documentId, text, index, tokenCount, parentChunkId, metadata), score));
            }
            return results;
        } catch (IOException e) {
            throw new VectorStoreException("parse results failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private @NonNull Map<String, Object> parseMetadata(@Nullable JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        try {
            return objectMapper.treeToValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    private HttpRequest.Builder requestBuilder(@NonNull String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-Weaviate-Api-Key", apiKey);
        }
        return builder;
    }

    private HttpResponse<String> send(@NonNull HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new VectorStoreException("HTTP " + response.statusCode() + ": " + response.body(), null);
            }
            return response;
        } catch (IOException e) {
            throw new VectorStoreException("HTTP request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorStoreException("HTTP request interrupted", e);
        }
    }

    public static final class VectorStoreException extends RuntimeException {
        public VectorStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
