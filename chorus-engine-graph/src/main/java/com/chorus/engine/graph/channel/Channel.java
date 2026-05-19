package com.chorus.engine.graph.channel;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Channel abstraction defining how a single field of state is updated.
 *
 * <p>Channels are the low-level primitive used by {@link com.chorus.engine.graph.state.StateUpdater}
 * to resolve conflicts when parallel nodes write to the same state key.
 *
 * @param <T> the field type
 */
public interface Channel<T> {

    /**
     * Merge an update into the current value.
     *
     * @param current the existing value (may be null)
     * @param update  the new value (may be null)
     * @return the merged value
     */
    @NonNull T merge(@Nullable T current, @Nullable T update);
}
