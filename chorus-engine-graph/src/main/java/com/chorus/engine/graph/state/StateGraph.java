package com.chorus.engine.graph.state;

import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.graph.checkpoint.GraphCheckpointer;
import com.chorus.engine.graph.pregel.PregelExecutor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * A directed stateful graph where nodes receive and update shared state of type {@code S}.
 *
 * <p>Modelled after LangGraph's Pregel engine, adapted for Java 25 structured concurrency.
 *
 * <p>Usage:
 * <pre>{@code
 * StateGraph<Map<String, Object>> graph = new StateGraph<>((current, update) -> {
 *     Map<String, Object> merged = new HashMap<>(current);
 *     merged.putAll(update);
 *     return Map.copyOf(merged);
 * });
 *
 * graph.addNode("A", (state, token) -> Map.of("A", true))
 *      .addNode("B", (state, token) -> Map.of("B", true))
 *      .addEdge("A", "B")
 *      .setEntryPoint("A")
 *      .setFinishPoint("B");
 *
 * CompiledGraph<Map<String, Object>> compiled = graph.compile();
 * Map<String, Object> result = compiled.invoke(Map.of(), "run-1", CancellationToken.create());
 * }</pre>
 *
 * @param <S> the shared state type
 */
public final class StateGraph<S> {

    private final StateUpdater<S> updater;
    private final Map<String, Node<S>> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> edges = new LinkedHashMap<>();
    private final Map<String, ConditionalEdge<S>> conditionalEdges = new LinkedHashMap<>();
    private String entryPoint;
    private String finishPoint = "__end__";

    public StateGraph(@NonNull StateUpdater<S> updater) {
        this.updater = Objects.requireNonNull(updater, "updater");
    }

    /**
     * Register a node.
     *
     * @param name the unique node name
     * @param node the node implementation
     * @return this graph for chaining
     */
    public StateGraph<S> addNode(@NonNull String name, @NonNull Node<S> node) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(node, "node");
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("Node already exists: " + name);
        }
        nodes.put(name, node);
        return this;
    }

    /**
     * Add an unconditional outgoing edge.
     * Multiple edges from the same node are supported for fan-out.
     *
     * @param from the source node name
     * @param to   the target node name (or "__end__")
     * @return this graph for chaining
     */
    public StateGraph<S> addEdge(@NonNull String from, @NonNull String to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        edges.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        return this;
    }

    /**
     * Add a conditional outgoing edge.
     *
     * @param from         the source node name
     * @param router       the routing function
     * @param destinations map from router return keys to target node names
     * @return this graph for chaining
     */
    public StateGraph<S> addConditionalEdge(
        @NonNull String from,
        @NonNull Router<S> router,
        @NonNull Map<String, String> destinations
    ) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(destinations, "destinations");
        if (conditionalEdges.containsKey(from)) {
            throw new IllegalArgumentException("Conditional edge already exists for node: " + from);
        }
        conditionalEdges.put(from, new ConditionalEdge<>(router, Map.copyOf(destinations)));
        return this;
    }

    /**
     * Set the entry-point node.
     *
     * @param name the node name
     * @return this graph for chaining
     */
    public StateGraph<S> setEntryPoint(@NonNull String name) {
        this.entryPoint = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Set the finish-point node. When this node is reached and has no outgoing
     * edges, the graph terminates. The special value {@code "__end__"} is the default.
     *
     * @param name the node name
     * @return this graph for chaining
     */
    public StateGraph<S> setFinishPoint(@NonNull String name) {
        this.finishPoint = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Compile the graph without checkpointing.
     *
     * @return the compiled, runnable graph
     */
    public CompiledGraph<S> compile() {
        validate(Set.of(), Set.of());
        PregelExecutor<S> executor = new PregelExecutor<>(
            this, GraphCheckpointer.noop(), Set.of(), Set.of());
        return new CompiledGraph<>(this, GraphCheckpointer.noop(), executor);
    }

    /**
     * Compile the graph with checkpointing.
     *
     * <p>Requires the state type {@code S} to be {@link java.io.Serializable}
     * because the default serializer uses Java serialization.
     *
     * @param checkpointer the underlying checkpointer (may be null for no persistence)
     * @return the compiled, runnable graph
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public CompiledGraph<S> compile(@Nullable Checkpointer checkpointer) {
        GraphCheckpointer<S> graphCheckpointer = checkpointer != null
            ? (GraphCheckpointer) GraphCheckpointer.of(checkpointer)
            : GraphCheckpointer.noop();
        return compile(graphCheckpointer, Set.of(), Set.of());
    }

    /**
     * Compile the graph with a custom graph checkpointer.
     *
     * @param checkpointer the graph checkpointer
     * @return the compiled, runnable graph
     */
    public CompiledGraph<S> compile(@NonNull GraphCheckpointer<S> checkpointer) {
        validate(Set.of(), Set.of());
        PregelExecutor<S> executor = new PregelExecutor<>(
            this, checkpointer, Set.of(), Set.of());
        return new CompiledGraph<>(this, checkpointer, executor);
    }

    /**
     * Compile with checkpointing and human-in-the-loop interrupts.
     *
     * @param checkpointer    the underlying checkpointer
     * @param interruptBefore pause <em>before</em> executing these nodes
     * @param interruptAfter  pause <em>after</em> executing these nodes
     * @return the compiled, runnable graph
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public CompiledGraph<S> compile(
        @Nullable Checkpointer checkpointer,
        @NonNull Set<String> interruptBefore,
        @NonNull Set<String> interruptAfter
    ) {
        GraphCheckpointer<S> graphCheckpointer = checkpointer != null
            ? (GraphCheckpointer) GraphCheckpointer.of(checkpointer)
            : GraphCheckpointer.noop();
        return compile(graphCheckpointer, interruptBefore, interruptAfter);
    }

    /**
     * Compile with full control.
     *
     * @param graphCheckpointer the graph checkpointer
     * @param interruptBefore   pause before these nodes
     * @param interruptAfter    pause after these nodes
     * @return the compiled, runnable graph
     */
    public CompiledGraph<S> compile(
        @NonNull GraphCheckpointer<S> graphCheckpointer,
        @NonNull Set<String> interruptBefore,
        @NonNull Set<String> interruptAfter
    ) {
        validate(interruptBefore, interruptAfter);
        PregelExecutor<S> executor = new PregelExecutor<>(
            this, graphCheckpointer, interruptBefore, interruptAfter);
        return new CompiledGraph<>(this, graphCheckpointer, executor);
    }

    private void validate(Set<String> interruptBefore, Set<String> interruptAfter) {
        if (entryPoint == null) {
            throw new IllegalStateException("Entry point not set. Call setEntryPoint(name).");
        }
        if (!nodes.containsKey(entryPoint) && !"__start__".equals(entryPoint)) {
            throw new IllegalStateException("Entry point node not found: " + entryPoint);
        }
        for (String from : edges.keySet()) {
            if (!nodes.containsKey(from) && !"__start__".equals(from)) {
                throw new IllegalStateException("Edge from unknown node: " + from);
            }
            if (conditionalEdges.containsKey(from)) {
                throw new IllegalStateException(
                    "Node " + from + " has both unconditional and conditional outgoing edges");
            }
        }
        for (String from : conditionalEdges.keySet()) {
            if (!nodes.containsKey(from)) {
                throw new IllegalStateException("Conditional edge from unknown node: " + from);
            }
        }
        for (String node : interruptBefore) {
            if (!nodes.containsKey(node)) {
                throw new IllegalStateException("Interrupt-before references unknown node: " + node);
            }
        }
        for (String node : interruptAfter) {
            if (!nodes.containsKey(node)) {
                throw new IllegalStateException("Interrupt-after references unknown node: " + node);
            }
        }
    }

    // ---- package-private accessors for PregelExecutor ----

    public StateUpdater<S> updater() {
        return updater;
    }

    public Map<String, Node<S>> nodes() {
        return nodes;
    }

    public Map<String, List<String>> edges() {
        return edges;
    }

    public Map<String, ConditionalEdge<S>> conditionalEdges() {
        return conditionalEdges;
    }

    public String entryPoint() {
        return entryPoint;
    }

    public String finishPoint() {
        return finishPoint;
    }

    // ---- inner records ----

    public record ConditionalEdge<S>(
        @NonNull Router<S> router,
        @NonNull Map<String, String> destinations
    ) {}
}
