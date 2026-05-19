package com.chorus.engine.springboot;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.state.StateGraph;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * REST endpoints for graph-based workflow execution.
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final ConcurrentHashMap<String, StateGraph<Map<String, Object>>> graphs = new ConcurrentHashMap<>();

    @PostMapping("/define")
    public ResponseEntity<DefineGraphResponse> defineGraph(@RequestBody DefineGraphRequest request) {
        StateGraph<Map<String, Object>> graph = new StateGraph<>((current, update) -> {
            Map<String, Object> merged = new java.util.HashMap<>(current);
            merged.putAll(update);
            return Map.copyOf(merged);
        });

        for (NodeDef node : request.nodes()) {
            graph.addNode(node.name(), (state, token) -> {
                // Merge node output into shared state
                Map<String, Object> result = new java.util.HashMap<>(state);
                result.put(node.name() + "_executed", true);
                result.put(node.name() + "_timestamp", System.currentTimeMillis());
                if (node.description() != null) {
                    result.put(node.name() + "_description", node.description());
                }
                return Map.copyOf(result);
            });
        }

        for (EdgeDef edge : request.edges()) {
            graph.addEdge(edge.from(), edge.to());
        }

        graph.setEntryPoint(request.entryPoint());
        if (request.finishPoint() != null) {
            graph.setFinishPoint(request.finishPoint());
        }

        graphs.put(request.graphId(), graph);

        return ResponseEntity.ok(new DefineGraphResponse(
            request.graphId(), request.nodes().size(), request.edges().size(), "defined"
        ));
    }

    @PostMapping("/{graphId}/invoke")
    public ResponseEntity<InvokeGraphResponse> invoke(
        @PathVariable String graphId,
        @RequestBody InvokeGraphRequest request
    ) {
        StateGraph<Map<String, Object>> graph = graphs.get(graphId);
        if (graph == null) {
            return ResponseEntity.notFound().build();
        }

        var compiled = graph.compile();
        Map<String, Object> state = request.initialState() != null
            ? request.initialState()
            : Map.of();

        try {
            Map<String, Object> result = compiled.invoke(
                state,
                request.runId() != null ? request.runId() : java.util.UUID.randomUUID().toString(),
                CancellationToken.create()
            );
            return ResponseEntity.ok(new InvokeGraphResponse(graphId, result, "completed"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                new InvokeGraphResponse(graphId, Map.of("error", e.getMessage()), "failed")
            );
        }
    }

    @GetMapping
    public ResponseEntity<List<String>> listGraphs() {
        return ResponseEntity.ok(List.copyOf(graphs.keySet()));
    }

    public record DefineGraphRequest(
        String graphId,
        List<NodeDef> nodes,
        List<EdgeDef> edges,
        String entryPoint,
        String finishPoint
    ) {}

    public record NodeDef(String name, String description) {}
    public record EdgeDef(String from, String to) {}

    public record DefineGraphResponse(
        String graphId,
        int nodeCount,
        int edgeCount,
        String status
    ) {}

    public record InvokeGraphRequest(
        String runId,
        Map<String, Object> initialState
    ) {}

    public record InvokeGraphResponse(
        String graphId,
        Map<String, Object> result,
        String status
    ) {}
}
