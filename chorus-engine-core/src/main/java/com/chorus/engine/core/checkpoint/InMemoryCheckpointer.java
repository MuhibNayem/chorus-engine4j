package com.chorus.engine.core.checkpoint;

import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * In-memory checkpointer for testing and single-node deployments.
 * Not suitable for distributed or long-running production use.
 */
public final class InMemoryCheckpointer implements Checkpointer {

    private record SavedEntry(AgentState state, long savedAtMillis) {}

    // runId -> (sequenceNumber -> entry)
    private final Map<String, ConcurrentSkipListMap<Long, SavedEntry>> storage = new ConcurrentHashMap<>();

    @Override
    public @NonNull Result<Void, CheckpointError> save(@NonNull String runId, long sequenceNumber, @NonNull AgentState state) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(state, "state");
        storage.computeIfAbsent(runId, k -> new ConcurrentSkipListMap<>())
               .put(sequenceNumber, new SavedEntry(state, System.currentTimeMillis()));
        return new Result.Ok<>(null);
    }

    @Override
    public @NonNull Result<AgentState, CheckpointError> loadLatest(@NonNull String runId) {
        ConcurrentSkipListMap<Long, SavedEntry> map = storage.get(runId);
        if (map == null || map.isEmpty()) {
            return Result.err(CheckpointError.of("NOT_FOUND", "No checkpoints for run: " + runId));
        }
        Map.Entry<Long, SavedEntry> last = map.lastEntry();
        return Result.ok(last.getValue().state());
    }

    @Override
    public @NonNull Result<AgentState, CheckpointError> load(@NonNull String runId, long sequenceNumber) {
        ConcurrentSkipListMap<Long, SavedEntry> map = storage.get(runId);
        if (map == null) {
            return Result.err(CheckpointError.of("NOT_FOUND", "No checkpoints for run: " + runId));
        }
        SavedEntry entry = map.get(sequenceNumber);
        if (entry == null) {
            return Result.err(CheckpointError.of("NOT_FOUND", "No checkpoint at sequence " + sequenceNumber));
        }
        return Result.ok(entry.state());
    }

    @Override
    public @NonNull Result<List<CheckpointRef>, CheckpointError> list(@NonNull String runId) {
        ConcurrentSkipListMap<Long, SavedEntry> map = storage.get(runId);
        if (map == null) return Result.ok(List.of());

        List<CheckpointRef> refs = new ArrayList<>();
        for (Map.Entry<Long, SavedEntry> e : map.descendingMap().entrySet()) {
            refs.add(new CheckpointRef(runId, e.getKey(), e.getValue().savedAtMillis()));
        }
        return Result.ok(refs);
    }

    @Override
    public @NonNull Result<Void, CheckpointError> prune(@NonNull String runId, long keepAfterSequence) {
        ConcurrentSkipListMap<Long, SavedEntry> map = storage.get(runId);
        if (map != null) {
            map.headMap(keepAfterSequence, false).clear();
        }
        return new Result.Ok<>(null);
    }

    public void clear() {
        storage.clear();
    }
}
