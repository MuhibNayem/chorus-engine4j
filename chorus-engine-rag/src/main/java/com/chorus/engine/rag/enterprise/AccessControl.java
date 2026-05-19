package com.chorus.engine.rag.enterprise;

import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Access control for RAG documents and chunks.
 *
 * <p>Documents carry ACL metadata (roles, users, departments).
 * The retrieval pipeline filters out chunks the current principal cannot access.
 *
 * <p>Patterns:
 * <ul>
 *   <li>RBAC: role-based (admin, editor, viewer)</li>
 *   <li>ABAC: attribute-based (department=engineering AND clearance=confidential)</li>
 *   <li>ReBAC: relationship-based (owner, collaborator)</li>
 * </ul>
 */
public interface AccessControl {

    boolean canRead(@NonNull Principal principal, @NonNull Chunk chunk);

    boolean canIngest(@NonNull Principal principal, @NonNull String collection);

    boolean canDelete(@NonNull Principal principal, @NonNull String documentId);

    @NonNull List<Chunk> filter(@NonNull Principal principal, @NonNull List<Chunk> chunks);

    record Principal(
        @NonNull String userId,
        @NonNull Set<String> roles,
        @NonNull Map<String, Object> attributes,
        @NonNull String tenantId
    ) {}

    /**
     * Simple RBAC implementation.
     */
    final class RoleBased implements AccessControl {

        private final Map<String, Set<String>> collectionRoles;

        public RoleBased(@NonNull Map<String, Set<String>> collectionRoles) {
            this.collectionRoles = Map.copyOf(collectionRoles);
        }

        @Override
        public boolean canRead(@NonNull Principal principal, @NonNull Chunk chunk) {
            String collection = chunk.metadata().getOrDefault("collection", "default").toString();
            Set<String> required = collectionRoles.getOrDefault(collection, Set.of());
            return principal.roles().stream().anyMatch(required::contains);
        }

        @Override
        public boolean canIngest(@NonNull Principal principal, @NonNull String collection) {
            return principal.roles().contains("ingest:" + collection) || principal.roles().contains("admin");
        }

        @Override
        public boolean canDelete(@NonNull Principal principal, @NonNull String documentId) {
            return principal.roles().contains("admin") || principal.roles().contains("delete");
        }

        @Override
        public @NonNull List<Chunk> filter(@NonNull Principal principal, @NonNull List<Chunk> chunks) {
            return chunks.stream().filter(c -> canRead(principal, c)).toList();
        }
    }
}
