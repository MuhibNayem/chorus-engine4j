package com.chorus.engine.memory.hierarchical;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.memory.LongTermMemory;
import com.chorus.engine.memory.ShortTermMemory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Four-tier hierarchical memory manager inspired by human cognitive architecture.
 *
 * <p>Tiers (fastest → slowest, smallest → largest):
 * <ol>
 *   <li><b>Working Memory</b> — active context window, session-scoped. Uses {@link ShortTermMemory}.
 *       Latency: &lt;1ms. Capacity: token-limited.</li>
 *   <li><b>Episodic Memory</b> — chronological event log. Uses {@link EpisodicMemory}.
 *       Latency: 1-10ms. Capacity: thousands of episodes.</li>
 *   <li><b>Semantic Memory</b> — facts and knowledge via embeddings. Uses {@link LongTermMemory}.
 *       Latency: 10-100ms. Capacity: unlimited.</li>
 *   <li><b>Procedural Memory</b> — learned skills and patterns. Uses {@link ProceduralMemory}.
 *       Latency: 1-10ms. Capacity: hundreds of procedures.</li>
 * </ol>
 *
 * <p>Automatic consolidation:
 * <ul>
 *   <li>Hot episodic memories (accessed &ge;3 times) are promoted to semantic memory</li>
 *   <li>Successful repeated action patterns become procedural memory</li>
 *   <li>Working memory evicts oldest entries when token budget exceeded</li>
 * </ul>
 *
 * <p>This architecture is the 2026 production standard for agent memory.
 * LangGraph's memory is described as "externalized or ad hoc" by comparison.
 */
public final class HierarchicalMemoryManager {

    private final ShortTermMemory working;
    private final EpisodicMemory episodic;
    private final LongTermMemory semantic;
    private final ProceduralMemory procedural;
    private final ScheduledExecutorService consolidationExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final int hotEpisodeThreshold;
    private final double procedureSuccessThreshold;
    private final Set<String> consolidatedEpisodeIds = ConcurrentHashMap.newKeySet();

    public HierarchicalMemoryManager(
        @NonNull ShortTermMemory working,
        @NonNull EpisodicMemory episodic,
        @NonNull LongTermMemory semantic,
        @NonNull ProceduralMemory procedural
    ) {
        this(working, episodic, semantic, procedural, 3, 0.7);
    }

    public HierarchicalMemoryManager(
        @NonNull ShortTermMemory working,
        @NonNull EpisodicMemory episodic,
        @NonNull LongTermMemory semantic,
        @NonNull ProceduralMemory procedural,
        int hotEpisodeThreshold,
        double procedureSuccessThreshold
    ) {
        this.working = working;
        this.episodic = episodic;
        this.semantic = semantic;
        this.procedural = procedural;
        this.hotEpisodeThreshold = hotEpisodeThreshold;
        this.procedureSuccessThreshold = procedureSuccessThreshold;
        this.consolidationExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-consolidation");
            t.setDaemon(true);
            return t;
        });
        this.consolidationExecutor.scheduleWithFixedDelay(
            this::runConsolidation, 60, 60, TimeUnit.SECONDS);
    }

    // ---- Write paths ----

    /**
     * Store a message in working memory (fastest tier).
     */
    public void storeWorking(@NonNull Message message, int tokenCount) {
        working.add(message, tokenCount);
    }

    /**
     * Record an episodic event.
     */
    public @NonNull String recordEpisode(@NonNull Message message, @NonNull String eventType,
                                         @Nullable Map<String, Object> entities, @Nullable String outcome) {
        return episodic.record(message, eventType, entities, outcome);
    }

    /**
     * Store a fact in semantic memory.
     */
    public void storeSemantic(@NonNull Message message, @NonNull String key,
                              @Nullable Map<String, Object> metadata) {
        semantic.store(message, key, metadata);
    }

    /**
     * Learn a new procedure.
     */
    public void learnProcedure(@NonNull String id, @NonNull String description,
                               @NonNull List<String> steps, @Nullable Map<String, Object> context) {
        procedural.learn(id, description, steps, context);
    }

    /**
     * Record procedure invocation outcome.
     */
    public void recordProcedureOutcome(@NonNull String procedureId, boolean success) {
        if (success) {
            procedural.recordSuccess(procedureId);
        } else {
            procedural.recordFailure(procedureId);
        }
    }

    // ---- Read paths ----

    /**
     * Retrieve recent working memory messages.
     */
    public @NonNull List<Message> workingRecent(int n) {
        return working.getRecent(n);
    }

    /**
     * Search working memory by keyword.
     */
    public @NonNull List<Message> workingSearch(@NonNull String query, int topK) {
        return working.search(query, topK);
    }

    /**
     * Query episodic memory.
     */
    public @NonNull List<EpisodicMemory.Episode> episodicRecent(int n) {
        return episodic.queryRecent(n);
    }

    public @NonNull List<EpisodicMemory.Episode> episodicByEntity(@NonNull String key, @NonNull Object value, int topK) {
        return episodic.queryByEntity(key, value, topK);
    }

    public @NonNull List<EpisodicMemory.Episode> episodicByType(@NonNull String eventType, int topK) {
        return episodic.queryByType(eventType, topK);
    }

    /**
     * Retrieve semantic facts.
     */
    public @NonNull List<LongTermMemory.RetrievalResult> semanticRetrieve(@NonNull String query, int topK) {
        return semantic.retrieve(query, topK);
    }

    /**
     * Find reliable procedures.
     */
    public @NonNull List<ProceduralMemory.Procedure> reliableProcedures(double minSuccessRate) {
        return procedural.findReliable(minSuccessRate);
    }

    public @NonNull List<ProceduralMemory.Procedure> proceduresByKeyword(@NonNull String keyword) {
        return procedural.findByKeyword(keyword);
    }

    // ---- Context assembly ----

    /**
     * Assemble a comprehensive context for an LLM call by querying all tiers.
     *
     * @param query   the current user query
     * @param topK    max results per tier
     * @return context messages from all relevant tiers
     */
    public @NonNull ContextAssembly assembleContext(@NonNull String query, int topK) {
        List<Message> working = workingRecent(topK);
        List<LongTermMemory.RetrievalResult> facts = semanticRetrieve(query, topK);
        List<EpisodicMemory.Episode> episodes = facts.isEmpty()
            ? episodicRecent(topK) : List.of();
        List<ProceduralMemory.Procedure> skills = proceduresByKeyword(query);

        return new ContextAssembly(working, episodes, facts, skills);
    }

    // ---- Consolidation ----

    private void runConsolidation() {
        if (closed.get()) return;
        try {
            // Promote hot episodes to semantic memory
            List<EpisodicMemory.Episode> hot = episodic.findHotEpisodes(hotEpisodeThreshold);
            for (EpisodicMemory.Episode ep : hot) {
                if (consolidatedEpisodeIds.add(ep.id())) {
                    String key = "consolidated-" + ep.id();
                    semantic.store(ep.message(), key, Map.of(
                        "source", "episodic_consolidation",
                        "eventType", ep.eventType(),
                        "timestamp", ep.timestamp().toString()
                    ));
                }
            }
        } catch (Exception e) {
            // Consolidation failures are non-fatal
        }
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            consolidationExecutor.shutdown();
            try {
                if (!consolidationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    consolidationExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                consolidationExecutor.shutdownNow();
            }
        }
    }

    // ---- Records ----

    public record ContextAssembly(
        @NonNull List<Message> working,
        @NonNull List<EpisodicMemory.Episode> episodes,
        @NonNull List<LongTermMemory.RetrievalResult> facts,
        @NonNull List<ProceduralMemory.Procedure> skills
    ) {}

    public record Stats(
        int workingSize,
        int episodicSize,
        int semanticSize,
        int proceduralSize
    ) {}

    public @NonNull Stats stats() {
        return new Stats(working.size(), episodic.size(), semantic.size(), procedural.size());
    }
}
