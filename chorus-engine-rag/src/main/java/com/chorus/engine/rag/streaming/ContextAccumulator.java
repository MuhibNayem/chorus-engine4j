package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
    private final AtomicInteger usedTokens = new AtomicInteger(0);
    private final AtomicInteger nextCitationIndex = new AtomicInteger(1);
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

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
        lock.writeLock().lock();
        try {
            int added = 0;
            for (Chunk chunk : chunks) {
                AccumulatedChunk existing = chunksById.get(chunk.id());
                double relevance = relevanceFn.apply(chunk);

                if (existing != null) {
                    if (relevance > existing.relevanceScore) {
                        existing.relevanceScore = relevance;
                    }
                    if (!existing.sourceStages.contains(stageName)) {
                        existing.sourceStages.add(stageName);
                    }
                    continue;
                }

                String entry = formatEntry(nextCitationIndex.get(), chunk.text().trim());
                int entryTokens = entry.length() / tokensPerChar;

                if (usedTokens.get() + entryTokens > maxContextTokens) {
                    break;
                }

                int citationIdx = nextCitationIndex.getAndIncrement();
                AccumulatedChunk acc = new AccumulatedChunk(
                    citationIdx, chunk, relevance, stageName, entry, entryTokens
                );
                chunksById.put(chunk.id(), acc);
                usedTokens.addAndGet(entryTokens);
                added++;
            }
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Build the context string from all accumulated chunks.
     */
    public @NonNull String buildContext() {
        lock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (AccumulatedChunk acc : chunksById.values()) {
                sb.append(acc.formattedEntry).append("\n\n");
            }
            return sb.toString().trim();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Build citation list for the current accumulated context.
     */
    public @NonNull List<RagStreamEvent.Citation> buildCitations() {
        lock.readLock().lock();
        try {
            List<RagStreamEvent.Citation> citations = new ArrayList<>();
            for (AccumulatedChunk acc : chunksById.values()) {
                String primaryStage = acc.sourceStages.isEmpty() ? "unknown" : acc.sourceStages.get(0);
                citations.add(new RagStreamEvent.Citation(
                    acc.citationIndex,
                    acc.chunk.id(),
                    acc.chunk.documentId(),
                    acc.chunk.text(),
                    acc.relevanceScore,
                    primaryStage
                ));
            }
            return citations;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get chunks that were added since the last snapshot.
     */
    public @NonNull List<Chunk> getNewChunksSince(@NonNull Set<String> knownIds) {
        lock.readLock().lock();
        try {
            List<Chunk> result = new ArrayList<>();
            for (Map.Entry<String, AccumulatedChunk> e : chunksById.entrySet()) {
                if (!knownIds.contains(e.getKey())) {
                    result.add(e.getValue().chunk);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public @NonNull Set<String> snapshotIds() {
        lock.readLock().lock();
        try {
            return Set.copyOf(chunksById.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    public int usedTokens() { return usedTokens.get(); }
    public int chunkCount() {
        lock.readLock().lock();
        try {
            return chunksById.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    public int remainingTokens() { return maxContextTokens - usedTokens.get(); }

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
