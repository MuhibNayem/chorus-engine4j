package com.chorus.engine.memory.hierarchical;

import com.chorus.engine.core.context.Message;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Episodic memory: chronological log of agent interactions and events.
 *
 * <p>Stores raw experiences with timestamps, entities, and outcomes.
 * Frequently accessed episodes can be promoted to semantic memory.
 * Modelled after human episodic memory — the "what happened" layer.
 */
public final class EpisodicMemory {

    private final Deque<Episode> episodes = new ConcurrentLinkedDeque<>();
    private final Map<String, Episode> byId = new ConcurrentHashMap<>();
    private final int maxEpisodes;
    private final int retentionDays;

    public EpisodicMemory(int maxEpisodes, int retentionDays) {
        this.maxEpisodes = maxEpisodes;
        this.retentionDays = retentionDays;
    }

    public @NonNull String record(@NonNull Message message, @NonNull String eventType,
                                  @Nullable Map<String, Object> entities, @Nullable String outcome) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(eventType, "eventType");
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        Episode ep = new Episode(id, message, eventType,
            entities != null ? Map.copyOf(entities) : Map.of(),
            outcome, now, 1);
        episodes.addLast(ep);
        byId.put(id, ep);
        evictIfNeeded(now);
        return id;
    }

    public @NonNull List<Episode> queryRecent(int n) {
        List<Episode> result = new ArrayList<>();
        Iterator<Episode> it = episodes.descendingIterator();
        while (it.hasNext() && result.size() < n) {
            result.add(it.next());
        }
        return result;
    }

    public @NonNull List<Episode> queryByEntity(@NonNull String entityKey, @NonNull Object entityValue, int topK) {
        return episodes.stream()
            .filter(ep -> entityValue.equals(ep.entities().get(entityKey)))
            .sorted(Comparator.comparing(Episode::timestamp).reversed())
            .limit(topK)
            .toList();
    }

    public @NonNull List<Episode> queryByType(@NonNull String eventType, int topK) {
        return episodes.stream()
            .filter(ep -> eventType.equals(ep.eventType()))
            .sorted(Comparator.comparing(Episode::timestamp).reversed())
            .limit(topK)
            .toList();
    }

    public @NonNull List<Episode> queryByTimeRange(@NonNull Instant from, @NonNull Instant to) {
        return episodes.stream()
            .filter(ep -> !ep.timestamp().isBefore(from) && !ep.timestamp().isAfter(to))
            .sorted(Comparator.comparing(Episode::timestamp).reversed())
            .toList();
    }

    public @Nullable Episode get(@NonNull String id) {
        return byId.get(id);
    }

    public synchronized void incrementAccessCount(@NonNull String id) {
        Episode ep = byId.get(id);
        if (ep != null) {
            episodes.remove(ep);
            Episode updated = ep.withAccessCount(ep.accessCount() + 1);
            episodes.addLast(updated);
            byId.put(id, updated);
        }
    }

    public @NonNull List<Episode> findHotEpisodes(int minAccessCount) {
        return episodes.stream()
            .filter(ep -> ep.accessCount() >= minAccessCount)
            .sorted(Comparator.comparingInt(Episode::accessCount).reversed())
            .toList();
    }

    public int size() { return episodes.size(); }

    public void clear() {
        episodes.clear();
        byId.clear();
    }

    private void evictIfNeeded() {
        evictIfNeeded(Instant.now());
    }

    private void evictIfNeeded(Instant referenceTime) {
        Instant cutoff = referenceTime.minusSeconds((long) retentionDays * 24 * 60 * 60);
        episodes.removeIf(ep -> {
            if (ep.timestamp().isBefore(cutoff)) {
                byId.remove(ep.id());
                return true;
            }
            return false;
        });
        while (episodes.size() > maxEpisodes && !episodes.isEmpty()) {
            Episode oldest = episodes.pollFirst();
            if (oldest != null) {
                byId.remove(oldest.id());
            }
        }
    }

    public record Episode(
        @NonNull String id,
        @NonNull Message message,
        @NonNull String eventType,
        @NonNull Map<String, Object> entities,
        @Nullable String outcome,
        @NonNull Instant timestamp,
        int accessCount
    ) {
        public Episode withAccessCount(int newCount) {
            return new Episode(id, message, eventType, entities, outcome, timestamp, newCount);
        }
    }
}
