package com.chorus.engine.rag.enterprise;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Tenant isolation model for multi-tenant RAG deployments.
 *
 * <p>Every document, chunk, and query is scoped to a tenant.
 * The vector store implementation must enforce tenant boundaries
 * at the storage layer (collection prefixes, row-level security, etc.).
 */
public record TenantIsolation(
    @NonNull String tenantId,
    @NonNull IsolationLevel level,
    @NonNull String collectionPrefix
) {
    public TenantIsolation {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(level);
        Objects.requireNonNull(collectionPrefix);
    }

    public @NonNull String scopedCollection(@NonNull String baseName) {
        return collectionPrefix + tenantId + "_" + baseName;
    }

    public @NonNull String scopedDocumentId(@NonNull String documentId) {
        return tenantId + ":" + documentId;
    }

    public enum IsolationLevel {
        /** Shared DB, prefixed collection names. */
        LOGICAL,
        /** Separate schemas per tenant. */
        SCHEMA,
        /** Separate DB instances per tenant. */
        PHYSICAL
    }
}
