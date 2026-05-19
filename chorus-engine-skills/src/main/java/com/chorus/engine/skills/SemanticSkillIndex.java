package com.chorus.engine.skills;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.rag.store.VectorStore;
import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vector-based skill discovery using semantic similarity.
 * Stores skill embeddings and retrieves the most relevant skills for a query.
 */
public final class SemanticSkillIndex {

    private final VectorStore vectorStore;
    private final VectorOperations vectorOps;
    private final Map<String, Skill> skillByChunkId = new ConcurrentHashMap<>();

    public SemanticSkillIndex(@NonNull VectorStore vectorStore) {
        this(vectorStore, VectorOperations.autoDetect());
    }

    public SemanticSkillIndex(@NonNull VectorStore vectorStore, @NonNull VectorOperations vectorOps) {
        this.vectorStore = Objects.requireNonNull(vectorStore);
        this.vectorOps = Objects.requireNonNull(vectorOps);
    }

    /**
     * Index a skill with its embedding vector.
     *
     * @param skill     the skill to index
     * @param embedding the embedding vector for the skill
     */
    public void index(@NonNull Skill skill, float @NonNull [] embedding) {
        Objects.requireNonNull(skill);
        Objects.requireNonNull(embedding);

        String chunkId = "skill:" + skill.id();
        Chunk chunk = new Chunk(
            chunkId,
            chunkId,
            skill.name() + " - " + skill.description(),
            0,
            0,
            null,
            Map.of("skillId", skill.id())
        ).withEmbedding(embedding);

        vectorStore.upsert(List.of(chunk));
        skillByChunkId.put(chunkId, skill);
    }

    /**
     * Query for the top-K most relevant skills given a query embedding.
     *
     * @param queryEmbedding the query embedding vector
     * @param topK           maximum number of results
     * @return list of relevant skills, ordered by similarity (most relevant first)
     */
    public @NonNull List<Skill> query(float @NonNull [] queryEmbedding, int topK) {
        Objects.requireNonNull(queryEmbedding);
        if (topK < 1) {
            return List.of();
        }

        List<VectorStore.RetrievalResult> results = vectorStore.search(queryEmbedding, topK, Map.of());
        List<Skill> skills = new ArrayList<>();
        for (VectorStore.RetrievalResult result : results) {
            String chunkId = result.chunk().id();
            Skill skill = skillByChunkId.get(chunkId);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }
}
