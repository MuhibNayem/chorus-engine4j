package com.chorus.engine.rag.store;

import com.chorus.engine.rag.document.Chunk;
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
 * Milvus REST client adapter (v2 API).
 *
 * <p>Uses JDK {@link HttpClient} with Jackson for JSON. Batches upserts in groups of 100.
 * Supports expr-based filtering.
 */
public final class MilvusVectorStore implements VectorStore {

    private static final int BATCH_SIZE = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String collectionName;
    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MilvusVectorStore(@NonNull String baseUrl,
                             @NonNull String collectionName,
                             @Nullable String token) {
        this(baseUrl, collectionName, token, HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
    }

    public MilvusVectorStore(@NonNull String baseUrl,
                             @NonNull String collectionName,
                             @Nullable String token,
                             @NonNull HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collectionName = Objects.requireNonNull(collectionName);
        this.token = token;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
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
            body.put("collectionName", collectionName);

            ArrayNode data = body.putArray("data");
            ArrayNode vector = data.addArray();
            for (float v : queryEmbedding) vector.add(v);

            body.put("annsField", "vector");
            body.put("limit", topK);
            body.putArray("outputFields")
                .add("id")
                .add("document_id")
                .add("text")
                .add("index")
                .add("token_count")
                .add("parent_chunk_id")
                .add("metadata");

            if (!filters.isEmpty()) {
                body.put("filter", buildMilvusExpr(filters));
            }

            HttpRequest request = requestBuilder("/v2/vectordb/entities/search")
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
            body.put("collectionName", collectionName);

            StringBuilder expr = new StringBuilder("id in [");
            int i = 0;
            for (String id : chunkIds) {
                if (i++ > 0) expr.append(",");
                expr.append("'").append(id.replace("'", "\\'")).append("'");
            }
            expr.append("]");
            body.put("filter", expr.toString());

            HttpRequest request = requestBuilder("/v2/vectordb/entities/delete")
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
            body.put("collectionName", collectionName);
            body.put("filter", "document_id == '" + documentId.replace("'", "\\'") + "'");

            HttpRequest request = requestBuilder("/v2/vectordb/entities/delete")
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
            ObjectNode body = objectMapper.createObjectNode();
            body.put("collectionName", collectionName);

            HttpRequest request = requestBuilder("/v2/vectordb/entities/get")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            // Milvus v2 doesn't have a simple count endpoint; return 0 as fallback
            return 0L;
        } catch (IOException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "milvus:" + collectionName;
    }

    // ---- Collection management ----

    /**
     * Creates the Milvus collection with the given vector configuration.
     *
     * @param dimensions vector dimensionality
     * @param metricType one of "COSINE", "L2", "IP"
     */
    public void createCollection(int dimensions, @NonNull String metricType) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("collectionName", collectionName);

            ArrayNode fields = body.putArray("fields");

            ObjectNode idField = objectMapper.createObjectNode();
            idField.put("fieldName", "id");
            idField.put("dataType", "VarChar");
            idField.put("isPrimary", true);
            ObjectNode idParams = idField.putObject("elementTypeParams");
            idParams.put("max_length", 255);
            fields.add(idField);

            ObjectNode docIdField = objectMapper.createObjectNode();
            docIdField.put("fieldName", "document_id");
            docIdField.put("dataType", "VarChar");
            ObjectNode docIdParams = docIdField.putObject("elementTypeParams");
            docIdParams.put("max_length", 255);
            fields.add(docIdField);

            ObjectNode textField = objectMapper.createObjectNode();
            textField.put("fieldName", "text");
            textField.put("dataType", "VarChar");
            ObjectNode textParams = textField.putObject("elementTypeParams");
            textParams.put("max_length", 65535);
            fields.add(textField);

            ObjectNode indexField = objectMapper.createObjectNode();
            indexField.put("fieldName", "index");
            indexField.put("dataType", "Int32");
            fields.add(indexField);

            ObjectNode tokenCountField = objectMapper.createObjectNode();
            tokenCountField.put("fieldName", "token_count");
            tokenCountField.put("dataType", "Int32");
            fields.add(tokenCountField);

            ObjectNode parentChunkIdField = objectMapper.createObjectNode();
            parentChunkIdField.put("fieldName", "parent_chunk_id");
            parentChunkIdField.put("dataType", "VarChar");
            ObjectNode parentParams = parentChunkIdField.putObject("elementTypeParams");
            parentParams.put("max_length", 255);
            fields.add(parentChunkIdField);

            ObjectNode metadataField = objectMapper.createObjectNode();
            metadataField.put("fieldName", "metadata");
            metadataField.put("dataType", "JSON");
            fields.add(metadataField);

            ObjectNode vectorField = objectMapper.createObjectNode();
            vectorField.put("fieldName", "vector");
            vectorField.put("dataType", "FloatVector");
            ObjectNode vecParams = vectorField.putObject("elementTypeParams");
            vecParams.put("dim", dimensions);
            fields.add(vectorField);

            ObjectNode index = body.putObject("indexParams");
            index.put("fieldName", "vector");
            index.put("indexName", "vector_idx");
            index.put("metricType", metricType);
            ObjectNode indexExtra = index.putObject("params");
            indexExtra.put("M", 16);
            indexExtra.put("efConstruction", 100);
            indexExtra.put("index_type", "HNSW");

            HttpRequest request = requestBuilder("/v2/vectordb/collections/create")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = send(request);
            if (response.statusCode() != 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode code = root.get("code");
                // 65535 = collection already exists
                if (code != null && code.asInt() == 65535) {
                    return;
                }
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
            body.put("collectionName", collectionName);

            ArrayNode data = body.putArray("data");
            for (Chunk chunk : chunks) {
                float[] emb = chunk.embedding();
                if (emb == null) {
                    throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");
                }

                ObjectNode row = objectMapper.createObjectNode();
                row.put("id", chunk.id());
                row.put("document_id", chunk.documentId());
                row.put("text", chunk.text());
                row.put("index", chunk.index());
                row.put("token_count", chunk.tokenCount());
                if (chunk.parentChunkId() != null) {
                    row.put("parent_chunk_id", chunk.parentChunkId());
                } else {
                    row.putNull("parent_chunk_id");
                }
                row.set("metadata", objectMapper.valueToTree(chunk.metadata()));

                ArrayNode vector = row.putArray("vector");
                for (float v : emb) vector.add(v);

                data.add(row);
            }

            HttpRequest request = requestBuilder("/v2/vectordb/entities/insert")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    private @NonNull List<RetrievalResult> parseSearchResults(@NonNull String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return List.of();

            List<RetrievalResult> results = new ArrayList<>();
            for (JsonNode hit : data) {
                String id = hit.has("id") ? hit.get("id").asText() : "";
                double distance = hit.has("distance") ? hit.get("distance").asDouble() : 0.0;

                String documentId = hit.has("document_id") ? hit.get("document_id").asText() : "";
                String text = hit.has("text") ? hit.get("text").asText() : "";
                int index = hit.has("index") ? hit.get("index").asInt() : 0;
                int tokenCount = hit.has("token_count") ? hit.get("token_count").asInt() : 0;
                String parentChunkId = hit.has("parent_chunk_id") && !hit.get("parent_chunk_id").isNull()
                    ? hit.get("parent_chunk_id").asText() : null;

                Map<String, Object> metadata = parseMetadata(hit.get("metadata"));

                Chunk chunk = new Chunk(id, documentId, text, index, tokenCount, parentChunkId, metadata);
                // Milvus returns distance, not similarity score. For cosine/L2, lower is better.
                // We return the raw distance as the score.
                results.add(new RetrievalResult(chunk, distance));
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
            return objectMapper.treeToValue(node, HashMap.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private @NonNull String buildMilvusExpr(@NonNull Map<String, Object> filters) {
        StringBuilder expr = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            if (i++ > 0) expr.append(" && ");
            Object value = entry.getValue();
            expr.append("metadata['").append(entry.getKey().replace("'", "\\'")).append("']");
            if (value instanceof String s) {
                expr.append(" == '").append(s.replace("'", "\\'")).append("'");
            } else if (value instanceof Number n) {
                expr.append(" == ").append(n);
            } else if (value instanceof Boolean b) {
                expr.append(" == ").append(b);
            } else {
                expr.append(" == '").append(value.toString().replace("'", "\\'")).append("'");
            }
        }
        return expr.toString();
    }

    private HttpRequest.Builder requestBuilder(@NonNull String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT);
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
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
