package com.chorus.engine.graph;

import com.chorus.engine.core.checkpoint.InMemoryCheckpointer;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.graph.checkpoint.GraphCheckpointer;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class CheckpointResumeTest {

    private static StateGraph<Map<String, Object>> mapGraph() {
        return new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });
    }

    @Test
    void pauseUpdateStateAndResume() {
        InMemoryCheckpointer memory = new InMemoryCheckpointer();
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of("A", true))
             .addNode("B", (state, token) -> Map.of("B", true))
             .addNode("C", (state, token) -> Map.of("C", true))
             .addEdge("A", "B")
             .addEdge("B", "C")
             .setEntryPoint("A");

        CompiledGraph<Map<String, Object>> compiled = graph.compile(
            memory, Set.of("B"), Set.of()
        );

        // Invoke — should pause before B
        Map<String, Object> interrupted = compiled.invoke(Map.of(), "test-resume", CancellationToken.create());

        assertThat(interrupted)
            .containsEntry("A", true)
            .doesNotContainKey("B");

        // Verify checkpoint exists
        Result<Map<String, Object>, String> stateResult = compiled.getState("test-resume");
        assertThat(stateResult.isOk()).isTrue();
        assertThat(stateResult.unwrap()).containsEntry("A", true);

        // Manually update state
        Result<Void, String> updateResult = compiled.updateState("test-resume", Map.of("manual", true));
        assertThat(updateResult.isOk()).isTrue();

        // Resume
        Map<String, Object> resumed = compiled.resume("test-resume");
        assertThat(resumed)
            .containsEntry("A", true)
            .containsEntry("B", true)
            .containsEntry("C", true)
            .containsEntry("manual", true);
    }

    @Test
    void interruptAfterNode() {
        InMemoryCheckpointer memory = new InMemoryCheckpointer();
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of("A", true))
             .addNode("B", (state, token) -> Map.of("B", true))
             .addEdge("A", "B")
             .setEntryPoint("A");

        CompiledGraph<Map<String, Object>> compiled = graph.compile(
            memory, Set.of(), Set.of("A")
        );

        Map<String, Object> interrupted = compiled.invoke(Map.of(), "test-after", CancellationToken.create());

        assertThat(interrupted)
            .containsEntry("A", true)
            .doesNotContainKey("B");

        // Resume should continue from after A
        Map<String, Object> resumed = compiled.resume("test-after");
        assertThat(resumed).containsEntry("B", true);
    }

    @Test
    void resumeWithoutCheckpointThrows() {
        InMemoryCheckpointer memory = new InMemoryCheckpointer();
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of())
             .setEntryPoint("A");

        CompiledGraph<Map<String, Object>> compiled = graph.compile(memory);

        assertThatThrownBy(() -> compiled.resume("nonexistent"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No checkpoint");
    }
}
