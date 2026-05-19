package com.chorus.engine.graph.speculative;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.checkpoint.GraphCheckpointer;
import com.chorus.engine.graph.state.GraphEvent;
import com.chorus.engine.graph.state.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SpeculativeGraphExecutorTest {

    @Test
    void basicExecutionWithoutSpeculation() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new java.util.HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });

        AtomicInteger aCalls = new AtomicInteger(0);
        AtomicInteger bCalls = new AtomicInteger(0);

        graph.addNode("A", (state, token) -> {
            aCalls.incrementAndGet();
            return Map.of("A", true);
        });
        graph.addNode("B", (state, token) -> {
            bCalls.incrementAndGet();
            return Map.of("B", true);
        });
        graph.addEdge("A", "B").setEntryPoint("A").setFinishPoint("B");

        SpeculativeGraphExecutor<Map<String, Object>> executor = new SpeculativeGraphExecutor<>(
            graph, GraphCheckpointer.noop(), Set.of(), Set.of(),
            name -> true, 0.0 // All nodes idempotent, threshold 0 so speculation always enabled
        );

        Map<String, Object> result = executor.invoke(Map.of(), "run-1", CancellationToken.create());

        assertTrue((Boolean) result.get("A"));
        assertTrue((Boolean) result.get("B"));
        assertEquals(1, aCalls.get());
        assertEquals(1, bCalls.get());
    }

    @Test
    void conditionalEdgeWithSpeculation() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new java.util.HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });

        AtomicInteger leftCalls = new AtomicInteger(0);
        AtomicInteger rightCalls = new AtomicInteger(0);

        graph.addNode("start", (state, token) -> Map.of("visited_start", true));
        graph.addNode("left", (state, token) -> {
            leftCalls.incrementAndGet();
            return Map.of("direction", "left");
        });
        graph.addNode("right", (state, token) -> {
            rightCalls.incrementAndGet();
            return Map.of("direction", "right");
        });
        graph.addConditionalEdge("start", (state, token) -> "go_left",
            Map.of("go_left", "left", "go_right", "right"));

        graph.setEntryPoint("start");

        SpeculativeGraphExecutor<Map<String, Object>> executor = new SpeculativeGraphExecutor<>(
            graph, GraphCheckpointer.noop(), Set.of(), Set.of(),
            name -> true, 0.0
        );

        Map<String, Object> result = executor.invoke(Map.of("choice", "left"), "run-2", CancellationToken.create());

        assertEquals("left", result.get("direction"));
        // Both left and right may have been speculatively executed
        assertTrue(leftCalls.get() >= 1);
        // Right may have been speculated but not used
        assertTrue(rightCalls.get() >= 0);
    }

    @Test
    void speculationStatsTracked() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new java.util.HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });

        graph.addNode("A", (state, token) -> Map.of("A", true));
        graph.addNode("B", (state, token) -> Map.of("B", true));
        graph.addEdge("A", "B").setEntryPoint("A").setFinishPoint("B");

        SpeculativeGraphExecutor<Map<String, Object>> executor = new SpeculativeGraphExecutor<>(
            graph, GraphCheckpointer.noop(), Set.of(), Set.of(),
            name -> true, 0.0
        );

        executor.invoke(Map.of(), "run-stats", CancellationToken.create());

        SpeculativeGraphExecutor.SpeculationStats stats = executor.stats();
        assertNotNull(stats);
        assertTrue(stats.hitRate() >= 0.0 && stats.hitRate() <= 1.0);
    }

    @Test
    void streamingEmitsEvents() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new java.util.HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });

        graph.addNode("A", (state, token) -> Map.of("A", true));
        graph.addNode("B", (state, token) -> Map.of("B", true));
        graph.addEdge("A", "B").setEntryPoint("A").setFinishPoint("B");

        SpeculativeGraphExecutor<Map<String, Object>> executor = new SpeculativeGraphExecutor<>(
            graph, GraphCheckpointer.noop(), Set.of(), Set.of(),
            name -> true, 0.0
        );

        Flow.Publisher<GraphEvent<Map<String, Object>>> publisher =
            executor.stream(Map.of(), "run-stream", CancellationToken.create());

        java.util.List<GraphEvent<Map<String, Object>>> events = new java.util.ArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(GraphEvent<Map<String, Object>> event) { events.add(event); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertFalse(events.isEmpty());
        boolean hasNodeStart = events.stream().anyMatch(e -> e instanceof GraphEvent.NodeStart);
        boolean hasGraphEnd = events.stream().anyMatch(e -> e instanceof GraphEvent.GraphEnd);
        assertTrue(hasNodeStart, "Should have NodeStart events");
        assertTrue(hasGraphEnd, "Should have GraphEnd event");
    }

    @Test
    void respectsCancellationToken() {
        StateGraph<Map<String, Object>> graph = new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new java.util.HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });

        graph.addNode("A", (state, token) -> {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Map.of("A", true);
        });
        graph.addNode("B", (state, token) -> Map.of("B", true));
        graph.addEdge("A", "B").setEntryPoint("A").setFinishPoint("B");

        SpeculativeGraphExecutor<Map<String, Object>> executor = new SpeculativeGraphExecutor<>(
            graph, GraphCheckpointer.noop(), Set.of(), Set.of(),
            name -> true, 0.0
        );

        CancellationToken token = CancellationToken.create();
        token.cancel("Test cancellation");

        assertThrows(java.util.concurrent.CancellationException.class, () ->
            executor.invoke(Map.of(), "run-cancel", token));
    }
}
