package com.chorus.engine.springboot;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.reactive.FlowCollector;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

/**
 * REST endpoints for agent execution.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentLoop agentLoop;
    private final LlmClient llmClient;

    public AgentController(AgentLoop agentLoop, LlmClient llmClient) {
        this.agentLoop = agentLoop;
        this.llmClient = llmClient;
    }

    /**
     * Run the agent with a list of messages and return the final response.
     */
    @PostMapping("/run")
    public ResponseEntity<AgentRunResponse> run(@RequestBody AgentRunRequest request) {
        String userInput = request.messages().stream()
            .filter(m -> "user".equalsIgnoreCase(m.role()))
            .reduce((a, b) -> b)
            .map(MessageDto::content)
            .orElse("");

        List<ToolDefinition> tools = request.tools() != null ? request.tools() : List.of();
        CancellationToken token = CancellationToken.create();

        Flow.Publisher<AgentEvent> publisher = agentLoop.run(
            request.runId() != null ? request.runId() : java.util.UUID.randomUUID().toString(),
            userInput,
            tools,
            token
        );

        try {
            List<AgentEvent> events = FlowCollector.toList(publisher, Duration.ofMinutes(5), token);
            String finalAnswer = events.stream()
                .filter(e -> e instanceof AgentEvent.Done)
                .map(e -> ((AgentEvent.Done) e).finalAnswer())
                .reduce((a, b) -> b)
                .orElse("");
            return ResponseEntity.ok(new AgentRunResponse(finalAnswer, null, Map.of()));
        } catch (TimeoutException e) {
            return ResponseEntity.internalServerError().body(
                new AgentRunResponse("ERROR: Timeout", null, Map.of()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().body(
                new AgentRunResponse("ERROR: Interrupted", null, Map.of()));
        }
    }

    /**
     * Stream agent events as Server-Sent Events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentRunRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        String userInput = request.messages().stream()
            .filter(m -> "user".equalsIgnoreCase(m.role()))
            .reduce((a, b) -> b)
            .map(MessageDto::content)
            .orElse("");

        List<ToolDefinition> tools = request.tools() != null ? request.tools() : List.of();
        CancellationToken token = CancellationToken.create();

        Flow.Publisher<AgentEvent> publisher = agentLoop.run(
            request.runId() != null ? request.runId() : java.util.UUID.randomUUID().toString(),
            userInput,
            tools,
            token
        );

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent event) {
                try {
                    emitter.send(SseEmitter.event()
                        .name(event.getClass().getSimpleName())
                        .data(event.toString()));
                    if (event instanceof AgentEvent.Done) {
                        emitter.complete();
                    }
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }
            @Override public void onError(Throwable t) { emitter.completeWithError(t); }
            @Override public void onComplete() { emitter.complete(); }
        });

        emitter.onTimeout(() -> {
            token.cancel("SSE timeout");
            emitter.complete();
        });

        return emitter;
    }

    /**
     * Agent health check.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        LlmClient.HealthStatus status = llmClient != null ? llmClient.health() : LlmClient.HealthStatus.UNAVAILABLE;
        return ResponseEntity.ok(Map.of(
            "status", status.name(),
            "provider", llmClient != null ? llmClient.providerName() : "none"
        ));
    }

    public record AgentRunRequest(
        String runId,
        List<MessageDto> messages,
        List<ToolDefinition> tools
    ) {}

    public record MessageDto(
        String role,
        String content
    ) {}

    public record AgentRunResponse(
        String output,
        String error,
        Map<String, Object> metadata
    ) {}
}
