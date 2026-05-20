package com.chorus.engine.llm.embed;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of embedding clients. User registers whichever clients they want.
 *
 * <p>No hardcoded providers. No defaults forced on the user.
 * The framework asks the registry for a client by model name;
 * the user decides which model maps to which client.
 */
public final class EmbeddingRegistry {

    private final Map<String, EmbeddingClient> clients = new ConcurrentHashMap<>();

    public void register(@NonNull String modelName, @NonNull EmbeddingClient client) {
        clients.put(modelName, client);
    }

    public @Nullable EmbeddingClient get(@NonNull String modelName) {
        return clients.get(modelName);
    }

    public @NonNull EmbeddingClient getOrThrow(@NonNull String modelName) {
        EmbeddingClient c = clients.get(modelName);
        if (c == null) {
            throw new IllegalArgumentException("No embedding client registered for model: " + modelName +
                ". Registered: " + clients.keySet());
        }
        return c;
    }

    public boolean has(@NonNull String modelName) {
        return clients.containsKey(modelName);
    }

    public void remove(@NonNull String modelName) {
        EmbeddingClient removed = clients.remove(modelName);
        if (removed instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    public @NonNull Map<String, EmbeddingClient> all() {
        return Map.copyOf(clients);
    }
}
