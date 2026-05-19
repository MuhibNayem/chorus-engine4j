package com.chorus.engine.graph.state;

import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

/**
 * Functional interface for a graph node.
 *
 * <p>A node receives the full shared state and returns a <em>partial</em> update.
 * The update is merged into the full state via the graph's {@link StateUpdater}.
 *
 * @param <S> the state type
 */
@FunctionalInterface
public interface Node<S> {

    /**
     * Execute the node logic.
     *
     * @param state the current full state
     * @param token cancellation token
     * @return a partial state update
     */
    @NonNull S invoke(@NonNull S state, @NonNull CancellationToken token);
}
