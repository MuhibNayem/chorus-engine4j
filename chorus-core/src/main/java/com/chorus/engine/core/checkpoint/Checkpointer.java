package com.chorus.engine.core.checkpoint;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Checkpointer {

    CompletableFuture<Void> save(String threadId, CheckpointState state);

    CompletableFuture<Checkpoint> load(String threadId);

    CompletableFuture<Checkpoint> loadAt(String threadId, int round);

    CompletableFuture<List<Checkpoint>> list(String threadId);

    CompletableFuture<Void> fork(String threadId, int round, String newThreadId);

    CompletableFuture<Void> delete(String threadId);
}
