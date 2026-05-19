package com.chorus.engine.core.a2a.server;

import com.chorus.engine.core.a2a.AgentCard;
import com.chorus.engine.core.a2a.A2aArtifact;
import com.chorus.engine.core.a2a.A2aMessage;
import com.chorus.engine.core.a2a.A2aTask;
import com.chorus.engine.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A server REST controller. Exposes agent capabilities and handles task delegation.
 * Extracts W3C Trace Context from incoming requests for distributed tracing.
 */
@RestController
@RequestMapping("/a2a")
public class A2aServerController {

    private static final Logger log = LoggerFactory.getLogger(A2aServerController.class);

    private final AgentCard agentCard;
    private final TaskStore taskStore;

    public A2aServerController(AgentCard agentCard) {
        this.agentCard = agentCard;
        this.taskStore = new TaskStore();
    }

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<AgentCard> getAgentCard() {
        return ResponseEntity.ok(agentCard);
    }

    @PostMapping("/tasks")
    public ResponseEntity<A2aTask> submitTask(@RequestBody Map<String, Object> request,
                                               @RequestHeader Map<String, String> headers) {
        // Extract W3C Trace Context for distributed tracing
        TraceContext traceContext = TraceContext.extract(headers);
        if (traceContext != null) {
            log.debug("Received A2A request with trace context: {}", traceContext.traceId());
        }

        String taskId = java.util.UUID.randomUUID().toString();
        String description = (String) request.getOrDefault("description", "");
        String sessionId = (String) request.getOrDefault("sessionId", "default");

        A2aTask task = A2aTask.create(taskId, description, sessionId);
        taskStore.save(task);

        log.info("A2A task submitted: {} - {} (traceId={})", taskId, description,
            traceContext != null ? traceContext.traceId() : "none");
        return ResponseEntity.ok(task);
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<A2aTask> getTask(@PathVariable String taskId) {
        A2aTask task = taskStore.find(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

    @PostMapping("/tasks/{taskId}/messages")
    public ResponseEntity<A2aTask> addMessage(@PathVariable String taskId, @RequestBody A2aMessage message) {
        A2aTask task = taskStore.find(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }

        List<A2aMessage> updatedMessages = new java.util.ArrayList<>(task.messages());
        updatedMessages.add(message);
        taskStore.save(task.withMessages(updatedMessages));

        return ResponseEntity.ok(taskStore.find(taskId));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<A2aTask> cancelTask(@PathVariable String taskId) {
        A2aTask task = taskStore.find(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }

        taskStore.save(task.withState("CANCELED"));
        return ResponseEntity.ok(taskStore.find(taskId));
    }

    @PostMapping("/tasks/{taskId}/artifacts")
    public ResponseEntity<A2aTask> addArtifact(@PathVariable String taskId, @RequestBody A2aArtifact artifact) {
        A2aTask task = taskStore.find(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }

        List<A2aArtifact> updatedArtifacts = new java.util.ArrayList<>(task.artifacts());
        updatedArtifacts.add(artifact);
        A2aTask updated = task.withArtifacts(updatedArtifacts)
            .withState("COMPLETED")
            .withMessages(task.messages());

        taskStore.save(updated);
        return ResponseEntity.ok(updated);
    }

    /**
     * In-memory task storage.
     */
    public static class TaskStore {
        private final Map<String, A2aTask> tasks = new ConcurrentHashMap<>();

        public void save(A2aTask task) {
            tasks.put(task.id(), task);
        }

        public A2aTask find(String taskId) {
            return tasks.get(taskId);
        }

        public List<A2aTask> list() {
            return List.copyOf(tasks.values());
        }
    }
}
