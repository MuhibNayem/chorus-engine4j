package com.chorus.engine.graph;

import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.graph.debug.CheckpointBrowser;
import com.chorus.engine.graph.debug.CheckpointEntry;
import com.chorus.engine.graph.viz.GraphVisualizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class GraphRestServer<S> {

    private final CompiledGraph<S> graph;
    private final Checkpointer checkpointer;
    private final CheckpointBrowser<S> checkpointBrowser;
    private final ObjectMapper mapper;
    private final Map<String, S> threadStates = new ConcurrentHashMap<>();
    private final String apiKey;
    private final String graphId;

    public GraphRestServer(CompiledGraph<S> graph, Checkpointer checkpointer, String apiKey, String graphId) {
        this.graph = graph;
        this.checkpointer = checkpointer;
        this.checkpointBrowser = checkpointer != null ? new CheckpointBrowser<>(graph, checkpointer) : null;
        this.mapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.graphId = graphId;
    }

    public GraphRestServer(CompiledGraph<S> graph, Checkpointer checkpointer, String apiKey) {
        this(graph, checkpointer, apiKey, "default");
    }

    @PostMapping("/threads")
    public ResponseEntity<Map<String, Object>> createThread(@RequestBody(required = false) Map<String, Object> body) {
        String threadId = java.util.UUID.randomUUID().toString();
        threadStates.put(threadId, null);
        return ResponseEntity.ok(Map.of("thread_id", threadId, "created_at", System.currentTimeMillis()));
    }

    @GetMapping("/threads/{threadId}")
    public ResponseEntity<Map<String, Object>> getThread(@PathVariable String threadId) {
        if (!threadStates.containsKey(threadId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("thread_id", threadId));
    }

    @DeleteMapping("/threads/{threadId}")
    public ResponseEntity<Void> deleteThread(@PathVariable String threadId) {
        threadStates.remove(threadId);
        if (checkpointer != null) {
            checkpointer.delete(threadId).join();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/threads/{threadId}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamRun(@PathVariable String threadId, @RequestBody Map<String, Object> input) {
        return Flux.defer(() -> {
            @SuppressWarnings("unchecked")
            S state = (S) input.getOrDefault("state", Map.of());
            return graph.stream(state, threadId, null)
                .map(event -> {
                    try {
                        return "data: " + mapper.writeValueAsString(event) + "\n\n";
                    } catch (Exception e) {
                        return "data: {\"error\":\"serialization\"}\n\n";
                    }
                });
        });
    }

    @PostMapping("/threads/{threadId}/runs/{runId}/resume")
    public ResponseEntity<Map<String, Object>> resumeRun(@PathVariable String threadId,
                                                          @PathVariable String runId,
                                                          @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> update = (Map<String, Object>) body.getOrDefault("update", Map.of());
        return ResponseEntity.ok(Map.of("thread_id", threadId, "status", "resumed"));
    }

    @GetMapping("/threads/{threadId}/checkpoints")
    public ResponseEntity<?> listCheckpoints(@PathVariable String threadId) {
        if (checkpointBrowser == null) {
            return ResponseEntity.ok(Map.of("checkpoints", List.of()));
        }
        List<CheckpointEntry> entries = checkpointBrowser.listCheckpoints(threadId);
        return ResponseEntity.ok(Map.of("checkpoints", entries));
    }

    @PostMapping("/threads/{threadId}/checkpoints/{round}/fork")
    public ResponseEntity<Map<String, Object>> forkAtCheckpoint(@PathVariable String threadId,
                                                                 @PathVariable int round,
                                                                 @RequestBody Map<String, Object> body) {
        if (checkpointBrowser == null) {
            return ResponseEntity.status(503).body(Map.of("error", "checkpointing not configured"));
        }
        String newThreadId = (String) body.getOrDefault("new_thread_id", java.util.UUID.randomUUID().toString());
        checkpointBrowser.forkAt(threadId, round, newThreadId);
        threadStates.put(newThreadId, null);
        return ResponseEntity.ok(Map.of("thread_id", threadId, "round", round,
            "new_thread_id", newThreadId, "status", "forked"));
    }

    @PostMapping("/threads/{threadId}/checkpoints/{round}/replay")
    public ResponseEntity<Map<String, Object>> replayFromCheckpoint(@PathVariable String threadId,
                                                                     @PathVariable int round,
                                                                     @RequestBody Map<String, Object> body) {
        if (checkpointBrowser == null) {
            return ResponseEntity.status(503).body(Map.of("error", "checkpointing not configured"));
        }
        @SuppressWarnings("unchecked")
        S modifiedState = (S) body.getOrDefault("state", Map.of());
        S result = checkpointBrowser.replayFrom(threadId, round, modifiedState);
        return ResponseEntity.ok(Map.of("thread_id", threadId, "round", round,
            "status", "replayed", "state", result));
    }

    @GetMapping(value = "/graphs/{graphId}/mermaid", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getMermaid(@PathVariable String graphId) {
        if (!this.graphId.equals(graphId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(GraphVisualizer.toMermaid(graph.getStateGraph()));
    }
}
