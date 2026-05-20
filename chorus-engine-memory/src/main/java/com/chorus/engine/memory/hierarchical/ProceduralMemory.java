package com.chorus.engine.memory.hierarchical;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Procedural memory: learned skills, patterns, and behavioral rules.
 *
 * <p>Stores reusable procedures with success/failure tracking.
 * When a pattern repeatedly leads to successful outcomes, it becomes
 * a procedural memory that guides future agent behavior.
 *
 * <p>This is the "how to do things" layer — analogous to human
 * muscle memory and learned habits.
 */
public final class ProceduralMemory {

    private final Map<String, Procedure> procedures = new ConcurrentHashMap<>();
    private final int maxProcedures;

    public ProceduralMemory(int maxProcedures) {
        this.maxProcedures = maxProcedures;
    }

    public synchronized void learn(@NonNull String procedureId, @NonNull String description,
                      @NonNull List<String> steps, @Nullable Map<String, Object> context) {
        Objects.requireNonNull(procedureId, "procedureId");
        Objects.requireNonNull(description, "description");
        Procedure proc = new Procedure(
            procedureId, description, List.copyOf(steps),
            context != null ? Map.copyOf(context) : Map.of(),
            Instant.now(), 0, 0, 0.0
        );
        procedures.put(procedureId, proc);
        evictIfNeeded();
    }

    public void recordSuccess(@NonNull String procedureId) {
        procedures.computeIfPresent(procedureId, (k, proc) -> {
            int newSuccess = proc.successCount() + 1;
            int newTotal = proc.invocationCount() + 1;
            return proc.withStats(newTotal, newSuccess, (double) newSuccess / newTotal);
        });
    }

    public void recordFailure(@NonNull String procedureId) {
        procedures.computeIfPresent(procedureId, (k, proc) -> {
            int newTotal = proc.invocationCount() + 1;
            return proc.withStats(newTotal, proc.successCount(), (double) proc.successCount() / newTotal);
        });
    }

    public @NonNull List<Procedure> findByKeyword(@NonNull String keyword) {
        String lower = keyword.toLowerCase(java.util.Locale.ROOT);
        return procedures.values().stream()
            .filter(p -> p.description().toLowerCase(java.util.Locale.ROOT).contains(lower)
                || p.steps().stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).contains(lower)))
            .sorted(Comparator.comparingDouble(Procedure::successRate).reversed())
            .toList();
    }

    public @NonNull List<Procedure> findReliable(double minSuccessRate) {
        return procedures.values().stream()
            .filter(p -> p.successRate() >= minSuccessRate && p.invocationCount() >= 3)
            .sorted(Comparator.comparingDouble(Procedure::successRate).reversed())
            .toList();
    }

    public @Nullable Procedure get(@NonNull String procedureId) {
        return procedures.get(procedureId);
    }

    public @NonNull List<Procedure> all() {
        return List.copyOf(procedures.values());
    }

    public void forget(@NonNull String procedureId) {
        procedures.remove(procedureId);
    }

    public int size() { return procedures.size(); }

    private synchronized void evictIfNeeded() {
        if (procedures.size() > maxProcedures) {
            procedures.values().stream()
                .min(Comparator.comparingInt(Procedure::invocationCount)
                    .thenComparingDouble(Procedure::successRate)
                    .thenComparing(Procedure::createdAt))
                .ifPresent(oldest -> procedures.remove(oldest.id()));
        }
    }

    public record Procedure(
        @NonNull String id,
        @NonNull String description,
        @NonNull List<String> steps,
        @NonNull Map<String, Object> context,
        @NonNull Instant createdAt,
        int invocationCount,
        int successCount,
        double successRate
    ) {
        Procedure withStats(int invocations, int successes, double rate) {
            return new Procedure(id, description, steps, context, createdAt, invocations, successes, rate);
        }
    }
}
