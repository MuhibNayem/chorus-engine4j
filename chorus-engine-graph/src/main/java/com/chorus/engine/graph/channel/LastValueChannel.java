package com.chorus.engine.graph.channel;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Last-write-wins channel.
 *
 * <p>If the update is non-null it overwrites the current value; otherwise the
 * current value is retained. If both are null an {@link IllegalArgumentException}
 * is thrown — callers must guarantee at least one side is non-null, which is
 * enforced by the graph state initialisation contract.
 *
 * @param <T> the value type
 */
public final class LastValueChannel<T> implements Channel<T> {

    @Override
    public @NonNull T merge(@Nullable T current, @Nullable T update) {
        if (update != null) return update;
        if (current != null) return current;
        throw new IllegalArgumentException(
            "LastValueChannel.merge: both current and update are null; "
            + "initialise the state field before the graph runs");
    }
}
