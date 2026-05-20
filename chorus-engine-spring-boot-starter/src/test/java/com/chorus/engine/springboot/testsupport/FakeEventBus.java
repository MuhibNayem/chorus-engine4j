package com.chorus.engine.springboot.testsupport;

import com.chorus.engine.telemetry.event.ChorusEvent;
import com.chorus.engine.telemetry.event.EventBus;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Hand-written fake event bus that records all subscriptions and published events.
 */
public final class FakeEventBus implements EventBus {

    private final Map<String, List<Consumer<ChorusEvent>>> subscriptions = new ConcurrentHashMap<>();
    private final List<ChorusEvent> publishedEvents = new ArrayList<>();

    @Override
    public void publish(@NonNull ChorusEvent event) {
        publishedEvents.add(event);
        List<Consumer<ChorusEvent>> handlers = subscriptions.get(event.eventType());
        if (handlers != null) {
            handlers.forEach(h -> h.accept(event));
        }
        List<Consumer<ChorusEvent>> wildcard = subscriptions.get("*");
        if (wildcard != null) {
            wildcard.forEach(h -> h.accept(event));
        }
    }

    @Override
    public void subscribe(@NonNull String eventType, @NonNull Consumer<ChorusEvent> handler) {
        subscriptions.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
    }

    public @NonNull List<Consumer<ChorusEvent>> getHandlers(@NonNull String eventType) {
        return List.copyOf(subscriptions.getOrDefault(eventType, List.of()));
    }

    public boolean hasSubscription(@NonNull String eventType) {
        return subscriptions.containsKey(eventType) && !subscriptions.get(eventType).isEmpty();
    }

    public @NonNull List<ChorusEvent> getPublishedEvents() {
        return List.copyOf(publishedEvents);
    }

    public void clear() {
        subscriptions.clear();
        publishedEvents.clear();
    }
}
