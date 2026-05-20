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
 * Qdrant HTTP client adapter.
 *
 * <p>Uses JDK {@link HttpClient} with Jackson for JSON. Batches upserts in groups of 100.
 * Supports Cosine, Euclid (L2), and Dot (inner product) distance metrics.
 */
public final class QdrantVectorStore implements VectorStore {

    private static final int BATCH_SIZE = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String collectionName;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public QdrantVectorStore(@NonNull String baseUrl,
                             @NonNull String collectionName,
                             @Nullable String apiKey) {
        this(baseUrl, collectionName, apiKey, HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
    }

    public QdrantVectorStore(@NonNull String baseUrl,
                             @NonNull String collectionName,
                             @Nullable String apiKey,
                             @NonNull HttpClient httpClient) {
        this(baseUrl, collectionName, apiKey, httpClient, new ObjectMapper());
    }

    public QdrantVectorStore(@NonNull String baseUrl,
                             @NonNull String collectionName,
                             @Nullable String apiKey,
                             @NonNull HttpClient httpClient,
                             @NonNull ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collectionName = Objects.requireNonNull(collectionName);
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    // ---- VectorStore implementation ----

    @Override
    public void upsert(@NonNull List<Chunk> chunks) {
        if (chunks.isEmpty()) return;

        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            upsertBatch(batch);
        }
    }

    @Override
    public @NonNull List<RetrievalResult> search(@NonNull float[] queryEmbedding, int topK, @NonNull Map<String, Object> filters) {
        if (topK <= 0) return List.of();

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("limit", topK);
            body.put("with_payload", true);
            body.put("with_vector", false);

            ArrayNode vector = body.putArray("vector");
            for (float v : queryEmbedding) vector.add(v);

            if (!filters.isEmpty()) {
                body.set("filter", buildQdrantFilter(filters));
            }

            HttpRequest request = requestBuilder("/collections/" + collectionName + "/points/search")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = send(request);
            return parseSearchResults(response.body());
        } catch (IOException e) {
            throw new VectorStoreException("search failed", e);
        }
    }

    @Override
    public void delete(@NonNull Set<String> chunkIds) {
        if (chunkIds.isEmpty()) return;

        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode points = body.putArray("points");
            for (String id : chunkIds) points.add(id);

            HttpRequest request = requestBuilder("/collections/" + collectionName + "/points/delete")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("delete failed", e);
        }
    }

    @Override
    public void deleteByDocument(@NonNull String documentId) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode filter = objectMapper.createObjectNode();
            ObjectNode must = objectMapper.createObjectNode();
            must.put("key", "document_id");
            must.put("match", objectMapper.createObjectNode().put("value", documentId));
            filter.putArray("must").add(must);
            body.set("filter", filter);

            HttpRequest request = requestBuilder("/collections/" + collectionName + "/points/delete")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("deleteByDocument failed", e);
        }
    }

    @Override
    public long count() {
        try {
            HttpRequest request = requestBuilder("/collections/" + collectionName)
                .GET()
                .build();

            HttpResponse<String> response = send(request);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode result = root.get("result");
            if (result != null && result.has("points_count")) {
                return result.get("points_count").asLong();
            }
            return 0L;
        } catch (IOException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "qdrant:" + collectionName;
    }

    // ---- Collection management ----

    /**
     * Creates the Qdrant collection with the given vector configuration.
     *
     * @param dimensions vector dimensionality
     * @param distance   one of "Cosine", "Euclid", "Dot"
     */
    public void createCollection(int dimensions, @NonNull String distance) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode vectors = body.putObject("vectors");
            vectors.put("size", dimensions);
            vectors.put("distance", distance);

            ObjectNode hnsw = body.putObject("hnsw_config");
            hnsw.put("m", 16);
            hnsw.put("ef_construct", 100);

            HttpRequest request = requestBuilder("/collections/" + collectionName)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = send(request);
            if (response.statusCode() != 200) {
                throw new VectorStoreException("createCollection failed: HTTP " + response.statusCode() + " " + response.body(), null);
            }
        } catch (IOException e) {
            throw new VectorStoreException("createCollection failed", e);
        }
    }

    // ---- Internal helpers ----

    private void upsertBatch(@NonNull List<Chunk> chunks) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode points = body.putArray("points");

            for (Chunk chunk : chunks) {
                float[] emb = chunk.embedding();
                if (emb == null) {
                    throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");
                }

                ObjectNode point = objectMapper.createObjectNode();
                point.put("id", chunk.id());

                ArrayNode vector = point.putArray("vector");
                for (float v : emb) vector.add(v);

                ObjectNode payload = point.putObject("payload");
                payload.put("document_id", chunk.documentId());
                payload.put("text", chunk.text());
                payload.put("index", chunk.index());
                payload.put("token_count", chunk.tokenCount());
                if (chunk.parentChunkId() != null) {
                    payload.put("parent_chunk_id", chunk.parentChunkId());
                }
                payload.set("metadata", objectMapper.valueToTree(chunk.metadata()));

                points.add(point);
            }

            HttpRequest request = requestBuilder("/collections/" + collectionName + "/points?wait=true")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    private @NonNull List<RetrievalResult> parseSearchResults(@NonNull String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.get("result");
            if (result == null || !result.isArray()) return List.of();

            List<RetrievalResult> results = new ArrayList<>();
            for (JsonNode hit : result) {
                String id = hit.get("id").asText();
                double score = hit.get("score").asDouble();
                JsonNode payload = hit.get("payload");

                String documentId = payload.has("document_id") ? payload.get("document_id").asText() : "";
                String text = payload.has("text") ? payload.get("text").asText() : "";
                int index = payload.has("index") ? payload.get("index").asInt() : 0;
                int tokenCount = payload.has("token_count") ? payload.get("token_count").asInt() : 0;
                String parentChunkId = payload.has("parent_chunk_id") && !payload.get("parent_chunk_id").isNull()
                    ? payload.get("parent_chunk_id").asText() : null;

                Map<String, Object> metadata = parseMetadata(payload.get("metadata"));

                Chunk chunk = new Chunk(id, documentId, text, index, tokenCount, parentChunkId, metadata);
                results.add(new RetrievalResult(chunk, score));
            }
            return results;
        } catch (IOException e) {
            throw new VectorStoreException("parse search results failed", e);
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

    private @NonNull JsonNode buildQdrantFilter(@NonNull Map<String, Object> filters) {
        ObjectNode filterNode = objectMapper.createObjectNode();
        ArrayNode must = filterNode.putArray("must");

        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            ObjectNode condition = objectMapper.createObjectNode();
            condition.put("key", entry.getKey());
            ObjectNode match = objectMapper.createObjectNode();
            Object value = entry.getValue();
            if (value instanceof String s) {
                match.put("value", s);
            } else if (value instanceof Number n) {
                match.put("value", n.doubleValue());
            } else if (value instanceof Boolean b) {
                match.put("value", b);
            } else {
                match.put("value", value.toString());
            }
            condition.set("match", match);
            must.add(condition);
        }

        return filterNode;
    }

    private HttpRequest.Builder requestBuilder(@NonNull String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("api-key", apiKey);
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
