package com.chorus.engine.rag.store;

import com.chorus.engine.rag.document.Chunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Production-grade PostgreSQL + pgvector adapter.
 *
 * <p>Requires:
 * <ul>
 *   <li>PostgreSQL 14+</li>
 *   <li>pgvector extension installed</li>
 *   <li>A {@link DataSource} with connection pooling (e.g. HikariCP)</li>
 * </ul>
 *
 * <p>Thread-safe — all operations use connections from the pool.
 */
public final class PgVectorStore implements VectorStore {

    private final DataSource dataSource;
    private final String tableName;
    private final int dimensions;
    private final String distanceMetric;
    private final String vectorOps;
    private final ObjectMapper objectMapper;

    public PgVectorStore(@NonNull DataSource dataSource,
                         @NonNull String tableName,
                         int dimensions,
                         @Nullable String distanceMetric,
                         @NonNull ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.tableName = sanitizeIdentifier(Objects.requireNonNull(tableName));
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be > 0");
        this.dimensions = dimensions;
        this.distanceMetric = distanceMetric == null || distanceMetric.isBlank() ? "cosine" : distanceMetric.toLowerCase(Locale.ROOT);
        this.vectorOps = resolveVectorOps(this.distanceMetric);
        this.objectMapper = objectMapper;
        initializeSchema();
    }

    public PgVectorStore(@NonNull DataSource dataSource,
                         @NonNull String tableName,
                         int dimensions,
                         @Nullable String distanceMetric) {
        this(dataSource, tableName, dimensions, distanceMetric, new ObjectMapper());
    }

    public PgVectorStore(@NonNull DataSource dataSource,
                         @NonNull String tableName,
                         int dimensions) {
        this(dataSource, tableName, dimensions, "cosine");
    }

    // ---- VectorStore implementation ----

    @Override
    public void upsert(@NonNull List<Chunk> chunks) {
        if (chunks.isEmpty()) return;

        String sql = """
            INSERT INTO "%s" (id, document_id, text, chunk_index, token_count, parent_chunk_id, metadata, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (id) DO UPDATE SET
                document_id = EXCLUDED.document_id,
                text = EXCLUDED.text,
                chunk_index = EXCLUDED.chunk_index,
                token_count = EXCLUDED.token_count,
                parent_chunk_id = EXCLUDED.parent_chunk_id,
                metadata = EXCLUDED.metadata,
                embedding = EXCLUDED.embedding
            """.formatted(tableName);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Chunk chunk : chunks) {
                    float[] emb = chunk.embedding();
                    if (emb == null) {
                        throw new IllegalArgumentException("Chunk " + chunk.id() + " has no embedding");
                    }
                    if (emb.length != dimensions) {
                        throw new IllegalArgumentException(
                            "Chunk " + chunk.id() + " embedding length " + emb.length + " != " + dimensions);
                    }

                    ps.setString(1, chunk.id());
                    ps.setString(2, chunk.documentId());
                    ps.setString(3, chunk.text());
                    ps.setInt(4, chunk.index());
                    ps.setInt(5, chunk.tokenCount());
                    ps.setString(6, chunk.parentChunkId());
                    ps.setString(7, toJson(chunk.metadata()));
                    ps.setObject(8, toPgVectorLiteral(emb));
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new VectorStoreException("upsert failed", e);
        }
    }

    @Override
    public @NonNull List<RetrievalResult> search(@NonNull float[] queryEmbedding, int topK, @NonNull Map<String, Object> filters) {
        if (topK <= 0) return List.of();
        if (queryEmbedding.length != dimensions) {
            throw new IllegalArgumentException("queryEmbedding length " + queryEmbedding.length + " != " + dimensions);
        }

        String operator = switch (distanceMetric) {
            case "cosine" -> "<=>";
            case "l2" -> "<->";
            case "inner_product" -> "<#>";
            default -> "<=>";
        };

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, document_id, text, chunk_index, token_count, parent_chunk_id, metadata, embedding ")
           .append(operator).append(" ? AS score ")
           .append("FROM \"").append(tableName).append("\" ");

        boolean hasFilters = !filters.isEmpty();
        if (hasFilters) {
            sql.append("WHERE metadata @> ?::jsonb ");
        }

        sql.append("ORDER BY embedding ").append(operator).append(" ? ");

        // For inner_product, higher is better; for cosine/l2, lower is better
        if (distanceMetric.equals("inner_product")) {
            sql.append("DESC ");
        }

        sql.append("LIMIT ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            int paramIdx = 1;
            ps.setObject(paramIdx++, toPgVectorLiteral(queryEmbedding));
            if (hasFilters) {
                ps.setString(paramIdx++, toJson(filters));
            }
            ps.setObject(paramIdx++, toPgVectorLiteral(queryEmbedding));
            ps.setInt(paramIdx, topK);

            try (ResultSet rs = ps.executeQuery()) {
                List<RetrievalResult> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapRowToResult(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new VectorStoreException("search failed", e);
        }
    }

    @Override
    public void delete(@NonNull Set<String> chunkIds) {
        if (chunkIds.isEmpty()) return;

        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        String sql = "DELETE FROM \"" + tableName + "\" WHERE id IN (" + placeholders + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int i = 1;
            for (String id : chunkIds) {
                ps.setString(i++, id);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new VectorStoreException("delete failed", e);
        }
    }

    @Override
    public void deleteByDocument(@NonNull String documentId) {
        String sql = "DELETE FROM \"" + tableName + "\" WHERE document_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, documentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new VectorStoreException("deleteByDocument failed", e);
        }
    }

    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM \"" + tableName + "\"";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            throw new VectorStoreException("count failed", e);
        }
    }

    @Override
    public @NonNull String storeName() {
        return "pgvector:" + tableName;
    }

    // ---- Schema initialization ----

    private void initializeSchema() {
        String createTable = """
            CREATE TABLE IF NOT EXISTS "%s" (
                id VARCHAR(255) PRIMARY KEY,
                document_id VARCHAR(255) NOT NULL,
                text TEXT NOT NULL,
                chunk_index INT,
                token_count INT,
                parent_chunk_id VARCHAR(255),
                metadata JSONB,
                embedding vector(%d)
            )
            """.formatted(tableName, dimensions);

        String createIndexEmbedding = """
            CREATE INDEX IF NOT EXISTS idx_%s_embedding ON "%s"
            USING hnsw (embedding %s)
            """.formatted(tableName, tableName, vectorOps);

        String createIndexDocId = """
            CREATE INDEX IF NOT EXISTS idx_%s_doc_id ON "%s" (document_id)
            """.formatted(tableName, tableName);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            stmt.execute(createTable);
            stmt.execute(createIndexEmbedding);
            stmt.execute(createIndexDocId);
        } catch (SQLException e) {
            throw new VectorStoreException("schema initialization failed", e);
        }
    }

    // ---- Helpers ----

    private String resolveVectorOps(String metric) {
        return switch (metric) {
            case "cosine" -> "vector_cosine_ops";
            case "l2" -> "vector_l2_ops";
            case "inner_product" -> "vector_ip_ops";
            default -> throw new IllegalArgumentException("Unknown distance metric: " + metric);
        };
    }

    private String toPgVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJson(@NonNull Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new VectorStoreException("JSON serialization failed", e);
        }
    }

    private RetrievalResult mapRowToResult(@NonNull ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String documentId = rs.getString("document_id");
        String text = rs.getString("text");
        int index = rs.getInt("chunk_index");
        int tokenCount = rs.getInt("token_count");
        String parentChunkId = rs.getString("parent_chunk_id");
        String metadataJson = rs.getString("metadata");
        double score = rs.getDouble("score");

        Map<String, Object> metadata;
        try {
            metadata = metadataJson == null
                ? Map.of()
                : objectMapper.readValue(metadataJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException e) {
            metadata = Map.of();
        }

        Chunk chunk = new Chunk(id, documentId, text, index, tokenCount, parentChunkId, metadata);
        return new RetrievalResult(chunk, score);
    }

    private static @NonNull String sanitizeIdentifier(@NonNull String name) {
        if (!name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid table name: " + name);
        }
        return name;
    }

    public @NonNull String tableName() { return tableName; }
    public int dimensions() { return dimensions; }
    public @NonNull String distanceMetric() { return distanceMetric; }

    /**
     * Runtime exception for vector store errors.
     */
    public static final class VectorStoreException extends RuntimeException {
        public VectorStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
