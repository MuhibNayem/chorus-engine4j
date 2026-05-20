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
 * MongoDB Atlas Vector Search client adapter using the Atlas Data API.
 *
 * <p>Requires a MongoDB Atlas cluster with an Atlas Vector Search index on the
 * {@code embedding} field. Uses the Atlas Data API (HTTPS) — no MongoDB driver required.
 *
 * <p>Prerequisites:
 * <ol>
 *   <li>Enable Atlas Data API on your cluster</li>
 *   <li>Create a Vector Search index named {@code "vector_index"} on the collection with:
 *       {@code { "fields": [{ "type": "vector", "path": "embedding", "numDimensions": N, "similarity": "cosine" }] }}</li>
 * </ol>
 *
 * <p>Configuration example:
 * <pre>{@code
 * VectorStore store = VectorStoreFactory.create("mongoatlas", Map.of(
 *     "dataApiUrl",      "https://data.mongodb-api.com/app/data-abcde/endpoint/data/v1",
 *     "apiKey",          "my-data-api-key",
 *     "database",        "mydb",
 *     "collection",      "chorus_chunks",
 *     "indexName",       "vector_index"        // optional, default "vector_index"
 * ));
 * }</pre>
 */
public final class MongoAtlasVectorStore implements VectorStore {

    private static final int BATCH_SIZE = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String dataApiUrl;
    private final String apiKey;
    private final String database;
    private final String collection;
    private final String indexName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MongoAtlasVectorStore(@NonNull String dataApiUrl,
                                 @NonNull String apiKey,
                                 @NonNull String database,
                                 @NonNull String collection,
                                 @Nullable String indexName) {
        this(dataApiUrl, apiKey, database, collection, indexName,
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(CONNECT_TIMEOUT).build(),
            new ObjectMapper());
    }

    public MongoAtlasVectorStore(@NonNull String dataApiUrl,
                                 @NonNull String apiKey,
                                 @NonNull String database,
                                 @NonNull String collection,
                                 @Nullable String indexName,
                                 @NonNull HttpClient httpClient,
                                 @NonNull ObjectMapper objectMapper) {
        this.dataApiUrl = dataApiUrl.endsWith("/") ? dataApiUrl.substring(0, dataApiUrl.length() - 1) : dataApiUrl;
        this.apiKey = Objects.requireNonNull(apiKey);
        this.database = Objects.requireNonNull(database);
        this.collection = Objects.requireNonNull(collection);
        this.indexName = indexName != null && !indexName.isBlank() ? indexName : "vector_index";
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
            ObjectNode body = dataApiBody();

            // Atlas Vector Search uses an aggregation pipeline
            ArrayNode pipeline = body.putArray("pipeline");

            // $vectorSearch stage
            ObjectNode vectorSearch = pipeline.addObject().putObject("$vectorSearch");
            vectorSearch.put("index", indexName);
            vectorSearch.put("path", "embedding");
            ArrayNode qvec = vectorSearch.putArray("queryVector");
            for (float v : queryEmbedding) qvec.add(v);
            vectorSearch.put("numCandidates", Math.max(topK * 10, 100));
            vectorSearch.put("limit", topK);

            if (!filters.isEmpty()) {
                vectorSearch.set("filter", buildMatchFilter(filters));
            }

            // $project stage — add score field
            ObjectNode project = pipeline.addObject().putObject("$project");
            project.put("embedding", 0);
            project.put("score", objectMapper.createObjectNode()
                .put("$meta", "vectorSearchScore"));

            HttpRequest request = requestBuilder("/action/aggregate")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = send(request);
            return parseAggregateResults(response.body());
        } catch (IOException e) {
            throw new VectorStoreException("search failed", e);
        }
    }

    @Override
    public void delete(@NonNull Set<String> chunkIds) {
        if (chunkIds.isEmpty()) return;
        try {
            ObjectNode body = dataApiBody();
            ArrayNode inList = objectMapper.createArrayNode();
            for (String id : chunkIds) inList.add(id);
            body.putObject("filter")
                .putObject("chunk_id")
                .set("$in", inList);

            HttpRequest request = requestBuilder("/action/deleteMany")
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
            ObjectNode body = dataApiBody();
            body.putObject("filter").put("document_id", documentId);

            HttpRequest request = requestBuilder("/action/deleteMany")
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
            ObjectNode body = dataApiBody();
            body.putObject("filter");

            HttpRequest request = requestBuilder("/action/aggregate")
                .POST(HttpRequest.BodyPublishers.ofString(
                    buildCountAggregation()))
                .build();
            HttpResponse<String> response = send(request);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode docs = root.path("documents");
            if (docs.isArray() && docs.size() > 0) {
                return docs.get(0).path("count").asLong(0L);
            }
            return 0L;
        } catch (IOException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "mongoatlas:" + database + "." + collection;
    }

    // ---- Internal helpers ----

    private void upsertBatch(@NonNull List<Chunk> batch) {
        try {
            // Atlas Data API supports updateOne with upsert for idempotency
            for (Chunk chunk : batch) {
                float[] emb = chunk.embedding();
                if (emb == null) throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");

                ObjectNode body = dataApiBody();
                body.putObject("filter").put("chunk_id", chunk.id());

                ObjectNode update = body.putObject("update");
                ObjectNode set = update.putObject("$set");
                set.put("chunk_id", chunk.id());
                set.put("document_id", chunk.documentId());
                set.put("text", chunk.text());
                set.put("chunk_index", chunk.index());
                set.put("token_count", chunk.tokenCount());
                if (chunk.parentChunkId() != null) set.put("parent_chunk_id", chunk.parentChunkId());
                set.set("metadata", objectMapper.valueToTree(chunk.metadata()));
                ArrayNode embArr = set.putArray("embedding");
                for (float v : emb) embArr.add(v);

                body.put("upsert", true);

                HttpRequest request = requestBuilder("/action/updateOne")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
                send(request);
            }
        } catch (IOException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    private @NonNull String buildCountAggregation() throws IOException {
        ObjectNode body = dataApiBody();
        ArrayNode pipeline = body.putArray("pipeline");
        pipeline.addObject().putObject("$count").put("", "count");
        // Cheat: build inline
        ObjectNode countBody = dataApiBody();
        ArrayNode p = countBody.putArray("pipeline");
        p.addObject().put("$count", "count");
        return objectMapper.writeValueAsString(countBody);
    }

    private @NonNull ObjectNode dataApiBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collection", collection);
        body.put("database", database);
        body.put("dataSource", "Cluster0");
        return body;
    }

    private @NonNull ObjectNode buildMatchFilter(@NonNull Map<String, Object> filters) {
        ObjectNode filter = objectMapper.createObjectNode();
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            Object value = e.getValue();
            if (value instanceof String s) filter.put(e.getKey(), s);
            else if (value instanceof Number n) filter.put(e.getKey(), n.doubleValue());
            else if (value instanceof Boolean b) filter.put(e.getKey(), b);
            else filter.put(e.getKey(), value.toString());
        }
        return filter;
    }

    private @NonNull List<RetrievalResult> parseAggregateResults(@NonNull String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode docs = root.path("documents");
            if (!docs.isArray()) return List.of();

            List<RetrievalResult> results = new ArrayList<>();
            for (JsonNode doc : docs) {
                String id = doc.path("chunk_id").asText("");
                double score = doc.path("score").asDouble(0.0);
                String documentId = doc.path("document_id").asText("");
                String text = doc.path("text").asText("");
                int index = doc.path("chunk_index").asInt(0);
                int tokenCount = doc.path("token_count").asInt(0);
                String parentChunkId = doc.has("parent_chunk_id") && !doc.get("parent_chunk_id").isNull()
                    ? doc.get("parent_chunk_id").asText() : null;
                Map<String, Object> metadata = parseMetadata(doc.get("metadata"));
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

    private HttpRequest.Builder requestBuilder(@NonNull String action) {
        return HttpRequest.newBuilder()
            .uri(URI.create(dataApiUrl + action))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("api-key", apiKey)
            .timeout(REQUEST_TIMEOUT);
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
