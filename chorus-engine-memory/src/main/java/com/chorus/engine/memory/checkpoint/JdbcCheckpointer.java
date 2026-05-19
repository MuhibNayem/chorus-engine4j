package com.chorus.engine.memory.checkpoint;

import com.chorus.engine.core.checkpoint.AgentState;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Production-grade JDBC-based checkpointer supporting PostgreSQL, MySQL, and H2.
 * <p>
 * Thread-safe when backed by a pooled {@link DataSource}.
 * <p>
 * Schema is auto-created on construction. Writes are transactional.
 */
public final class JdbcCheckpointer implements Checkpointer {

    private final DataSource dataSource;
    private final String tableName;
    private final CheckpointSerializer serializer;

    public JdbcCheckpointer(@NonNull DataSource dataSource, @NonNull String tableName) {
        this(dataSource, tableName, new JsonCheckpointSerializer());
    }

    public JdbcCheckpointer(@NonNull DataSource dataSource, @NonNull String tableName, @NonNull CheckpointSerializer serializer) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.serializer = serializer;
        ensureTableExists();
    }

    private void ensureTableExists() {
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
                run_id VARCHAR(255) NOT NULL,
                sequence_number BIGINT NOT NULL,
                state_json TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (run_id, sequence_number)
            )
            """.formatted(tableName);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create checkpoint table: " + tableName, e);
        }
    }

    @Override
    public @NonNull Result<Void, Checkpointer.CheckpointError> save(@NonNull String runId, long sequenceNumber, @NonNull AgentState state) {
        String deleteSql = "DELETE FROM " + tableName + " WHERE run_id = ? AND sequence_number = ?";
        String insertSql = "INSERT INTO " + tableName + " (run_id, sequence_number, state_json) VALUES (?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
                    deletePs.setString(1, runId);
                    deletePs.setLong(2, sequenceNumber);
                    deletePs.executeUpdate();
                }
                try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                    insertPs.setString(1, runId);
                    insertPs.setLong(2, sequenceNumber);
                    insertPs.setString(3, serializer.serialize(state));
                    insertPs.executeUpdate();
                }
                conn.commit();
                return new Result.Ok<>(null);
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                return Result.err(Checkpointer.CheckpointError.of("SAVE_FAILED", "Failed to save checkpoint", e));
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            return Result.err(Checkpointer.CheckpointError.of("SAVE_FAILED", "Failed to acquire connection", e));
        }
    }

    @Override
    public @NonNull Result<AgentState, Checkpointer.CheckpointError> loadLatest(@NonNull String runId) {
        String sql = "SELECT state_json FROM " + tableName + " WHERE run_id = ? ORDER BY sequence_number DESC LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("state_json");
                    return Result.ok(serializer.deserialize(json));
                }
                return Result.err(Checkpointer.CheckpointError.of("NOT_FOUND", "No checkpoints for run: " + runId));
            }
        } catch (SQLException e) {
            return Result.err(Checkpointer.CheckpointError.of("LOAD_FAILED", "Failed to load latest checkpoint", e));
        }
    }

    @Override
    public @NonNull Result<AgentState, Checkpointer.CheckpointError> load(@NonNull String runId, long sequenceNumber) {
        String sql = "SELECT state_json FROM " + tableName + " WHERE run_id = ? AND sequence_number = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setLong(2, sequenceNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("state_json");
                    return Result.ok(serializer.deserialize(json));
                }
                return Result.err(Checkpointer.CheckpointError.of("NOT_FOUND", "No checkpoint at sequence " + sequenceNumber));
            }
        } catch (SQLException e) {
            return Result.err(Checkpointer.CheckpointError.of("LOAD_FAILED", "Failed to load checkpoint", e));
        }
    }

    @Override
    public @NonNull Result<List<CheckpointRef>, Checkpointer.CheckpointError> list(@NonNull String runId) {
        String sql = "SELECT sequence_number, created_at FROM " + tableName
            + " WHERE run_id = ? ORDER BY sequence_number DESC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CheckpointRef> refs = new ArrayList<>();
                while (rs.next()) {
                    long seq = rs.getLong("sequence_number");
                    Timestamp ts = rs.getTimestamp("created_at");
                    long millis = ts != null ? ts.getTime() : System.currentTimeMillis();
                    refs.add(new CheckpointRef(runId, seq, millis));
                }
                return Result.ok(refs);
            }
        } catch (SQLException e) {
            return Result.err(Checkpointer.CheckpointError.of("LIST_FAILED", "Failed to list checkpoints", e));
        }
    }

    @Override
    public @NonNull Result<Void, Checkpointer.CheckpointError> prune(@NonNull String runId, long keepAfterSequence) {
        String sql = "DELETE FROM " + tableName + " WHERE run_id = ? AND sequence_number < ?";

        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, runId);
                ps.setLong(2, keepAfterSequence);
                ps.executeUpdate();
                conn.commit();
                return new Result.Ok<>(null);
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                return Result.err(Checkpointer.CheckpointError.of("PRUNE_FAILED", "Failed to prune checkpoints", e));
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            return Result.err(Checkpointer.CheckpointError.of("PRUNE_FAILED", "Failed to acquire connection", e));
        }
    }
}
