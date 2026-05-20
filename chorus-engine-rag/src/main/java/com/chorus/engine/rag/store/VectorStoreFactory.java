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
 *   <li>{@code weaviate} — {@link WeaviateVectorStore}</li>
 *   <li>{@code elasticsearch} — {@link ElasticsearchVectorStore}</li>
 *   <li>{@code opensearch} — {@link OpenSearchVectorStore}</li>
 *   <li>{@code chroma} — {@link ChromaVectorStore}</li>
 *   <li>{@code mongoatlas} — {@link MongoAtlasVectorStore}</li>
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
 *   <tr><td>weaviate</td><td>baseUrl, className</td><td>apiKey</td></tr>
 *   <tr><td>elasticsearch</td><td>baseUrl, indexName</td><td>apiKey, username, password, dimensions (default: 1536)</td></tr>
 *   <tr><td>opensearch</td><td>baseUrl, indexName</td><td>username, password, dimensions (default: 1536)</td></tr>
 *   <tr><td>chroma</td><td>baseUrl, collectionName</td><td>tenant, database, token</td></tr>
 *   <tr><td>mongoatlas</td><td>dataApiUrl, apiKey, database, collection</td><td>indexName (default: vector_index)</td></tr>
 * </table>
 */
public final class VectorStoreFactory {

    private VectorStoreFactory() {}

    /**
     * Creates a {@link VectorStore} from the given type and configuration map.
     *
     * @param type   one of: memory, pgvector, qdrant, pinecone, milvus, weaviate,
     *               elasticsearch, opensearch, chroma, mongoatlas
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
            case "weaviate" -> createWeaviate(config);
            case "elasticsearch", "elastic" -> createElasticsearch(config);
            case "opensearch" -> createOpenSearch(config);
            case "chroma", "chromadb" -> createChroma(config);
            case "mongoatlas", "mongodb-atlas" -> createMongoAtlas(config);
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

    private static @NonNull VectorStore createWeaviate(@NonNull Map<String, Object> config) {
        String baseUrl = requireString(config, "baseUrl", "weaviate");
        String className = requireString(config, "className", "weaviate");
        String apiKey = getString(config, "apiKey", null);
        return new WeaviateVectorStore(baseUrl, className, apiKey);
    }

    private static @NonNull VectorStore createElasticsearch(@NonNull Map<String, Object> config) {
        String baseUrl = requireString(config, "baseUrl", "elasticsearch");
        String indexName = requireString(config, "indexName", "elasticsearch");
        String apiKey = getString(config, "apiKey", null);
        String username = getString(config, "username", null);
        String password = getString(config, "password", null);
        int dimensions = getInt(config, "dimensions", 1536);
        return new ElasticsearchVectorStore(baseUrl, indexName, apiKey, username, password, dimensions,
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build(),
            new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static @NonNull VectorStore createOpenSearch(@NonNull Map<String, Object> config) {
        String baseUrl = requireString(config, "baseUrl", "opensearch");
        String indexName = requireString(config, "indexName", "opensearch");
        String username = getString(config, "username", null);
        String password = getString(config, "password", null);
        int dimensions = getInt(config, "dimensions", 1536);
        return new OpenSearchVectorStore(baseUrl, indexName, username, password, dimensions);
    }

    private static @NonNull VectorStore createChroma(@NonNull Map<String, Object> config) {
        String baseUrl = requireString(config, "baseUrl", "chroma");
        String collectionName = requireString(config, "collectionName", "chroma");
        String tenant = getString(config, "tenant", "default_tenant");
        String database = getString(config, "database", "default_database");
        String token = getString(config, "token", null);
        return new ChromaVectorStore(baseUrl, collectionName, tenant, database, token,
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build(),
            new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static @NonNull VectorStore createMongoAtlas(@NonNull Map<String, Object> config) {
        String dataApiUrl = requireString(config, "dataApiUrl", "mongoatlas");
        String apiKey = requireString(config, "apiKey", "mongoatlas");
        String database = requireString(config, "database", "mongoatlas");
        String collection = requireString(config, "collection", "mongoatlas");
        String indexName = getString(config, "indexName", "vector_index");
        return new MongoAtlasVectorStore(dataApiUrl, apiKey, database, collection, indexName);
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
