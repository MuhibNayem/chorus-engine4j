package com.chorus.engine.memory;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Intelligent context window compaction strategies.
 *
 * <p>When token usage exceeds a threshold, compacts history while preserving
 * semantic meaning and critical information.
 */
public final class ContextCompactor {

    private final int targetTokens;

    public ContextCompactor(int targetTokens) {
        this.targetTokens = targetTokens;
    }

    /**
     * Summarization compaction: replaces the oldest N messages with a summary.
     * Preserves system prompt and the most recent messages.
     */
    public @NonNull CompactionResult summarize(@NonNull List<Message> history, @NonNull Summarizer summarizer) {
        if (history.size() <= 2) {
            return new CompactionResult(history, "history too short to compact");
        }

        // Always preserve system prompt and last 2 messages
        Message system = history.get(0).role() == Role.SYSTEM ? history.get(0) : null;
        int preserveEnd = Math.min(3, history.size());
        List<Message> recent = history.subList(history.size() - preserveEnd, history.size());
        List<Message> middle = history.subList(system != null ? 1 : 0, history.size() - preserveEnd);

        if (middle.isEmpty()) {
            return new CompactionResult(history, "no middle section to compact");
        }

        String summary = summarizer.summarize(middle);
        List<Message> compacted = new ArrayList<>();
        if (system != null) compacted.add(system);
        compacted.add(Message.assistant("[Earlier conversation summarized]: " + summary));
        compacted.addAll(recent);

        return new CompactionResult(List.copyOf(compacted), "summarized " + middle.size() + " messages");
    }

    /**
     * Selective retention: keeps only messages most relevant to the current query.
     */
    public @NonNull CompactionResult selectiveRetention(
        @NonNull List<Message> history,
        @NonNull String currentQuery,
        @NonNull RelevanceScorer scorer
    ) {
        if (history.size() <= 4) {
            return new CompactionResult(history, "history too short");
        }

        Message system = history.get(0).role() == Role.SYSTEM ? history.get(0) : null;
        List<Message> candidates = new ArrayList<>(history);
        if (system != null) candidates.remove(0);

        // Score each message against current query
        List<ScoredMessage> scored = candidates.stream()
            .map(m -> new ScoredMessage(m, scorer.score(m, currentQuery)))
            .sorted(Comparator.comparingDouble(s -> -s.score))
            .limit(Math.max(4, candidates.size() / 2))
            .sorted(Comparator.comparingInt(s -> history.indexOf(s.message))) // restore order
            .toList();

        List<Message> compacted = new ArrayList<>();
        if (system != null) compacted.add(system);
        compacted.addAll(scored.stream().map(s -> s.message).toList());

        return new CompactionResult(List.copyOf(compacted),
            "selective retention: kept " + scored.size() + "/" + candidates.size());
    }

    public record CompactionResult(@NonNull List<Message> messages, @NonNull String strategy) {}

    public interface Summarizer {
        @NonNull String summarize(@NonNull List<Message> messages);
    }

    public interface RelevanceScorer {
        double score(@NonNull Message message, @NonNull String query);
    }

    private record ScoredMessage(@NonNull Message message, double score) {}
}
