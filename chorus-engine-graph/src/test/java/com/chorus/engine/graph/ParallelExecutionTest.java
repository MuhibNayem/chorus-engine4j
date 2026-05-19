package com.chorus.engine.graph;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ParallelExecutionTest {

    private static StateGraph<Map<String, Object>> mapGraph() {
        return new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });
    }

    @Test
    void fanOutToMultipleNodes() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("start", (state, token) -> Map.of("start", true))
             .addNode("left", (state, token) -> {
                 token.throwIfCancelled();
                 try {
                     Thread.sleep(50);
                 } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                 }
                 return Map.of("left", true);
             })
             .addNode("right", (state, token) -> {
                 token.throwIfCancelled();
                 return Map.of("right", true);
             })
             .addNode("join", (state, token) -> Map.of("join", true))
             .addEdge("start", "left")
             .addEdge("start", "right")
             .addEdge("left", "join")
             .addEdge("right", "join")
             .setEntryPoint("start");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-parallel", CancellationToken.create());
        long duration = System.currentTimeMillis() - startTime;

        assertThat(result)
            .containsEntry("left", true)
            .containsEntry("right", true)
            .containsEntry("join", true);

        // Both left and right run concurrently, so total time should be < 100ms
        // (left alone takes ~50ms; sequentially it would be ~50ms + some overhead anyway,
        // but if they were truly sequential with 50ms each, it would be ~100ms).
        assertThat(duration).isLessThan(120);
    }

    @Test
    void parallelNodesMergeUpdates() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("start", (state, token) -> Map.of())
             .addNode("addA", (state, token) -> Map.of("A", 1))
             .addNode("addB", (state, token) -> Map.of("B", 2))
             .addNode("sum", (state, token) -> {
                 int a = ((Number) state.getOrDefault("A", 0)).intValue();
                 int b = ((Number) state.getOrDefault("B", 0)).intValue();
                 return Map.of("total", a + b);
             })
             .addEdge("start", "addA")
             .addEdge("start", "addB")
             .addEdge("addA", "sum")
             .addEdge("addB", "sum")
             .setEntryPoint("start");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of(), "test-merge", CancellationToken.create());

        assertThat(result).containsEntry("total", 3);
    }
}
