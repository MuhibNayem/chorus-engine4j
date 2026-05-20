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
 * ChromaDB HTTP API client adapter (Chroma v0.6+).
 *
 * <p>Uses the Chroma REST API at {@code /api/v2} (database-tenant model).
 * Batches upserts in groups of 100. Supports metadata filtering via Chroma's
 * {@code where} clause (equality and range comparisons).
 *
 * <p>Authentication: Bearer token via {@code X-Chroma-Token} header (optional for local deployments).
 *
 * <p>Configuration example:
 * <pre>{@code
 * VectorStore store = VectorStoreFactory.create("chroma", Map.of(
 *     "baseUrl",        "http://localhost:8000",
 *     "collectionName", "chorus_chunks",
 *     "tenant",         "default_tenant",    // optional, defaults to "default_tenant"
 *     "database",       "default_database",  // optional, defaults to "default_database"
 *     "token",          "my-token"           // optional
 * ));
 * }</pre>
 */
public final class ChromaVectorStore implements VectorStore {

    private static final int BATCH_SIZE = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String collectionName;
    private final String tenant;
    private final String database;
    @Nullable private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Resolved collection UUID — fetched lazily on first operation. */
    @Nullable private volatile String collectionId;

    public ChromaVectorStore(@NonNull String baseUrl,
                             @NonNull String collectionName,
                             @Nullable String token) {
        this(baseUrl, collectionName, "default_tenant", "default_database", token,
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(CONNECT_TIMEOUT).build(),
            new ObjectMapper());
    }

    public ChromaVectorStore(@NonNull String baseUrl,
                             @NonNull String collectionName,
                             @NonNull String tenant,
                             @NonNull String database,
                             @Nullable String token,
                             @NonNull HttpClient httpClient,
                             @NonNull ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collectionName = Objects.requireNonNull(collectionName);
        this.tenant = tenant;
        this.database = database;
        this.token = token;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void upsert(@NonNull List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        String id = resolveCollectionId();
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            upsertBatch(id, chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size())));
        }
    }

    @Override
    public @NonNull List<RetrievalResult> search(@NonNull float[] queryEmbedding,
                                                  int topK,
                                                  @NonNull Map<String, Object> filters) {
        if (topK <= 0) return List.of();
        try {
            String id = resolveCollectionId();
            ObjectNode body = objectMapper.createObjectNode();

            ArrayNode queryEmbeddings = body.putArray("query_embeddings");
            ArrayNode vec = queryEmbeddings.addArray();
            for (float v : queryEmbedding) vec.add(v);

            body.put("n_results", topK);
            body.putArray("include").add("metadatas").add("documents").add("distances");

            if (!filters.isEmpty()) {
                body.set("where", buildWhereClause(filters));
            }

            HttpRequest request = requestBuilder(collectionPath(id) + "/query")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = send(request);
            return parseQueryResults(response.body());
        } catch (IOException e) {
            throw new VectorStoreException("search failed", e);
        }
    }

    @Override
    public void delete(@NonNull Set<String> chunkIds) {
        if (chunkIds.isEmpty()) return;
        try {
            String id = resolveCollectionId();
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode ids = body.putArray("ids");
            for (String chunkId : chunkIds) ids.add(chunkId);

            HttpRequest request = requestBuilder(collectionPath(id) + "/delete")
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
            String id = resolveCollectionId();
            ObjectNode body = objectMapper.createObjectNode();
            body.set("where", objectMapper.createObjectNode()
                .set("document_id", objectMapper.createObjectNode().put("$eq", documentId)));

            HttpRequest request = requestBuilder(collectionPath(id) + "/delete")
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
            String id = resolveCollectionId();
            HttpRequest request = requestBuilder(collectionPath(id) + "/count").GET().build();
            HttpResponse<String> response = send(request);
            JsonNode root = objectMapper.readTree(response.body());
            // Chroma returns the count directly as a number
            return root.isNumber() ? root.asLong() : 0L;
        } catch (IOException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "chroma:" + collectionName;
    }

    /**
     * Creates or retrieves the Chroma collection. Safe to call multiple times.
     *
     * @return the resolved collection UUID
     */
    public @NonNull String getOrCreateCollection() {
        return resolveCollectionId();
    }

    // ---- Internal helpers ----

    private @NonNull String resolveCollectionId() {
        if (collectionId != null) return collectionId;
        synchronized (this) {
            if (collectionId != null) return collectionId;
            collectionId = fetchOrCreateCollectionId();
        }
        return collectionId;
    }

    private @NonNull String fetchOrCreateCollectionId() {
        try {
            // Try to get existing collection
            HttpRequest getRequest = requestBuilder(
                "/api/v2/tenants/" + tenant + "/databases/" + database + "/collections/" + collectionName
            ).GET().build();

            HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (getResponse.statusCode() == 200) {
                return objectMapper.readTree(getResponse.body()).get("id").asText();
            }

            // Create collection
            ObjectNode body = objectMapper.createObjectNode();
            body.put("name", collectionName);
            ObjectNode meta = body.putObject("metadata");
            meta.put("hnsw:space", "cosine");

            HttpRequest createRequest = requestBuilder(
                "/api/v2/tenants/" + tenant + "/databases/" + database + "/collections"
            ).POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();

            HttpResponse<String> createResponse = httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (createResponse.statusCode() >= 400) {
                throw new VectorStoreException("Failed to create collection: HTTP " + createResponse.statusCode() + " " + createResponse.body(), null);
            }
            return objectMapper.readTree(createResponse.body()).get("id").asText();
        } catch (IOException e) {
            throw new VectorStoreException("resolveCollectionId failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorStoreException("resolveCollectionId interrupted", e);
        }
    }

    private @NonNull String collectionPath(@NonNull String id) {
        return "/api/v2/tenants/" + tenant + "/databases/" + database + "/collections/" + id;
    }

    private void upsertBatch(@NonNull String id, @NonNull List<Chunk> batch) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode ids = body.putArray("ids");
            ArrayNode embeddings = body.putArray("embeddings");
            ArrayNode documents = body.putArray("documents");
            ArrayNode metadatas = body.putArray("metadatas");

            for (Chunk chunk : batch) {
                float[] emb = chunk.embedding();
                if (emb == null) throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");

                ids.add(chunk.id());
                ArrayNode vec = embeddings.addArray();
                for (float v : emb) vec.add(v);
                documents.add(chunk.text());

                ObjectNode meta = objectMapper.createObjectNode();
                meta.put("document_id", chunk.documentId());
                meta.put("chunk_index", chunk.index());
                meta.put("token_count", chunk.tokenCount());
                if (chunk.parentChunkId() != null) meta.put("parent_chunk_id", chunk.parentChunkId());
                // Flatten chunk metadata into Chroma metadata (Chroma values must be scalar)
                for (Map.Entry<String, Object> e : chunk.metadata().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String s) meta.put(e.getKey(), s);
                    else if (v instanceof Number n) meta.put(e.getKey(), n.doubleValue());
                    else if (v instanceof Boolean b) meta.put(e.getKey(), b);
                    else meta.put(e.getKey(), v.toString());
                }
                metadatas.add(meta);
            }

            HttpRequest request = requestBuilder(collectionPath(id) + "/upsert")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    private @NonNull ObjectNode buildWhereClause(@NonNull Map<String, Object> filters) {
        if (filters.size() == 1) {
            Map.Entry<String, Object> e = filters.entrySet().iterator().next();
            return buildSingleCondition(e.getKey(), e.getValue());
        }
        ArrayNode operands = objectMapper.createArrayNode();
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            operands.add(buildSingleCondition(e.getKey(), e.getValue()));
        }
        return objectMapper.createObjectNode().set("$and", operands);
    }

    private @NonNull ObjectNode buildSingleCondition(@NonNull String key, @NonNull Object value) {
        ObjectNode condition = objectMapper.createObjectNode();
        ObjectNode eqClause = objectMapper.createObjectNode();
        if (value instanceof String s) eqClause.put("$eq", s);
        else if (value instanceof Number n) eqClause.put("$eq", n.doubleValue());
        else if (value instanceof Boolean b) eqClause.put("$eq", b);
        else eqClause.put("$eq", value.toString());
        condition.set(key, eqClause);
        return condition;
    }

    private @NonNull List<RetrievalResult> parseQueryResults(@NonNull String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode idsNode = root.path("ids").path(0);
            JsonNode distancesNode = root.path("distances").path(0);
            JsonNode metadatasNode = root.path("metadatas").path(0);
            JsonNode documentsNode = root.path("documents").path(0);

            if (!idsNode.isArray()) return List.of();

            List<RetrievalResult> results = new ArrayList<>();
            for (int i = 0; i < idsNode.size(); i++) {
                String id = idsNode.get(i).asText();
                double distance = distancesNode.isArray() ? distancesNode.get(i).asDouble(1.0) : 1.0;
                double score = 1.0 - distance;
                String text = documentsNode.isArray() ? documentsNode.get(i).asText("") : "";
                JsonNode meta = metadatasNode.isArray() ? metadatasNode.get(i) : objectMapper.createObjectNode();

                String documentId = meta.path("document_id").asText("");
                int index = meta.path("chunk_index").asInt(0);
                int tokenCount = meta.path("token_count").asInt(0);
                String parentChunkId = meta.has("parent_chunk_id") && !meta.get("parent_chunk_id").isNull()
                    ? meta.get("parent_chunk_id").asText() : null;

                Map<String, Object> metadata = new LinkedHashMap<>();
                meta.fields().forEachRemaining(field -> {
                    String k = field.getKey();
                    if (!Set.of("document_id", "chunk_index", "token_count", "parent_chunk_id").contains(k)) {
                        JsonNode v = field.getValue();
                        if (v.isTextual()) metadata.put(k, v.asText());
                        else if (v.isNumber()) metadata.put(k, v.asDouble());
                        else if (v.isBoolean()) metadata.put(k, v.asBoolean());
                    }
                });

                results.add(new RetrievalResult(new Chunk(id, documentId, text, index, tokenCount, parentChunkId, metadata), score));
            }
            return results;
        } catch (IOException e) {
            throw new VectorStoreException("parse results failed", e);
        }
    }

    private HttpRequest.Builder requestBuilder(@NonNull String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT);
        if (token != null && !token.isBlank()) {
            builder.header("X-Chroma-Token", token);
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
