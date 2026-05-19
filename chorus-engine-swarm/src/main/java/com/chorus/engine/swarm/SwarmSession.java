package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A running multi-agent session.
 *
 * <p>Thread-safe: multiple readers are safe; mutations are synchronized
 * so that a single session can be observed from multiple threads.
 */
public final class SwarmSession {

    private final String sessionId;
    private final CopyOnWriteArrayList<Message> messages;
    private final ConcurrentHashMap<String, Object> contextVariables;
    private final AtomicReference<String> activeAgent;
    private final AtomicInteger turnCount;
    private final Instant startedAt;

    public SwarmSession(
        @NonNull String sessionId,
        @NonNull List<Message> messages,
        @NonNull Map<String, Object> contextVariables,
        @NonNull String activeAgent
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
        this.messages = new CopyOnWriteArrayList<>(messages);
        this.contextVariables = new ConcurrentHashMap<>(contextVariables);
        this.activeAgent = new AtomicReference<>(Objects.requireNonNull(activeAgent, "activeAgent cannot be null"));
        this.turnCount = new AtomicInteger(0);
        this.startedAt = Instant.now();
    }

    public @NonNull String sessionId() {
        return sessionId;
    }

    public @NonNull List<Message> messages() {
        return List.copyOf(messages);
    }

    public @NonNull Map<String, Object> contextVariables() {
        return Map.copyOf(contextVariables);
    }

    public @NonNull String activeAgent() {
        return activeAgent.get();
    }

    public int turnCount() {
        return turnCount.get();
    }

    public @NonNull Instant startedAt() {
        return startedAt;
    }

    public void addMessage(@NonNull Message message) {
        messages.add(Objects.requireNonNull(message, "message cannot be null"));
    }

    public void addMessages(@NonNull List<Message> newMessages) {
        messages.addAll(Objects.requireNonNull(newMessages, "newMessages cannot be null"));
    }

    public void setContextVariable(@NonNull String key, @Nullable Object value) {
        if (value == null) {
            contextVariables.remove(key);
        } else {
            contextVariables.put(key, value);
        }
    }

    public @Nullable Object getContextVariable(@NonNull String key) {
        return contextVariables.get(key);
    }

    public void setActiveAgent(@NonNull String agentName) {
        activeAgent.set(Objects.requireNonNull(agentName, "agentName cannot be null"));
    }

    public void incrementTurnCount() {
        turnCount.incrementAndGet();
    }

    public void addTurns(int turns) {
        turnCount.addAndGet(turns);
    }
}
