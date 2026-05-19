package com.chorus.engine.graph.state;

import org.jspecify.annotations.NonNull;

/**
 * Sealed event hierarchy for streaming graph execution.
 * All events are immutable records.
 *
 * @param <S> the state type
 */
public sealed interface GraphEvent<S> {

    record NodeStart<S>(
        @NonNull String nodeName,
        @NonNull S state
    ) implements GraphEvent<S> {}

    record NodeEnd<S>(
        @NonNull String nodeName,
        @NonNull S state,
        @NonNull S output
    ) implements GraphEvent<S> {}

    record EdgeTransition<S>(
        @NonNull String from,
        @NonNull String to
    ) implements GraphEvent<S> {}

    record CheckpointSaved<S>(
        @NonNull String threadId,
        long sequence,
        @NonNull S state
    ) implements GraphEvent<S> {}

    record GraphEnd<S>(
        @NonNull S finalState
    ) implements GraphEvent<S> {}

    record GraphError<S>(
        @NonNull String nodeName,
        @NonNull Throwable error
    ) implements GraphEvent<S> {}
}
