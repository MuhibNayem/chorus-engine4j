package com.chorus.engine.springboot.graph;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of {@link GraphDescriptor} instances.
 *
 * <p>Populated during {@code afterSingletonsInstantiated()} by
 * {@link GraphAnnotationProcessor}. Consumed by the dev-mode
 * {@link com.chorus.engine.springboot.GraphVisualizerController}
 * to render Mermaid.js diagrams.
 *
 * <p>This bean exists independently of whether graph execution is enabled
 * ({@code chorus.graph.enabled}), allowing the visualizer to reflect on
 * annotated workflow classes even when the graph engine is not active.
 */
public final class GraphDescriptorRegistry {

    private final Map<String, GraphDescriptor> descriptors = new ConcurrentHashMap<>();

    /**
     * Registers a workflow descriptor.
     *
     * <p>Idempotent: re-registering the same bean name replaces the existing descriptor.
     *
     * @param beanName   Spring bean name
     * @param descriptor topology descriptor
     */
    public void register(@NonNull String beanName, @NonNull GraphDescriptor descriptor) {
        descriptors.put(beanName, descriptor);
    }

    /**
     * Returns an unmodifiable view of all registered descriptors, keyed by bean name.
     */
    public @NonNull Map<String, GraphDescriptor> all() {
        return Collections.unmodifiableMap(descriptors);
    }

    /**
     * Returns all registered descriptors as an ordered collection, sorted by bean name.
     */
    public @NonNull Collection<GraphDescriptor> allSorted() {
        return descriptors.values().stream()
            .sorted(java.util.Comparator.comparing(GraphDescriptor::beanName))
            .toList();
    }

    /**
     * Finds a descriptor by bean name.
     *
     * @param beanName the Spring bean name
     * @return the descriptor, if registered
     */
    public @NonNull Optional<GraphDescriptor> find(@NonNull String beanName) {
        return Optional.ofNullable(descriptors.get(beanName));
    }

    /**
     * Returns the total number of registered workflow descriptors.
     */
    public int size() {
        return descriptors.size();
    }

    /**
     * Returns {@code true} if no workflows have been registered.
     */
    public boolean isEmpty() {
        return descriptors.isEmpty();
    }
}
