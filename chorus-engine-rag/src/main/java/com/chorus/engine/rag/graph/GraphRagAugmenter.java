package com.chorus.engine.rag.graph;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Graph RAG augmenter: enriches vector retrieval results with
 * knowledge graph traversal for multi-hop reasoning.
 *
 * <p>Process:
 * <ol>
 *   <li>Extract entities from initial retrieved chunks</li>
 *   <li>Traverse graph 1-2 hops from those entities</li>
 *   <li>Fetch chunks linked to discovered entities</li>
 *   <li>Merge, deduplicate, and re-rank</li>
 * </ol>
 */
public final class GraphRagAugmenter {

    private final KnowledgeGraph knowledgeGraph;
    private final int maxHops;
    private final int maxEntitiesPerChunk;

    public GraphRagAugmenter(@NonNull KnowledgeGraph knowledgeGraph, int maxHops, int maxEntitiesPerChunk) {
        this.knowledgeGraph = knowledgeGraph;
        this.maxHops = maxHops;
        this.maxEntitiesPerChunk = maxEntitiesPerChunk;
    }

    public @NonNull List<RetrievalEngine.RetrievalResult> augment(
        @NonNull String query,
        @NonNull List<RetrievalEngine.RetrievalResult> initialResults
    ) {
        // Extract entities from top chunks
        Set<String> seedEntityIds = new HashSet<>();
        for (RetrievalEngine.RetrievalResult r : initialResults.subList(0, Math.min(5, initialResults.size()))) {
            // Simple heuristic: use documentId as proxy for entity
            // In production, use NER or LLM-based entity extraction
            seedEntityIds.add(r.chunk().documentId());
        }

        // Traverse graph
        Set<String> discoveredEntityIds = new HashSet<>(seedEntityIds);
        for (String entityId : seedEntityIds) {
            List<KnowledgeGraph.Entity> neighbors = knowledgeGraph.getNeighbors(
                entityId, maxHops, KnowledgeGraph.RelationshipType.ALL
            );
            for (KnowledgeGraph.Entity e : neighbors) {
                discoveredEntityIds.add(e.id());
            }
        }

        // Build augmented results from discovered entities
        List<RetrievalEngine.RetrievalResult> augmented = new ArrayList<>(initialResults);
        for (String entityId : discoveredEntityIds) {
            if (seedEntityIds.contains(entityId)) continue;
            // Placeholder: create synthetic result from graph entity
            Chunk syntheticChunk = new Chunk(
                java.util.UUID.randomUUID().toString(),
                entityId,
                "Related entity from knowledge graph: " + entityId,
                0, 0, null,
                java.util.Map.of("source", "knowledge_graph", "entityId", entityId)
            );
            augmented.add(new RetrievalEngine.RetrievalResult(syntheticChunk, 0.3, "graph"));
        }

        // Deduplicate by chunk ID
        Map<String, RetrievalEngine.RetrievalResult> deduped = new LinkedHashMap<>();
        for (RetrievalEngine.RetrievalResult r : augmented) {
            deduped.merge(r.chunk().id(), r, (a, b) -> a.score() >= b.score() ? a : b);
        }

        return deduped.values().stream()
            .sorted(Comparator.comparingDouble(r -> -r.score()))
            .toList();
    }
}
