package com.chorus.engine.graph;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CycleTest {

    private static StateGraph<Map<String, Object>> mapGraph() {
        return new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });
    }

    @Test
    void loopUntilConditionMet() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("increment", (state, token) -> {
            int count = ((Number) state.getOrDefault("count", 0)).intValue();
            return Map.of("count", count + 1);
        })
        .addConditionalEdge("increment",
            (state, token) -> {
                int count = ((Number) state.getOrDefault("count", 0)).intValue();
                return count >= 3 ? "done" : "again";
            },
            Map.of("done", "__end__", "again", "increment"))
        .setEntryPoint("increment");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-cycle", CancellationToken.create());

        assertThat(result).containsEntry("count", 3);
    }

    @Test
    void maxIterationsGuardPreventsInfiniteLoop() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("forever", (state, token) -> Map.of("tick", true))
             .addEdge("forever", "forever")
             .setEntryPoint("forever");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();

        assertThatThrownBy(() -> compiled.invoke(Map.of(), "test-max-iter", CancellationToken.create()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Max iterations exceeded");
    }
}
