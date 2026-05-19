package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.util.function.Consumer;

/**
 * Core event bus for publishing and subscribing to telemetry events.
 * Implementations must be thread-safe.
 */
public interface EventBus {

    /**
     * Publish an event to all subscribers.
     */
    void publish(@NonNull ChorusEvent event);

    /**
     * Subscribe to events of a specific type.
     * Use "*" to subscribe to all events.
     */
    void subscribe(@NonNull String eventType, @NonNull Consumer<ChorusEvent> handler);
}
