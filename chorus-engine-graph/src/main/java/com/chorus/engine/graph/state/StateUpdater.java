package com.chorus.engine.graph.state;

import org.jspecify.annotations.NonNull;

/**
 * Functional interface for merging a partial state update into the current state.
 *
 * <p>Immutable in, immutable out. The implementation must not mutate inputs.
 *
 * @param <S> the state type
 */
@FunctionalInterface
public interface StateUpdater<S> {

    /**
     * Merge {@code update} into {@code current} and return the new state.
     *
     * @param current the current full state
     * @param update  the partial update produced by a node
     * @return the merged state
     */
    @NonNull S apply(@NonNull S current, @NonNull S update);
}
