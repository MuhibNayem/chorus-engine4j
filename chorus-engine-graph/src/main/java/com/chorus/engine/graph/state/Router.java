package com.chorus.engine.graph.state;

import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

/**
 * Functional interface for conditional edge routing.
 *
 * <p>The router inspects the current state and returns a destination key.
 * The key is looked up in the {@code destinations} map provided when the
 * conditional edge was registered.
 *
 * @param <S> the state type
 */
@FunctionalInterface
public interface Router<S> {

    /**
     * Determine the next node based on state.
     *
     * @param state the current full state
     * @param token cancellation token
     * @return a destination key present in the edge's destination map
     */
    @NonNull String route(@NonNull S state, @NonNull CancellationToken token);
}
