package com.chorus.engine.graph.debug;

import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.graph.CompiledGraph;

import java.util.List;
import java.util.Objects;

/**
 * Time-travel debugger for compiled graphs. Browse, fork, and replay checkpoints.
 */
public class CheckpointBrowser<S> {

    private final CompiledGraph<S> graph;
    private final Checkpointer checkpointer;

    public CheckpointBrowser(CompiledGraph<S> graph, Checkpointer checkpointer) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.checkpointer = Objects.requireNonNull(checkpointer, "checkpointer");
    }

    /**
     * List all checkpoints for a thread.
     */
    public List<CheckpointEntry> listCheckpoints(String threadId) {
        List<Checkpoint> checkpoints = checkpointer.list(threadId).join();
        return checkpoints.stream()
            .map(cp -> new CheckpointEntry(
                cp.round(),
                cp.createdAt(),
                fingerprint(cp),
                cp.threadId()
            ))
            .toList();
    }

    /**
     * Fork a thread from a specific checkpoint round.
     */
    public String forkAt(String threadId, int round, String newThreadId) {
        checkpointer.fork(threadId, round, newThreadId).join();
        return newThreadId;
    }

    /**
     * Replay from a checkpoint with a modified state.
     */
    public S replayFrom(String threadId, int round, S modifiedState) {
        Checkpoint checkpoint = checkpointer.loadAt(threadId, round).join();
        if (checkpoint == null) {
            throw new IllegalArgumentException(
                "Checkpoint not found for thread " + threadId + " at round " + round);
        }
        return graph.invoke(modifiedState, threadId, null);
    }

    private String fingerprint(Checkpoint cp) {
        if (cp.messages() == null) {
            return Integer.toHexString(Objects.hash(cp.threadId(), cp.round(), cp.createdAt()));
        }
        return Integer.toHexString(Objects.hash(cp.threadId(), cp.round(), cp.createdAt(), cp.messages()));
    }
}
