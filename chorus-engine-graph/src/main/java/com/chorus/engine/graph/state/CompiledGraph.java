package com.chorus.engine.graph.state;

import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.graph.checkpoint.GraphCheckpointer;
import com.chorus.engine.graph.pregel.PregelExecutor;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.Flow;

/**
 * A compiled, runnable {@link StateGraph}.
 *
 * <p>Immutable and thread-safe. Multiple invocations (with different
 * {@code threadId}s) may run concurrently.
 *
 * @param <S> the state type
 */
public final class CompiledGraph<S> {

    private final StateGraph<S> graph;
    private final GraphCheckpointer<S> checkpointer;
    private final PregelExecutor<S> executor;

    CompiledGraph(
        @NonNull StateGraph<S> graph,
        @NonNull GraphCheckpointer<S> checkpointer,
        @NonNull PregelExecutor<S> executor
    ) {
        this.graph = graph;
        this.checkpointer = checkpointer;
        this.executor = executor;
    }

    /**
     * Execute the graph synchronously and return the final state.
     *
     * @param initialState the starting state
     * @param threadId     the execution thread / run identifier
     * @param token        cancellation token
     * @return the final state
     * @throws RuntimeException if execution fails or is cancelled
     */
    public @NonNull S invoke(@NonNull S initialState, @NonNull String threadId, @NonNull CancellationToken token) {
        return executor.invoke(initialState, threadId, token);
    }

    /**
     * Execute the graph and stream lifecycle events.
     *
     * @param initialState the starting state
     * @param threadId     the execution thread / run identifier
     * @param token        cancellation token
     * @return a publisher of graph events
     */
    public Flow.@NonNull Publisher<GraphEvent<S>> stream(
        @NonNull S initialState, @NonNull String threadId, @NonNull CancellationToken token
    ) {
        return executor.stream(initialState, threadId, token);
    }

    /**
     * Load the latest checkpointed state for a thread.
     *
     * @param threadId the execution thread / run identifier
     * @return the state or an error description
     */
    public @NonNull Result<S, String> getState(@NonNull String threadId) {
        return checkpointer.loadLatest(threadId)
            .map(GraphCheckpointer.Checkpoint::state)
            .mapErr(e -> e.code() + ": " + e.message());
    }

    /**
     * Manually update the checkpointed state for a thread.
     * Used for human-in-the-loop corrections before resuming.
     *
     * @param threadId the execution thread / run identifier
     * @param update   the partial state update
     * @return ok or an error description
     */
    public @NonNull Result<Void, String> updateState(@NonNull String threadId, @NonNull S update) {
        Result<GraphCheckpointer.Checkpoint<S>, Checkpointer.CheckpointError> loaded = checkpointer.loadLatest(threadId);
        if (loaded.isErr()) {
            return Result.err("No checkpoint found for thread: " + threadId);
        }
        GraphCheckpointer.Checkpoint<S> checkpoint = loaded.unwrap();
        S newState = graph.updater().apply(checkpoint.state(), update);
        Result<Void, Checkpointer.CheckpointError> saveResult = checkpointer.save(
            threadId, checkpoint.sequence() + 1, newState, checkpoint.nextNodes());
        return saveResult.mapErr(e -> e.code() + ": " + e.message());
    }

    /**
     * Resume a paused graph from its latest checkpoint.
     *
     * @param threadId the execution thread / run identifier
     * @return the final or re-interrupted state
     * @throws IllegalStateException if no checkpoint exists or the graph is already complete
     */
    public @NonNull S resume(@NonNull String threadId) {
        return resume(threadId, CancellationToken.create());
    }

    /**
     * Resume a paused graph from its latest checkpoint with cancellation support.
     *
     * @param threadId the execution thread / run identifier
     * @param token    cancellation token
     * @return the final or re-interrupted state
     * @throws IllegalStateException if no checkpoint exists or the graph is already complete
     */
    public @NonNull S resume(@NonNull String threadId, @NonNull CancellationToken token) {
        return executor.resume(threadId, token);
    }

    /**
     * Resume a paused graph and stream events.
     *
     * @param threadId the execution thread / run identifier
     * @param token    cancellation token
     * @return a publisher of graph events
     */
    public Flow.@NonNull Publisher<GraphEvent<S>> streamResume(
        @NonNull String threadId, @NonNull CancellationToken token
    ) {
        return executor.streamResume(threadId, token);
    }
}
