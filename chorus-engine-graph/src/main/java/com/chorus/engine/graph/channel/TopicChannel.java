package com.chorus.engine.graph.channel;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only list channel.
 *
 * <p>Merges updates by appending all elements from the update list to
 * the current list. Useful for accumulating messages, events, or logs.
 *
 * @param <T> the element type
 */
public final class TopicChannel<T> implements Channel<List<T>> {

    @Override
    public @NonNull List<T> merge(@Nullable List<T> current, @Nullable List<T> update) {
        List<T> result = new ArrayList<>();
        if (current != null) {
            result.addAll(current);
        }
        if (update != null) {
            result.addAll(update);
        }
        return List.copyOf(result);
    }
}
