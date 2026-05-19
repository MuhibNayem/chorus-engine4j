package com.chorus.engine.graph.channel;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Last-write-wins channel.
 *
 * <p>If the update is non-null it overwrites the current value;
 * otherwise the current value is retained.
 *
 * @param <T> the value type
 */
public final class LastValueChannel<T> implements Channel<T> {

    @Override
    public @NonNull T merge(@Nullable T current, @Nullable T update) {
        if (update != null) {
            return update;
        }
        if (current != null) {
            return current;
        }
        throw new IllegalStateException("Both current and update are null");
    }
}
