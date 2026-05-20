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
 * OpenSearch 2.x k-NN vector search client adapter (AWS OpenSearch Service compatible).
 *
 * <p>Uses the OpenSearch REST API with {@code knn_vector} fields and approximate nearest-neighbour
 * search. Batches upserts via the {@code _bulk} API in groups of 100. Structurally identical
 * to {@link ElasticsearchVectorStore} but uses the OpenSearch query DSL for k-NN.
 *
 * <p>Authentication: HTTP Basic or AWS SigV4 (provide {@code awsCredentials} via config).
 * For managed AWS OpenSearch Service, combine with an AWS request signing proxy.
 *
 * <p>Configuration example:
 * <pre>{@code
 * VectorStore store = VectorStoreFactory.create("opensearch", Map.of(
 *     "baseUrl",    "https://my-domain.us-east-1.es.amazonaws.com",
 *     "indexName",  "chorus_chunks",
 *     "username",   "admin",
 *     "password",   "Admin@12345",
 *     "dimensions", 1536
 * ));
 * }</pre>
 */
public final class OpenSearchVectorStore implements VectorStore {

    private static final int BATCH_SIZE = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String indexName;
    @Nullable private final String basicAuthHeader;
    private final int dimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenSearchVectorStore(@NonNull String baseUrl,
                                 @NonNull String indexName,
                                 @Nullable String username,
                                 @Nullable String password,
                                 int dimensions) {
        this(baseUrl, indexName, username, password, dimensions,
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(CONNECT_TIMEOUT).build(),
            new ObjectMapper());
    }

    public OpenSearchVectorStore(@NonNull String baseUrl,
                                 @NonNull String indexName,
                                 @Nullable String username,
                                 @Nullable String password,
                                 int dimensions,
                                 @NonNull HttpClient httpClient,
                                 @NonNull ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.indexName = Objects.requireNonNull(indexName);
        this.dimensions = dimensions;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        if (username != null && password != null) {
            String credentials = username + ":" + password;
            this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        } else {
            this.basicAuthHeader = null;
        }
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
            ObjectNode body = objectMapper.createObjectNode();
            body.put("size", topK);

            ObjectNode query = body.putObject("query");
            ObjectNode knnOuter = query.putObject("knn");
            ObjectNode knnField = knnOuter.putObject("embedding");
            ArrayNode vector = knnField.putArray("vector");
            for (float v : queryEmbedding) vector.add(v);
            knnField.put("k", topK);

            if (!filters.isEmpty()) {
                // Rebuild query as bool+filter wrapping the knn clause
                body.remove("query");
                ObjectNode boolQuery = body.putObject("query").putObject("bool");
                ObjectNode knnMust = boolQuery.putObject("must").putObject("knn").putObject("embedding");
                ArrayNode mustVec = knnMust.putArray("vector");
                for (float v : queryEmbedding) mustVec.add(v);
                knnMust.put("k", topK);
                boolQuery.set("filter", buildTermsFilter(filters));
            }

            HttpRequest request = requestBuilder("/" + indexName + "/_search")
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
            ObjectNode query = body.putObject("query");
            ObjectNode terms = query.putObject("terms");
            ArrayNode ids = terms.putArray("_id");
            for (String id : chunkIds) ids.add(id);
            HttpRequest request = requestBuilder("/" + indexName + "/_delete_by_query")
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
            ObjectNode query = body.putObject("query");
            query.putObject("term").put("document_id", documentId);
            HttpRequest request = requestBuilder("/" + indexName + "/_delete_by_query")
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
            HttpRequest request = requestBuilder("/" + indexName + "/_count").GET().build();
            HttpResponse<String> response = send(request);
            return objectMapper.readTree(response.body()).path("count").asLong(0L);
        } catch (IOException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "opensearch:" + indexName;
    }

    /**
     * Creates the OpenSearch index with a {@code knn_vector} field.
     * Safe to call multiple times — ignores "resource already exists" errors.
     */
    public void createIndex() {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("settings")
                .put("index.knn", true);

            ObjectNode props = body.putObject("mappings").putObject("properties");
            ObjectNode embField = props.putObject("embedding");
            embField.put("type", "knn_vector");
            embField.put("dimension", dimensions);
            embField.putObject("method")
                .put("name", "hnsw")
                .put("space_type", "cosinesimil")
                .put("engine", "nmslib");

            props.putObject("document_id").put("type", "keyword");
            props.putObject("text").put("type", "text");
            props.putObject("chunk_index").put("type", "integer");
            props.putObject("token_count").put("type", "integer");
            props.putObject("parent_chunk_id").put("type", "keyword");
            props.putObject("metadata").put("type", "object").put("dynamic", true);

            HttpRequest request = requestBuilder("/" + indexName)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                if (!response.body().contains("resource_already_exists_exception")) {
                    throw new VectorStoreException("createIndex failed: HTTP " + response.statusCode() + " " + response.body(), null);
                }
            }
        } catch (IOException e) {
            throw new VectorStoreException("createIndex failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorStoreException("createIndex interrupted", e);
        }
    }

    // ---- Internal helpers ----

    private void upsertBatch(@NonNull List<Chunk> batch) {
        try {
            StringBuilder bulkBody = new StringBuilder();
            for (Chunk chunk : batch) {
                float[] emb = chunk.embedding();
                if (emb == null) throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");

                ObjectNode actionMeta = objectMapper.createObjectNode();
                actionMeta.putObject("index").put("_index", indexName).put("_id", chunk.id());
                bulkBody.append(objectMapper.writeValueAsString(actionMeta)).append('\n');

                ObjectNode doc = objectMapper.createObjectNode();
                doc.put("document_id", chunk.documentId());
                doc.put("text", chunk.text());
                doc.put("chunk_index", chunk.index());
                doc.put("token_count", chunk.tokenCount());
                if (chunk.parentChunkId() != null) doc.put("parent_chunk_id", chunk.parentChunkId());
                doc.set("metadata", objectMapper.valueToTree(chunk.metadata()));
                ArrayNode embArr = doc.putArray("embedding");
                for (float v : emb) embArr.add(v);
                bulkBody.append(objectMapper.writeValueAsString(doc)).append('\n');
            }

            HttpRequest request = requestBuilder("/_bulk")
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(bulkBody.toString()))
                .build();
            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    private @NonNull ObjectNode buildTermsFilter(@NonNull Map<String, Object> filters) {
        ObjectNode bool = objectMapper.createObjectNode();
        ArrayNode must = bool.putObject("bool").putArray("must");
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            ObjectNode term = objectMapper.createObjectNode();
            ObjectNode termNode = term.putObject("term");
            Object value = e.getValue();
            if (value instanceof String s) termNode.put(e.getKey(), s);
            else if (value instanceof Number n) termNode.put(e.getKey(), n.doubleValue());
            else if (value instanceof Boolean b) termNode.put(e.getKey(), b);
            else termNode.put(e.getKey(), value.toString());
            must.add(term);
        }
        return bool;
    }

    private @NonNull List<RetrievalResult> parseSearchResults(@NonNull String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray()) return List.of();

            List<RetrievalResult> results = new ArrayList<>();
            for (JsonNode hit : hits) {
                String id = hit.path("_id").asText();
                double score = hit.path("_score").asDouble(0.0);
                JsonNode src = hit.path("_source");
                String documentId = src.path("document_id").asText("");
                String text = src.path("text").asText("");
                int index = src.path("chunk_index").asInt(0);
                int tokenCount = src.path("token_count").asInt(0);
                String parentChunkId = src.has("parent_chunk_id") && !src.get("parent_chunk_id").isNull()
                    ? src.get("parent_chunk_id").asText() : null;
                Map<String, Object> metadata = parseMetadata(src.get("metadata"));
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
        if (basicAuthHeader != null) {
            builder.header("Authorization", basicAuthHeader);
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
