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
 * Pinecone REST client adapter.
 *
 * <p>Uses JDK {@link HttpClient} with Jackson for JSON. Batches upserts in groups of 100.
 * Supports metadata filtering via Pinecone's JSON filter format.
 */
public final class PineconeVectorStore implements VectorStore {

    private static final int BATCH_SIZE = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final String indexHost;
    private final String namespace;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PineconeVectorStore(@NonNull String apiKey,
                               @NonNull String indexHost,
                               @Nullable String namespace) {
        this(apiKey, indexHost, namespace, HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
    }

    public PineconeVectorStore(@NonNull String apiKey,
                               @NonNull String indexHost,
                               @Nullable String namespace,
                               @NonNull HttpClient httpClient) {
        this.apiKey = Objects.requireNonNull(apiKey);
        String host = indexHost.endsWith("/") ? indexHost.substring(0, indexHost.length() - 1) : indexHost;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://" + host;
        }
        this.indexHost = host;
        this.namespace = namespace == null || namespace.isBlank() ? "" : namespace;
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
            body.put("topK", topK);
            body.put("includeMetadata", true);
            body.put("includeValues", false);

            if (!namespace.isBlank()) {
                body.put("namespace", namespace);
            }

            ArrayNode vector = body.putArray("vector");
            for (float v : queryEmbedding) vector.add(v);

            if (!filters.isEmpty()) {
                body.set("filter", buildPineconeFilter(filters));
            }

            HttpRequest request = requestBuilder("/query")
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
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode ids = body.putArray("ids");
            for (String id : chunkIds) ids.add(id);

            if (!namespace.isBlank()) {
                body.put("namespace", namespace);
            }

            HttpRequest request = requestBuilder("/vectors/delete")
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
            body.set("filter", objectMapper.createObjectNode()
                .put("document_id", documentId));

            if (!namespace.isBlank()) {
                body.put("namespace", namespace);
            }
            body.put("deleteAll", false);

            HttpRequest request = requestBuilder("/vectors/delete")
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
            if (!namespace.isBlank()) {
                body.put("namespace", namespace);
            }

            HttpRequest request = requestBuilder("/describe_index_stats")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = send(request);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode namespaces = root.get("namespaces");
            if (namespaces != null && namespaces.has(namespace.isBlank() ? "" : namespace)) {
                return namespaces.get(namespace.isBlank() ? "" : namespace).get("vectorCount").asLong();
            }
            return 0L;
        } catch (IOException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "pinecone:" + indexHost;
    }

    // ---- Internal helpers ----

    private void upsertBatch(@NonNull List<Chunk> chunks) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            if (!namespace.isBlank()) {
                body.put("namespace", namespace);
            }

            ArrayNode vectors = body.putArray("vectors");
            for (Chunk chunk : chunks) {
                float[] emb = chunk.embedding();
                if (emb == null) {
                    throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");
                }

                ObjectNode vec = objectMapper.createObjectNode();
                vec.put("id", chunk.id());

                ArrayNode values = vec.putArray("values");
                for (float v : emb) values.add(v);

                ObjectNode metadata = vec.putObject("metadata");
                metadata.put("document_id", chunk.documentId());
                metadata.put("text", chunk.text());
                metadata.put("index", chunk.index());
                metadata.put("token_count", chunk.tokenCount());
                if (chunk.parentChunkId() != null) {
                    metadata.put("parent_chunk_id", chunk.parentChunkId());
                }
                metadata.set("chunk_metadata", objectMapper.valueToTree(chunk.metadata()));

                vectors.add(vec);
            }

            HttpRequest request = requestBuilder("/vectors/upsert")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            send(request);
        } catch (IOException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    private @NonNull List<RetrievalResult> parseQueryResults(@NonNull String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode matches = root.get("matches");
            if (matches == null || !matches.isArray()) return List.of();

            List<RetrievalResult> results = new ArrayList<>();
            for (JsonNode match : matches) {
                String id = match.get("id").asText();
                double score = match.get("score").asDouble();
                JsonNode metadata = match.get("metadata");

                String documentId = metadata != null && metadata.has("document_id")
                    ? metadata.get("document_id").asText() : "";
                String text = metadata != null && metadata.has("text")
                    ? metadata.get("text").asText() : "";
                int index = metadata != null && metadata.has("index")
                    ? metadata.get("index").asInt() : 0;
                int tokenCount = metadata != null && metadata.has("token_count")
                    ? metadata.get("token_count").asInt() : 0;
                String parentChunkId = metadata != null && metadata.has("parent_chunk_id") && !metadata.get("parent_chunk_id").isNull()
                    ? metadata.get("parent_chunk_id").asText() : null;

                Map<String, Object> chunkMetadata = parseMetadata(metadata != null ? metadata.get("chunk_metadata") : null);

                Chunk chunk = new Chunk(id, documentId, text, index, tokenCount, parentChunkId, chunkMetadata);
                results.add(new RetrievalResult(chunk, score));
            }
            return results;
        } catch (IOException e) {
            throw new VectorStoreException("parse query results failed", e);
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

    private @NonNull JsonNode buildPineconeFilter(@NonNull Map<String, Object> filters) {
        ObjectNode filterNode = objectMapper.createObjectNode();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                filterNode.put(entry.getKey(), s);
            } else if (value instanceof Number n) {
                filterNode.put(entry.getKey(), n.doubleValue());
            } else if (value instanceof Boolean b) {
                filterNode.put(entry.getKey(), b);
            } else {
                filterNode.put(entry.getKey(), value.toString());
            }
        }
        return filterNode;
    }

    private HttpRequest.Builder requestBuilder(@NonNull String path) {
        String basePath = "/";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(indexHost + basePath + path))
            .header("Content-Type", "application/json")
            .header("Api-Key", apiKey)
            .timeout(REQUEST_TIMEOUT);
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
