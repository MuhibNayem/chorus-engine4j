package com.chorus.engine.springboot;

import com.chorus.engine.a2a.client.A2aClient;
import com.chorus.engine.a2a.task.Task;
import com.chorus.engine.a2a.task.Message;
import com.chorus.engine.a2a.task.Part;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for A2A (Agent-to-Agent) protocol operations.
 * Only available when an A2aClient bean is configured.
 */
@RestController
@RequestMapping("/api/a2a")
@ConditionalOnBean(A2aClient.class)
public class A2aController {

    private final A2aClient a2aClient;

    public A2aController(A2aClient a2aClient) {
        this.a2aClient = a2aClient;
    }

    @GetMapping("/agent-card")
    public ResponseEntity<AgentCardResponse> fetchAgentCard() {
        try {
            var card = a2aClient.fetchAgentCard();
            return ResponseEntity.ok(new AgentCardResponse(
                card.name(), card.description(), card.version(), card.url(), true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(new AgentCardResponse(
                null, null, null, null, false, e.getMessage()
            ));
        }
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskResponse> sendTask(@RequestBody SendTaskRequest request) {
        try {
            var task = new Task(
                request.taskId() != null ? request.taskId() : java.util.UUID.randomUUID().toString(),
                request.sessionId() != null ? request.sessionId() : "default",
                Task.Status.SUBMITTED,
                List.of(new Message(
                    Message.Role.USER,
                    List.of(new Part.TextPart(request.message())),
                    null
                )),
                null, null
            );
            var result = a2aClient.sendTask(task);
            return ResponseEntity.ok(new TaskResponse(
                result.id(), result.status().value(), null
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                new TaskResponse(null, null, e.getMessage())
            );
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable String taskId) {
        try {
            var result = a2aClient.getTask(taskId);
            return ResponseEntity.ok(new TaskResponse(
                result.id(), result.status().value(), null
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                new TaskResponse(taskId, null, e.getMessage())
            );
        }
    }

    public record AgentCardResponse(
        String name, String description, String version, String url,
        boolean available, String error
    ) {}

    public record SendTaskRequest(String taskId, String sessionId, String message) {}
    public record TaskResponse(String taskId, String state, String error) {}
}
