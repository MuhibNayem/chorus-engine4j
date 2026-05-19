package com.chorus.engine.rag.enterprise;

import com.chorus.engine.rag.document.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AccessControlTest {

    @Test
    void rbacFilter_allowsMatchingRoles() {
        AccessControl.RoleBased ac = new AccessControl.RoleBased(Map.of(
            "public", Set.of("viewer", "editor", "admin"),
            "private", Set.of("admin")
        ));

        Chunk publicChunk = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("collection", "public"));
        Chunk privateChunk = new Chunk("c2", "d2", "text", 0, 1, null, Map.of("collection", "private"));

        AccessControl.Principal viewer = new AccessControl.Principal("u1", Set.of("viewer"), Map.of(), "t1");
        List<Chunk> filtered = ac.filter(viewer, List.of(publicChunk, privateChunk));

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).id()).isEqualTo("c1");
    }

    @Test
    void rbac_canRead_checksCollectionRoles() {
        AccessControl.RoleBased ac = new AccessControl.RoleBased(Map.of(
            "engineering", Set.of("engineer", "admin")
        ));

        Chunk chunk = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("collection", "engineering"));
        AccessControl.Principal engineer = new AccessControl.Principal("u1", Set.of("engineer"), Map.of(), "t1");
        AccessControl.Principal sales = new AccessControl.Principal("u2", Set.of("sales"), Map.of(), "t1");

        assertThat(ac.canRead(engineer, chunk)).isTrue();
        assertThat(ac.canRead(sales, chunk)).isFalse();
    }

    @Test
    void rbac_canRead_defaultsToEmptyRequiredRoles() {
        AccessControl.RoleBased ac = new AccessControl.RoleBased(Map.of());

        Chunk chunk = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("collection", "unknown"));
        AccessControl.Principal anyone = new AccessControl.Principal("u1", Set.of(), Map.of(), "t1");

        assertThat(ac.canRead(anyone, chunk)).isFalse();
    }

    @Test
    void rbac_canIngest_requiresIngestRoleOrAdmin() {
        AccessControl.RoleBased ac = new AccessControl.RoleBased(Map.of());

        AccessControl.Principal admin = new AccessControl.Principal("u1", Set.of("admin"), Map.of(), "t1");
        AccessControl.Principal ingest = new AccessControl.Principal("u2", Set.of("ingest:docs"), Map.of(), "t1");
        AccessControl.Principal viewer = new AccessControl.Principal("u3", Set.of("viewer"), Map.of(), "t1");

        assertThat(ac.canIngest(admin, "docs")).isTrue();
        assertThat(ac.canIngest(ingest, "docs")).isTrue();
        assertThat(ac.canIngest(viewer, "docs")).isFalse();
    }

    @Test
    void rbac_canDelete_requiresAdminOrDeleteRole() {
        AccessControl.RoleBased ac = new AccessControl.RoleBased(Map.of());

        AccessControl.Principal admin = new AccessControl.Principal("u1", Set.of("admin"), Map.of(), "t1");
        AccessControl.Principal deleter = new AccessControl.Principal("u2", Set.of("delete"), Map.of(), "t1");
        AccessControl.Principal viewer = new AccessControl.Principal("u3", Set.of("viewer"), Map.of(), "t1");

        assertThat(ac.canDelete(admin, "doc1")).isTrue();
        assertThat(ac.canDelete(deleter, "doc1")).isTrue();
        assertThat(ac.canDelete(viewer, "doc1")).isFalse();
    }

    @Test
    void rbac_filter_preservesOrderOfAllowedChunks() {
        AccessControl.RoleBased ac = new AccessControl.RoleBased(Map.of(
            "public", Set.of("viewer")
        ));

        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("collection", "public"));
        Chunk c2 = new Chunk("c2", "d2", "text", 0, 1, null, Map.of("collection", "private"));
        Chunk c3 = new Chunk("c3", "d3", "text", 0, 1, null, Map.of("collection", "public"));

        AccessControl.Principal viewer = new AccessControl.Principal("u1", Set.of("viewer"), Map.of(), "t1");
        List<Chunk> filtered = ac.filter(viewer, List.of(c1, c2, c3));

        assertThat(filtered).extracting(Chunk::id).containsExactly("c1", "c3");
    }

    @Test
    void nullRejection() {
        AccessControl.RoleBased ac = new AccessControl.RoleBased(Map.of());
        AccessControl.Principal p = new AccessControl.Principal("u1", Set.of(), Map.of(), "t1");
        Chunk c = new Chunk("c1", "d1", "text", 0, 1, null, Map.of());

        assertThatNullPointerException().isThrownBy(() -> ac.canRead(null, c));
        assertThatNullPointerException().isThrownBy(() -> ac.canRead(p, null));
        assertThatNullPointerException().isThrownBy(() -> ac.filter(null, List.of(c)));
        assertThatNullPointerException().isThrownBy(() -> ac.filter(p, null));
        assertThatNullPointerException().isThrownBy(() -> ac.canIngest(null, "coll"));
        assertThatNullPointerException().isThrownBy(() -> ac.canDelete(null, "doc"));
    }
}
