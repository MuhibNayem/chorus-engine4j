package com.chorus.engine.springboot;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.reactive.FlowCollector;
import com.chorus.engine.swarm.*;
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
import java.util.stream.Collectors;

/**
 * REST endpoints for swarm operations.
 */
@RestController
@RequestMapping("/api/swarm")
public class SwarmController {

    private final SwarmOrchestrator swarmOrchestrator;

    public SwarmController(SwarmOrchestrator swarmOrchestrator) {
        this.swarmOrchestrator = swarmOrchestrator;
    }

    /**
     * Run a swarm session.
     */
    @PostMapping("/run")
    public ResponseEntity<SwarmRunResponse> run(@RequestBody SwarmRunRequest request) {
        List<Message> messages = request.messages().stream()
            .map(m -> Message.user(m.content()))
            .collect(Collectors.toList());

        SwarmSession session = swarmOrchestrator.createSession(
            request.initialAgent() != null ? request.initialAgent() : "default",
            messages
        );

        CancellationToken token = CancellationToken.create();
        Flow.Publisher<SwarmEvent> publisher = swarmOrchestrator.run(session, token);

        try {
            List<SwarmEvent> events = FlowCollector.toList(publisher, Duration.ofMinutes(5), token);
            String result = events.stream()
                .map(SwarmEvent::toString)
                .collect(Collectors.joining("\n"));
            return ResponseEntity.ok(new SwarmRunResponse(
                session.sessionId(),
                result,
                Map.of("turns", session.turnCount(), "activeAgent", session.activeAgent())
            ));
        } catch (TimeoutException e) {
            return ResponseEntity.internalServerError().body(
                new SwarmRunResponse(session.sessionId(), "ERROR: Timeout", Map.of()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().body(
                new SwarmRunResponse(session.sessionId(), "ERROR: Interrupted", Map.of()));
        }
    }

    /**
     * Dynamic handoff between agents.
     */
    @PostMapping("/handoff")
    public ResponseEntity<HandoffResponse> handoff(@RequestBody HandoffRequest request) {
        List<Message> messages = request.messages().stream()
            .map(m -> Message.user(m.content()))
            .toList();

        Handoff handoff = new Handoff(
            request.fromAgent(),
            request.toAgent(),
            request.reason(),
            messages
        );

        return ResponseEntity.ok(new HandoffResponse(
            handoff.fromAgent(),
            handoff.toAgent(),
            handoff.reason(),
            "Handoff created"
        ));
    }

    public record SwarmRunRequest(
        String initialAgent,
        List<MessageDto> messages
    ) {}

    public record MessageDto(
        String role,
        String content
    ) {}

    public record SwarmRunResponse(
        String sessionId,
        String result,
        Map<String, Object> metadata
    ) {}

    public record HandoffRequest(
        String fromAgent,
        String toAgent,
        String reason,
        List<MessageDto> messages
    ) {}

    public record HandoffResponse(
        String fromAgent,
        String toAgent,
        String reason,
        String status
    ) {}
}
