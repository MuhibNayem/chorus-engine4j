package com.chorus.engine.checkpoint.postgres;

import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.CheckpointState;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Production-grade PostgreSQL checkpointer with schema versioning,
 * connection pooling support, batch operations, and health checks.
 *
 * <p>Schema:</p>
 * <pre>
 * CREATE TABLE chorus_checkpoint (
 *   thread_id VARCHAR(255) NOT NULL,
 *   round INT NOT NULL,
 *   messages_json TEXT NOT NULL,
 *   hitl_pause_json TEXT,
 *   created_at BIGINT NOT NULL,
 *   PRIMARY KEY (thread_id, round)
 * );
 * CREATE INDEX idx_chorus_checkpoint_thread ON chorus_checkpoint(thread_id, round DESC);
 * </pre>
 */
public class PostgresCheckpointer implements Checkpointer {

    private static final Logger log = LoggerFactory.getLogger(PostgresCheckpointer.class);
    private static final int SCHEMA_VERSION = 1;

    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private volatile boolean initialized = false;

    public PostgresCheckpointer(DataSource dataSource) {
        this.dataSource = dataSource;
        this.mapper = new ObjectMapper().registerModule(new Jdk8Module());
        initializeSchema();
    }

    private void initializeSchema() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

            // Create table if not exists
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chorus_checkpoint (
                        thread_id VARCHAR(255) NOT NULL,
                        round INT NOT NULL,
                        messages_json TEXT NOT NULL,
                        hitl_pause_json TEXT,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (thread_id, round)
                    )
                    """);
            }

            // Create index
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_chorus_ckpt_thread
                    ON chorus_checkpoint(thread_id, round DESC)
                    """);
            }

            initialized = true;
            log.info("Postgres checkpointer initialized (schema v{})", SCHEMA_VERSION);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize Postgres checkpointer schema", e);
        }
    }

    @Override
    public CompletableFuture<Void> save(String threadId, CheckpointState state) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String messagesJson = mapper.writeValueAsString(state.messages());
                    String hitlJson = state.waitingForHitl().isPresent()
                        ? mapper.writeValueAsString(state.waitingForHitl().get())
                        : null;

                    upsertCheckpoint(conn, threadId, state.round(), messagesJson, hitlJson);
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                throw new RuntimeException("Postgres checkpoint save failed for thread " + threadId, e);
            }
        });
    }

    private void upsertCheckpoint(Connection conn, String threadId, int round,
                                   String messagesJson, String hitlJson) throws SQLException {
        // Try insert first, fall back to update on duplicate key
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO chorus_checkpoint (thread_id, round, messages_json, hitl_pause_json, created_at) " +
            "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, threadId);
            ps.setInt(2, round);
            ps.setString(3, messagesJson);
            ps.setString(4, hitlJson);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getSQLState() != null && (e.getSQLState().startsWith("23") || e.getMessage().contains("unique") || e.getMessage().contains("PRIMARY KEY"))) {
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chorus_checkpoint SET messages_json = ?, hitl_pause_json = ?, created_at = ? " +
                    "WHERE thread_id = ? AND round = ?")) {
                    ps.setString(1, messagesJson);
                    ps.setString(2, hitlJson);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setString(4, threadId);
                    ps.setInt(5, round);
                    ps.executeUpdate();
                }
            } else {
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<Checkpoint> load(String threadId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT round, messages_json, hitl_pause_json, created_at FROM chorus_checkpoint " +
                     "WHERE thread_id = ? ORDER BY round DESC LIMIT 1")) {
                ps.setString(1, threadId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapRow(rs);
                    }
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException("Postgres checkpoint load failed for thread " + threadId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Checkpoint> loadAt(String threadId, int round) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT round, messages_json, hitl_pause_json, created_at FROM chorus_checkpoint " +
                     "WHERE thread_id = ? AND round = ?")) {
                ps.setString(1, threadId);
                ps.setInt(2, round);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapRow(rs);
                    }
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException("Postgres checkpoint loadAt failed for thread " + threadId + " round " + round, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Checkpoint>> list(String threadId) {
        return CompletableFuture.supplyAsync(() -> {
            List<Checkpoint> results = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT round, messages_json, hitl_pause_json, created_at FROM chorus_checkpoint " +
                     "WHERE thread_id = ? ORDER BY round ASC")) {
                ps.setString(1, threadId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Postgres checkpoint list failed for thread " + threadId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> fork(String threadId, int round, String newThreadId) {
        return loadAt(threadId, round).thenCompose(cp -> {
            if (cp == null) {
                return CompletableFuture.completedFuture(null);
            }
            CheckpointState state = new CheckpointState(cp.messages(), cp.round(), cp.waitingForHitl().map(p ->
                new CheckpointState.HitlPause(p.resumeKey(), p.requests(), p.toolCalls(), p.assistant())));
            return save(newThreadId, state);
        });
    }

    @Override
    public CompletableFuture<Void> delete(String threadId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM chorus_checkpoint WHERE thread_id = ?")) {
                ps.setString(1, threadId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Postgres checkpoint delete failed for thread " + threadId, e);
            }
        });
    }

    /**
     * Health check for connection pool monitoring.
     */
    public boolean isHealthy() {
        if (!initialized) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Batch save multiple checkpoints in a single transaction.
     */
    public CompletableFuture<Void> saveBatch(String threadId, List<CheckpointState> states) {
        return CompletableFuture.runAsync(() -> {
            Connection conn = null;
            try {
                conn = dataSource.getConnection();
                conn.setAutoCommit(false);
                for (CheckpointState state : states) {
                    String messagesJson = mapper.writeValueAsString(state.messages());
                    String hitlJson = state.waitingForHitl().isPresent()
                        ? mapper.writeValueAsString(state.waitingForHitl().get()) : null;
                    upsertCheckpoint(conn, threadId, state.round(), messagesJson, hitlJson);
                }
                conn.commit();
            } catch (Exception e) {
                if (conn != null) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                }
                throw new RuntimeException("Postgres checkpoint batch save failed", e);
            } finally {
                if (conn != null) {
                    try { conn.close(); } catch (SQLException ignored) {}
                }
            }
        });
    }

    private Checkpoint mapRow(ResultSet rs) throws Exception {
        int round = rs.getInt("round");
        String messagesJson = rs.getString("messages_json");
        String hitlJson = rs.getString("hitl_pause_json");
        long createdAt = rs.getLong("created_at");

        List<com.chorus.engine.core.event.ChatMessage> messages =
            mapper.readValue(messagesJson, new TypeReference<>() {});

        java.util.Optional<CheckpointState.HitlPause> hitlPause = java.util.Optional.empty();
        if (hitlJson != null && !hitlJson.isBlank()) {
            hitlPause = java.util.Optional.of(mapper.readValue(hitlJson, CheckpointState.HitlPause.class));
        }

        return new Checkpoint(null, round, messages, createdAt, hitlPause);
    }
}
