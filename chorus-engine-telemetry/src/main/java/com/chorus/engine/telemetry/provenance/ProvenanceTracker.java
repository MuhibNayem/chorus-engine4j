package com.chorus.engine.telemetry.provenance;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Full provenance tracker for every decision, tool call, and state change.
 *
 * <p>Records a complete audit trail forming a directed acyclic graph (DAG)
 * of causality. Each provenance entry has:
 * <ul>
 *   <li>Unique decision ID</li>
 *   <li>Timestamp and agent/node that made the decision</li>
 *   <li>Input state snapshot</li>
 *   <li>Reasoning/rationale</li>
 *   <li>Output/action taken</li>
 *   <li>Parent decision IDs (forming the causal chain)</li>
 * </ul>
 *
 * <p>This enables:
 * <ul>
 *   <li>Post-hoc explainability: "Why did the agent do X?"</li>
 *   <li>Compliance auditing: full trace for regulated industries</li>
 *   <li>Debugging: replay exact decision sequences</li>
 *   <li>Trust: users can inspect the full reasoning chain</li>
 * </ul>
 *
 * <p>No major framework (LangGraph, CrewAI, AutoGen) provides this level
 * of decision provenance out of the box in 2026.
 */
public final class ProvenanceTracker {

    private final List<ProvenanceEntry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, ProvenanceEntry> byId = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final int maxEntries;

    public ProvenanceTracker() {
        this(100_000);
    }

    public ProvenanceTracker(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Record a new provenance entry.
     *
     * @param runId       the execution run identifier
     * @param agentId     the agent or node that made the decision
     * @param decisionType classification of the decision (e.g., "tool_call", "router_choice")
     * @param inputState  snapshot of state before the decision
     * @param reasoning   the rationale or prompt that led to the decision
     * @param output      the resulting action or output
     * @param parents     parent decision IDs in the causal chain
     * @return the unique decision ID
     */
    public @NonNull String record(
        @NonNull String runId,
        @NonNull String agentId,
        @NonNull String decisionType,
        @NonNull Map<String, Object> inputState,
        @Nullable String reasoning,
        @Nullable Object output,
        @NonNull List<String> parents
    ) {
        String id = runId + "-" + sequence.incrementAndGet();
        ProvenanceEntry entry = new ProvenanceEntry(
            id, runId, agentId, decisionType,
            Map.copyOf(inputState), reasoning, output,
            List.copyOf(parents), Instant.now()
        );
        entries.add(entry);
        byId.put(id, entry);
        evictIfNeeded();
        return id;
    }

    /**
     * Record a simple decision with no parents.
     */
    public @NonNull String record(
        @NonNull String runId,
        @NonNull String agentId,
        @NonNull String decisionType,
        @NonNull Map<String, Object> inputState,
        @Nullable String reasoning,
        @Nullable Object output
    ) {
        return record(runId, agentId, decisionType, inputState, reasoning, output, List.of());
    }

    public @Nullable ProvenanceEntry get(@NonNull String decisionId) {
        return byId.get(decisionId);
    }

    public @NonNull List<ProvenanceEntry> getChain(@NonNull String decisionId) {
        List<ProvenanceEntry> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(decisionId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            ProvenanceEntry entry = byId.get(current);
            if (entry != null) {
                chain.add(entry);
                queue.addAll(entry.parents());
            }
        }

        // Sort by timestamp ascending for causal order
        chain.sort(Comparator.comparing(ProvenanceEntry::timestamp));
        return chain;
    }

    public @NonNull List<ProvenanceEntry> queryByRun(@NonNull String runId) {
        return entries.stream()
            .filter(e -> e.runId().equals(runId))
            .sorted(Comparator.comparing(ProvenanceEntry::timestamp))
            .toList();
    }

    public @NonNull List<ProvenanceEntry> queryByAgent(@NonNull String agentId) {
        return entries.stream()
            .filter(e -> e.agentId().equals(agentId))
            .sorted(Comparator.comparing(ProvenanceEntry::timestamp))
            .toList();
    }

    public @NonNull List<ProvenanceEntry> queryByType(@NonNull String decisionType) {
        return entries.stream()
            .filter(e -> e.decisionType().equals(decisionType))
            .sorted(Comparator.comparing(ProvenanceEntry::timestamp))
            .toList();
    }

    /**
     * Export the full provenance chain for a run as a human-readable explanation.
     */
    public @NonNull String explain(@NonNull String runId) {
        List<ProvenanceEntry> runEntries = queryByRun(runId);
        if (runEntries.isEmpty()) return "No provenance entries for run: " + runId;

        StringBuilder sb = new StringBuilder();
        sb.append("=== Provenance Report for Run: ").append(runId).append(" ===\n\n");
        for (ProvenanceEntry e : runEntries) {
            sb.append("[").append(e.timestamp()).append("] ")
              .append(e.agentId()).append(" → ").append(e.decisionType()).append("\n");
            if (e.reasoning() != null) {
                sb.append("  Reasoning: ").append(e.reasoning()).append("\n");
            }
            if (e.output() != null) {
                sb.append("  Output: ").append(e.output()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public int size() { return entries.size(); }

    public void clear() {
        entries.clear();
        byId.clear();
    }

    private void evictIfNeeded() {
        while (entries.size() > maxEntries && !entries.isEmpty()) {
            ProvenanceEntry oldest = entries.get(0);
            entries.remove(0);
            byId.remove(oldest.id());
        }
    }

    public record ProvenanceEntry(
        @NonNull String id,
        @NonNull String runId,
        @NonNull String agentId,
        @NonNull String decisionType,
        @NonNull Map<String, Object> inputState,
        @Nullable String reasoning,
        @Nullable Object output,
        @NonNull List<String> parents,
        @NonNull Instant timestamp
    ) {}
}
