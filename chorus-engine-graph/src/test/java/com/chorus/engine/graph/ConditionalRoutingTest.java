package com.chorus.engine.graph;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ConditionalRoutingTest {

    private static StateGraph<Map<String, Object>> mapGraph() {
        return new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });
    }

    @Test
    void branchBasedOnState_highPath() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("start", (state, token) -> Map.of("value", 10))
             .addNode("high", (state, token) -> Map.of("branch", "high"))
             .addNode("low", (state, token) -> Map.of("branch", "low"))
             .addConditionalEdge("start",
                 (state, token) -> ((Number) state.get("value")).intValue() > 5 ? "high" : "low",
                 Map.of("high", "high", "low", "low"))
             .addEdge("high", "__end__")
             .addEdge("low", "__end__")
             .setEntryPoint("start");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-cond-high", CancellationToken.create());

        assertThat(result).containsEntry("branch", "high");
    }

    @Test
    void branchBasedOnState_lowPath() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("start", (state, token) -> Map.of("value", 2))
             .addNode("high", (state, token) -> Map.of("branch", "high"))
             .addNode("low", (state, token) -> Map.of("branch", "low"))
             .addConditionalEdge("start",
                 (state, token) -> ((Number) state.get("value")).intValue() > 5 ? "high" : "low",
                 Map.of("high", "high", "low", "low"))
             .addEdge("high", "__end__")
             .addEdge("low", "__end__")
             .setEntryPoint("start");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-cond-low", CancellationToken.create());

        assertThat(result).containsEntry("branch", "low");
    }

    @Test
    void unknownRouterDestinationThrows() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("start", (state, token) -> Map.of())
             .addConditionalEdge("start",
                 (state, token) -> "unknown",
                 Map.of("known", "end"))
             .setEntryPoint("start");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();

        assertThatThrownBy(() -> compiled.invoke(Map.of(), "test-cond-unknown", CancellationToken.create()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("unknown destination");
    }

    @Test
    void conditionalAndUnconditionalEdgesOnSameNodeThrowsOnCompile() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of())
             .addNode("B", (state, token) -> Map.of())
             .addEdge("A", "B")
             .addConditionalEdge("A", (state, token) -> "B", Map.of("B", "B"))
             .setEntryPoint("A");

        assertThatThrownBy(graph::compile)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("both unconditional and conditional");
    }
}
