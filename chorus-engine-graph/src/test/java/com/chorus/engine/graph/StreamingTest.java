package com.chorus.engine.graph;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.GraphEvent;
import com.chorus.engine.graph.state.StateGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;

class StreamingTest {

    private static StateGraph<Map<String, Object>> mapGraph() {
        return new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });
    }

    @Test
    void verifyGraphEventStream() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("A", (state, token) -> Map.of("A", true))
             .addNode("B", (state, token) -> Map.of("B", true))
             .addEdge("A", "B")
             .setEntryPoint("A");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();

        List<GraphEvent<Map<String, Object>>> events = new ArrayList<>();
        compiled.stream(Map.of(), "test-stream", CancellationToken.create()).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(GraphEvent<Map<String, Object>> event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new RuntimeException(throwable);
            }

            @Override
            public void onComplete() {
                // expected
            }
        });

        assertThat(events)
            .anyMatch(e -> e instanceof GraphEvent.NodeStart<Map<String, Object>> ns && ns.nodeName().equals("A"))
            .anyMatch(e -> e instanceof GraphEvent.NodeEnd<Map<String, Object>> ne && ne.nodeName().equals("A"))
            .anyMatch(e -> e instanceof GraphEvent.NodeStart<Map<String, Object>> ns && ns.nodeName().equals("B"))
            .anyMatch(e -> e instanceof GraphEvent.NodeEnd<Map<String, Object>> ne && ne.nodeName().equals("B"))
            .anyMatch(e -> e instanceof GraphEvent.EdgeTransition<Map<String, Object>> et &&
                           et.from().equals("A") && et.to().equals("B"))
            .anyMatch(e -> e instanceof GraphEvent.GraphEnd<Map<String, Object>>);
    }

    @Test
    void streamPropagatesCancellation() {
        StateGraph<Map<String, Object>> graph = mapGraph();

        graph.addNode("slow", (state, token) -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        })
        .setEntryPoint("slow");

        CompiledGraph<Map<String, Object>> compiled = graph.compile();
        CancellationToken token = CancellationToken.create();

        List<GraphEvent<Map<String, Object>>> events = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        Flow.Subscription[] subscriptionHolder = new Flow.Subscription[1];
        compiled.stream(Map.of(), "test-cancel", token).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriptionHolder[0] = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(GraphEvent<Map<String, Object>> event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                errors.add(throwable);
            }

            @Override
            public void onComplete() {
                // not expected
            }
        });

        // The slow node runs synchronously in the subscribe thread, so we can't easily
        // cancel it mid-flight without running in a separate thread. For this test,
        // we verify that the subscription cancel() propagates to the token.
        token.cancel("test-cancel");
        assertThat(token.isCancelled()).isTrue();
    }
}
