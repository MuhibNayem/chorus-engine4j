package com.chorus.engine.integration;

import com.chorus.engine.graph.*;
import com.chorus.engine.graph.channel.Channel;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class StateGraphTest {

    @Test
    void testLinearChain() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>(Map.of(
            "count", Channel.sum(),
            "messages", Channel.<String>append()
        ));

        graph.addNode("increment", state -> {
            return Map.of("count", 1);
        });
        graph.addNode("append", state -> {
            return Map.of("messages", List.of("hello"));
        });
        graph.addEdge(StateGraph.START, "increment");
        graph.addEdge("increment", "append");
        graph.addEdge("append", StateGraph.END);

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> initial = Map.of();
        Map<String, Object> result = compiled.invoke(initial);

        assertThat(result.get("count")).isEqualTo(1);
        assertThat(result.get("messages")).asList().containsExactly("hello");
    }

    @Test
    void testParallelExecution() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>(Map.of(
            "a", Channel.sum(),
            "b", Channel.sum()
        ));

        graph.addNode("nodeA", state -> {
            return Map.of("a", 10);
        });
        graph.addNode("nodeB", state -> {
            return Map.of("b", 20);
        });
        graph.addEdge(StateGraph.START, "nodeA");
        graph.addEdge(StateGraph.START, "nodeB");
        graph.addEdge("nodeA", StateGraph.END);
        graph.addEdge("nodeB", StateGraph.END);

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of());

        assertThat(result.get("a")).isEqualTo(10);
        assertThat(result.get("b")).isEqualTo(20);
    }

    @Test
    void testConditionalEdges() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>(Map.of(
            "value", Channel.lastValue(0)
        ));

        graph.addNode("decider", state -> {
            return Map.of("value", 42);
        });
        graph.addNode("high", state -> {
            return Map.of("value", 100);
        });
        graph.addNode("low", state -> {
            return Map.of("value", 1);
        });
        graph.addEdge(StateGraph.START, "decider");
        graph.addConditionalEdge("decider", state -> {
            int val = (int) state.getOrDefault("value", 0);
            return val > 20 ? "high" : "low";
        });
        graph.addEdge("high", StateGraph.END);
        graph.addEdge("low", StateGraph.END);

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        Map<String, Object> result = compiled.invoke(Map.of());

        assertThat(result.get("value")).isEqualTo(100);
    }

    @Test
    void testCycleDetection() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>(Map.of());
        graph.addNode("a", state -> state);
        graph.addNode("b", state -> state);
        graph.addEdge(StateGraph.START, "a");
        graph.addEdge("a", "b");
        graph.addEdge("b", "a"); // cycle

        assertThatThrownBy(graph::compile)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cycle detected");
    }

    @Test
    void testStreamingEvents() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>(Map.of("count", Channel.sum()));
        graph.addNode("inc", state -> {
            return Map.of("count", 1);
        });
        graph.addEdge(StateGraph.START, "inc");
        graph.addEdge("inc", StateGraph.END);

        CompiledGraph<Map<String, Object>> compiled = graph.compile();

        StepVerifier.create(compiled.stream(Map.of()))
            .expectNextMatches(e -> e instanceof GraphEvent.NodeStartEvent)
            .expectNextMatches(e -> e instanceof GraphEvent.NodeEndEvent)
            .expectNextMatches(e -> e instanceof GraphEvent.StateEvent)
            .expectNextMatches(e -> e instanceof GraphEvent.EndEvent)
            .verifyComplete();
    }

    @Test
    void testInterruptAndResume() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>(Map.of("approved", Channel.lastValue(false)));
        graph.addNode("request", state -> {
            throw new GraphInterrupt("request", "need approval");
        });
        graph.addEdge(StateGraph.START, "request");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();

        StepVerifier.create(compiled.stream(Map.of()))
            .expectNextMatches(e -> e instanceof GraphEvent.NodeStartEvent)
            .expectNextMatches(e -> e instanceof GraphEvent.InterruptEvent ie && "need approval".equals(ie.value()))
            .verifyComplete();
    }
}
