package com.chorus.engine.graph;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class StateGraphTest {

    private static StateGraph<Map<String, Object>> mapGraph() {
        return new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });
    }

    @Test
    void simpleLinearWorkflow() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of("A", true))
             .addNode("B", (state, token) -> Map.of("B", true, "A_seen", state.get("A")))
             .addEdge("A", "B")
             .setEntryPoint("A")
             .setFinishPoint("B");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-linear", CancellationToken.create());

        assertThat(result)
            .containsEntry("A", true)
            .containsEntry("B", true)
            .containsEntry("A_seen", true);
    }

    @Test
    void graphWithoutFinishPointEndsWhenNoEdgesRemain() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of("A", true))
             .addNode("B", (state, token) -> Map.of("B", true))
             .addEdge("A", "B")
             .setEntryPoint("A");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-no-finish", CancellationToken.create());

        assertThat(result)
            .containsEntry("A", true)
            .containsEntry("B", true);
    }

    @Test
    void endSentinelTerminatesImmediately() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of("A", true))
             .addEdge("A", "__end__")
             .setEntryPoint("A");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-end", CancellationToken.create());

        assertThat(result).containsEntry("A", true);
    }

    @Test
    void missingEntryPointThrowsOnCompile() {
        StateGraph<Map<String, Object>> graph = mapGraph();
        graph.addNode("A", (state, token) -> Map.of());

        assertThatThrownBy(graph::compile)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Entry point not set");
    }

    @Test
    void duplicateNodeThrows() {
        StateGraph<Map<String, Object>> graph = mapGraph();
        graph.addNode("A", (state, token) -> Map.of());

        assertThatThrownBy(() -> graph.addNode("A", (state, token) -> Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Node already exists");
    }
}
