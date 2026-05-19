package com.chorus.engine.memory;

import com.chorus.engine.core.context.Message;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Token-aware short-term memory with LRU eviction.
 *
 * <p>Maintains a rolling window of recent messages. When the token budget
 * is exceeded, evicts oldest messages first (FIFO). Supports semantic
 * search over recent history via simple keyword overlap scoring.
 *
 * <p>Thread-safe.
 */
public final class ShortTermMemory {

    private final int maxTokens;
    private final int maxMessages;
    private final Deque<MemoryEntry> entries = new ConcurrentLinkedDeque<>();
    private final Map<String, MemoryEntry> byId = new ConcurrentHashMap<>();
    private volatile int currentTokens = 0;

    public ShortTermMemory(int maxTokens, int maxMessages) {
        this.maxTokens = maxTokens;
        this.maxMessages = maxMessages;
    }

    public void add(@NonNull Message message, int tokenCount) {
        String id = UUID.randomUUID().toString();
        MemoryEntry entry = new MemoryEntry(id, message, tokenCount, Instant.now());
        entries.addLast(entry);
        byId.put(id, entry);
        currentTokens += tokenCount;
        evictIfNeeded();
    }

    public @NonNull List<Message> getRecent(int n) {
        List<Message> result = new ArrayList<>();
        Iterator<MemoryEntry> it = entries.descendingIterator();
        while (it.hasNext() && result.size() < n) {
            result.addFirst(it.next().message);
        }
        return result;
    }

    public @NonNull List<Message> getAll() {
        return entries.stream().map(e -> e.message).toList();
    }

    public @Nullable Message get(@NonNull String id) {
        MemoryEntry e = byId.get(id);
        return e != null ? e.message : null;
    }

    public void clear() {
        entries.clear();
        byId.clear();
        currentTokens = 0;
    }

    public int currentTokens() {
        return currentTokens;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Simple keyword overlap search for quick retrieval.
     */
    public @NonNull List<Message> search(@NonNull String query, int topK) {
        Set<String> queryTerms = tokenize(query);
        return entries.stream()
            .sorted(Comparator.comparingDouble(e -> -score(e, queryTerms)))
            .limit(topK)
            .map(e -> e.message)
            .toList();
    }

    private double score(@NonNull MemoryEntry entry, @NonNull Set<String> queryTerms) {
        Set<String> msgTerms = tokenize(entry.message.content());
        if (msgTerms.isEmpty()) return 0.0;
        long overlap = msgTerms.stream().filter(queryTerms::contains).count();
        return (double) overlap / msgTerms.size();
    }

    private @NonNull Set<String> tokenize(@NonNull String text) {
        return Set.of(text.toLowerCase().split("\\s+"));
    }

    private void evictIfNeeded() {
        while (currentTokens > maxTokens && !entries.isEmpty()) {
            MemoryEntry oldest = entries.pollFirst();
            if (oldest != null) {
                byId.remove(oldest.id);
                currentTokens -= oldest.tokenCount;
            }
        }
        while (entries.size() > maxMessages && !entries.isEmpty()) {
            MemoryEntry oldest = entries.pollFirst();
            if (oldest != null) {
                byId.remove(oldest.id);
                currentTokens -= oldest.tokenCount;
            }
        }
    }

    private record MemoryEntry(@NonNull String id, @NonNull Message message, int tokenCount, @NonNull Instant timestamp) {}
}
