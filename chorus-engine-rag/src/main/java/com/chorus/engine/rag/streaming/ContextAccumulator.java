package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Incrementally assembles retrieved chunks into a context string,
 * enforcing token budgets and deduplicating across retrieval waves.
 *
 * <p>Each wave contributes chunks until the token budget is exhausted.
 * Waves are processed in priority order. Within a wave, chunks are
 * ordered by relevance score. A chunk that appears in multiple waves
 * is only counted once, using its highest score for citation tracking.
 */
public final class ContextAccumulator {

    private final int maxContextTokens;
    private final int tokensPerChar;
    private final LinkedHashMap<String, AccumulatedChunk> chunksById = new LinkedHashMap<>();
    private int usedTokens = 0;
    private int nextCitationIndex = 1;

    public ContextAccumulator(int maxContextTokens) {
        this(maxContextTokens, 4);
    }

    public ContextAccumulator(int maxContextTokens, int tokensPerChar) {
        this.maxContextTokens = maxContextTokens;
        this.tokensPerChar = tokensPerChar;
    }

    /**
     * Incorporate chunks from a retrieval wave.
     *
     * @param chunks      retrieved chunks, ordered by relevance
     * @param stageName   the retrieval stage that produced these chunks
     * @param relevanceFn function to extract relevance score per chunk
     * @return number of new chunks added (not already present from earlier waves)
     */
    public int addWave(
        @NonNull List<Chunk> chunks,
        @NonNull String stageName,
        java.util.function.@NonNull Function<Chunk, Double> relevanceFn
    ) {
        int added = 0;
        for (Chunk chunk : chunks) {
            AccumulatedChunk existing = chunksById.get(chunk.id());
            double relevance = relevanceFn.apply(chunk);

            if (existing != null) {
                // Update with better score / additional source stage
                if (relevance > existing.relevanceScore) {
                    existing.relevanceScore = relevance;
                }
                if (!existing.sourceStages.contains(stageName)) {
                    existing.sourceStages.add(stageName);
                }
                continue;
            }

            // Estimate token cost
            String entry = formatEntry(nextCitationIndex, chunk.text().trim());
            int entryTokens = entry.length() / tokensPerChar;

            if (usedTokens + entryTokens > maxContextTokens) {
                break; // Budget exhausted
            }

            AccumulatedChunk acc = new AccumulatedChunk(
                nextCitationIndex++, chunk, relevance, stageName, entry, entryTokens
            );
            chunksById.put(chunk.id(), acc);
            usedTokens += entryTokens;
            added++;
        }
        return added;
    }

    /**
     * Build the context string from all accumulated chunks.
     */
    public @NonNull String buildContext() {
        StringBuilder sb = new StringBuilder();
        for (AccumulatedChunk acc : chunksById.values()) {
            sb.append(acc.formattedEntry).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Build citation list for the current accumulated context.
     */
    public @NonNull List<RagStreamEvent.Citation> buildCitations() {
        List<RagStreamEvent.Citation> citations = new ArrayList<>();
        for (AccumulatedChunk acc : chunksById.values()) {
            citations.add(new RagStreamEvent.Citation(
                acc.citationIndex,
                acc.chunk.id(),
                acc.chunk.documentId(),
                acc.chunk.text(),
                acc.relevanceScore,
                acc.sourceStages.get(0) // Primary source stage
            ));
        }
        return citations;
    }

    /**
     * Get chunks that were added since the last snapshot.
     * Used to compute supplemental context after generation has started.
     */
    public @NonNull List<Chunk> getNewChunksSince(@NonNull Set<String> knownIds) {
        List<Chunk> result = new ArrayList<>();
        for (Map.Entry<String, AccumulatedChunk> e : chunksById.entrySet()) {
            if (!knownIds.contains(e.getKey())) {
                result.add(e.getValue().chunk);
            }
        }
        return result;
    }

    public @NonNull Set<String> snapshotIds() {
        return Set.copyOf(chunksById.keySet());
    }

    public int usedTokens() { return usedTokens; }
    public int chunkCount() { return chunksById.size(); }
    public int remainingTokens() { return maxContextTokens - usedTokens; }

    private static @NonNull String formatEntry(int citationIndex, String text) {
        return "[" + citationIndex + "] " + text;
    }

    private static final class AccumulatedChunk {
        final int citationIndex;
        final Chunk chunk;
        double relevanceScore;
        final List<String> sourceStages;
        final String formattedEntry;
        final int tokenCost;

        AccumulatedChunk(int citationIndex, Chunk chunk, double relevanceScore,
                         String sourceStage, String formattedEntry, int tokenCost) {
            this.citationIndex = citationIndex;
            this.chunk = chunk;
            this.relevanceScore = relevanceScore;
            this.sourceStages = new ArrayList<>(List.of(sourceStage));
            this.formattedEntry = formattedEntry;
            this.tokenCost = tokenCost;
        }
    }
}
