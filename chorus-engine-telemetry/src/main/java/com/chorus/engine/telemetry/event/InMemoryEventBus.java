package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory event bus using copy-on-write subscribers.
 * Suitable for testing and single-node deployments.
 */
public final class InMemoryEventBus implements EventBus {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<ChorusEvent>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(@NonNull ChorusEvent event) {
        // Type-specific subscribers
        List<Consumer<ChorusEvent>> typeHandlers = subscribers.get(event.eventType());
        if (typeHandlers != null) {
            for (Consumer<ChorusEvent> handler : typeHandlers) {
                dispatch(handler, event);
            }
        }

        // Wildcard subscribers
        List<Consumer<ChorusEvent>> wildcardHandlers = subscribers.get("*");
        if (wildcardHandlers != null) {
            for (Consumer<ChorusEvent> handler : wildcardHandlers) {
                dispatch(handler, event);
            }
        }
    }

    @Override
    public void subscribe(@NonNull String eventType, @NonNull Consumer<ChorusEvent> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    private void dispatch(@NonNull Consumer<ChorusEvent> handler, @NonNull ChorusEvent event) {
        try {
            handler.accept(event);
        } catch (Exception e) {
            // Defensive: subscriber exceptions must not break the bus
            // In a production setup this would be logged
        }
    }
}
