package com.chorus.engine.rag.store;

import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Factory for creating {@link VectorStore} instances from configuration.
 *
 * <p>Supported types:
 * <ul>
 *   <li>{@code memory} — {@link InMemoryVectorStore}</li>
 *   <li>{@code pgvector} — {@link PgVectorStore} (requires {@code dataSource} in config)</li>
 *   <li>{@code qdrant} — {@link QdrantVectorStore}</li>
 *   <li>{@code pinecone} — {@link PineconeVectorStore}</li>
 *   <li>{@code milvus} — {@link MilvusVectorStore}</li>
 * </ul>
 *
 * <p>Configuration keys per type:
 * <table>
 *   <tr><th>Type</th><th>Required keys</th><th>Optional keys</th></tr>
 *   <tr><td>memory</td><td>—</td><td>—</td></tr>
 *   <tr><td>pgvector</td><td>dataSource</td><td>tableName (default: chunks), dimensions (default: 768), distanceMetric (default: cosine)</td></tr>
 *   <tr><td>qdrant</td><td>baseUrl, collectionName</td><td>apiKey</td></tr>
 *   <tr><td>pinecone</td><td>apiKey, indexHost</td><td>namespace</td></tr>
 *   <tr><td>milvus</td><td>baseUrl, collectionName</td><td>token</td></tr>
 * </table>
 */
public final class VectorStoreFactory {

    private VectorStoreFactory() {}

    /**
     * Creates a {@link VectorStore} from the given type and configuration map.
     *
     * @param type   one of: memory, pgvector, qdrant, pinecone, milvus
     * @param config configuration map; type-specific keys documented above
     * @return a configured VectorStore instance
     * @throws IllegalArgumentException if the type is unknown or required config is missing
     */
    public static @NonNull VectorStore create(@NonNull String type, @NonNull Map<String, Object> config) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(config);

        return switch (type.toLowerCase(Locale.ROOT)) {
            case "memory" -> new InMemoryVectorStore();
            case "pgvector" -> createPgVector(config);
            case "qdrant" -> createQdrant(config);
            case "pinecone" -> createPinecone(config);
            case "milvus" -> createMilvus(config);
            default -> throw new IllegalArgumentException("Unknown vector store type: " + type);
        };
    }

    private static @NonNull VectorStore createPgVector(@NonNull Map<String, Object> config) {
        Object ds = config.get("dataSource");
        if (!(ds instanceof DataSource dataSource)) {
            throw new IllegalArgumentException("pgvector requires a 'dataSource' of type javax.sql.DataSource");
        }
        String tableName = getString(config, "tableName", "chunks");
        int dimensions = getInt(config, "dimensions", 768);
        String distanceMetric = getString(config, "distanceMetric", "cosine");
        return new PgVectorStore(dataSource, tableName, dimensions, distanceMetric);
    }

    private static @NonNull VectorStore createQdrant(@NonNull Map<String, Object> config) {
        String baseUrl = requireString(config, "baseUrl", "qdrant");
        String collectionName = requireString(config, "collectionName", "qdrant");
        String apiKey = getString(config, "apiKey", null);
        return new QdrantVectorStore(baseUrl, collectionName, apiKey);
    }

    private static @NonNull VectorStore createPinecone(@NonNull Map<String, Object> config) {
        String apiKey = requireString(config, "apiKey", "pinecone");
        String indexHost = requireString(config, "indexHost", "pinecone");
        String namespace = getString(config, "namespace", null);
        return new PineconeVectorStore(apiKey, indexHost, namespace);
    }

    private static @NonNull VectorStore createMilvus(@NonNull Map<String, Object> config) {
        String baseUrl = requireString(config, "baseUrl", "milvus");
        String collectionName = requireString(config, "collectionName", "milvus");
        String token = getString(config, "token", null);
        return new MilvusVectorStore(baseUrl, collectionName, token);
    }

    private static @NonNull String requireString(@NonNull Map<String, Object> config, @NonNull String key, @NonNull String type) {
        Object value = config.get(key);
        if (value == null) {
            throw new IllegalArgumentException(type + " requires config key: " + key);
        }
        return value.toString();
    }

    private static @NonNull String getString(@NonNull Map<String, Object> config, @NonNull String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static int getInt(@NonNull Map<String, Object> config, @NonNull String key, int defaultValue) {
        Object value = config.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
