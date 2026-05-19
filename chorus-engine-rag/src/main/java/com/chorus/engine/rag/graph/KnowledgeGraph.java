package com.chorus.engine.rag.graph;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Set;

/**
 * Lightweight knowledge graph interface for Graph RAG.
 *
 * <p>Implementations can be:
 * <ul>
 *   <li>In-memory adjacency lists (for small domains)</li>
 *   <li>Neo4j (for complex relationship modeling)</li>
 *   <li>RDF triple stores</li>
 *   <li>Graph databases (Amazon Neptune, Azure Cosmos DB Gremlin)</li>
 * </ul>
 */
public interface KnowledgeGraph {

    /**
     * Get entities connected to the given entity within N hops.
     */
    @NonNull List<Entity> getNeighbors(@NonNull String entityId, int hops, @NonNull RelationshipType type);

    /**
     * Find shortest path between two entities.
     */
    @NonNull List<Entity> findPath(@NonNull String fromId, @NonNull String toId);

    /**
     * Extract entities from text and add to the graph.
     */
    void extractAndLink(@NonNull String text, @NonNull String sourceDocumentId);

    long entityCount();

    long relationshipCount();

    record Entity(
        @NonNull String id,
        @NonNull String name,
        @NonNull String type, // "PERSON", "ORG", "CONCEPT", etc.
        @NonNull Set<String> sourceDocuments
    ) {}

    record Relationship(
        @NonNull String fromId,
        @NonNull String toId,
        @NonNull String predicate,
        double weight
    ) {}

    enum RelationshipType { ALL, IS_A, PART_OF, RELATED_TO, MENTIONS }
}
