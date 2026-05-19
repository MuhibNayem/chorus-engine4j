package com.chorus.engine.graph;

import com.chorus.engine.graph.channel.Channel;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Typed-state graph builder with reducer semantics.
 * Compatible with LangGraph patterns but built from scratch for Spring AI.
 */
public class StateGraph<S> {

    public static final String START = "__start__";
    public static final String END = "__end__";

    private final Map<String, Channel<?>> channels;
    private final Map<String, NodeFn<S>> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<ConditionalEdge> conditionalEdges = new ArrayList<>();
    private String entryPoint = START;

    @FunctionalInterface
    public interface NodeFn<S> {
        S apply(S state) throws GraphInterrupt;
    }

    public StateGraph(Map<String, Channel<?>> channels) {
        this.channels = new LinkedHashMap<>(channels);
    }

    public StateGraph<S> addNode(String name, NodeFn<S> fn) {
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("Node already exists: " + name);
        }
        if (START.equals(name) || END.equals(name)) {
            throw new IllegalArgumentException("Reserved node name: " + name);
        }
        nodes.put(name, fn);
        return this;
    }

    public StateGraph<S> addEdge(String from, String to) {
        edges.add(new Edge(from, to));
        return this;
    }

    public StateGraph<S> addConditionalEdge(String from, Function<S, String> condition) {
        conditionalEdges.add(new ConditionalEdge(from, condition));
        return this;
    }

    public StateGraph<S> setEntryPoint(String node) {
        this.entryPoint = node;
        return this;
    }

    public CompiledGraph<S> compile(CompileOptions options) {
        validate();
        return new CompiledGraph<>(this, options);
    }

    public CompiledGraph<S> compile() {
        return compile(new CompileOptions(25, 0, null));
    }

    private void validate() {
        // Check all static edge targets exist
        for (Edge e : edges) {
            if (!END.equals(e.to) && !nodes.containsKey(e.to)) {
                throw new IllegalStateException("Edge target does not exist: " + e.to);
            }
        }
        // Check all conditional edge sources exist
        for (ConditionalEdge ce : conditionalEdges) {
            if (!START.equals(ce.from) && !nodes.containsKey(ce.from)) {
                throw new IllegalStateException("Conditional edge source does not exist: " + ce.from);
            }
        }
        // Check entry point exists
        if (!START.equals(entryPoint) && !nodes.containsKey(entryPoint)) {
            throw new IllegalStateException("Entry point does not exist: " + entryPoint);
        }
        // Cycle detection via DFS
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        if (START.equals(entryPoint)) {
            for (Edge e : edges) {
                if (e.from.equals(START)) {
                    dfs(e.to, visiting, visited);
                }
            }
        } else {
            dfs(entryPoint, visiting, visited);
        }
    }

    private void dfs(String node, Set<String> visiting, Set<String> visited) {
        if (END.equals(node) || !nodes.containsKey(node)) return;
        if (visiting.contains(node)) {
            throw new IllegalStateException("Cycle detected in graph at node: " + node);
        }
        if (visited.contains(node)) return;
        visiting.add(node);
        for (Edge e : edges) {
            if (e.from.equals(node)) {
                dfs(e.to, visiting, visited);
            }
        }
        for (ConditionalEdge ce : conditionalEdges) {
            if (ce.from.equals(node)) {
                // Conditional edges can't be statically checked for cycles
            }
        }
        visiting.remove(node);
        visited.add(node);
    }

    public Map<String, Channel<?>> channels() { return channels; }
    public Map<String, NodeFn<S>> nodes() { return nodes; }
    public List<Edge> edges() { return edges; }
    public List<ConditionalEdge> conditionalEdges() { return conditionalEdges; }
    public String entryPoint() { return entryPoint; }

    public record Edge(String from, String to) {}

    public static class ConditionalEdge {
        private final String from;
        private final Function<?, String> condition;
        public ConditionalEdge(String from, Function<?, String> condition) {
            this.from = from;
            this.condition = condition;
        }
        public String from() { return from; }
        public Function<?, String> condition() { return condition; }
    }

    public record CompileOptions(int recursionLimit, long timeoutMs,
                                  com.chorus.engine.core.checkpoint.Checkpointer checkpointer) {}
}
