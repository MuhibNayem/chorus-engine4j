package com.chorus.engine.rag.enterprise;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable audit log entry for RAG operations.
 *
 * <p>Required for enterprise compliance (SOX, HIPAA, GDPR, FedRAMP).
 * Every query, retrieval, and generation is logged with:
 * <ul>
 *   <li>Who (principal)</li>
 *   <li>What (query, retrieved chunks, answer)</li>
 *   <li>When (timestamp)</li>
 *   <li>Why (retrieval scores, model used, tokens)</li>
 * </ul>
 */
public record AuditLog(
    @NonNull String eventId,
    @NonNull Instant timestamp,
    @NonNull String tenantId,
    @NonNull String userId,
    @NonNull EventType eventType,
    @NonNull String query,
    @NonNull List<String> retrievedChunkIds,
    @Nullable String generatedAnswer,
    int inputTokens,
    int outputTokens,
    @NonNull Map<String, Object> metadata
) {
    public AuditLog {
        metadata = Map.copyOf(metadata);
        retrievedChunkIds = List.copyOf(retrievedChunkIds);
    }

    public enum EventType {
        QUERY, RETRIEVAL, GENERATION, INGESTION, DELETE, ACCESS_DENIED, ERROR
    }

    /**
     * Sink for audit logs. Implementations: file, database, SIEM, Kafka.
     */
    public interface Sink {
        void write(@NonNull AuditLog entry);

        @NonNull List<AuditLog> query(
            @NonNull String tenantId,
            @NonNull Instant from,
            @NonNull Instant to,
            int limit
        );
    }
}
