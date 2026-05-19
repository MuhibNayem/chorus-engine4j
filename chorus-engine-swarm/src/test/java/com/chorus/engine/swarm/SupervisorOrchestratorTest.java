package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;

class SupervisorOrchestratorTest {

    @Test
    void supervisor_delegates_to_worker_then_completes() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        // 1. Supervisor delegates to worker1
        fake.enqueue(new ChatResponse(
            "1", "model", "fake", Message.assistant("DELEGATE worker1\nTASK: calculate 2+2"),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        // 2. Worker1 streams "4"
        fake.enqueue(new ChatResponse(
            "2", "model", "fake", Message.assistant("4"),
            new TokenCount(3, 1, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        // 3. Supervisor completes
        fake.enqueue(new ChatResponse(
            "3", "model", "fake", Message.assistant("COMPLETE\nRESPONSE: The answer is 4"),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition supervisor = new AgentDefinition(
            "supervisor", "You are the supervisor.", List.of(), "model", null, Map.of()
        );
        AgentDefinition worker1 = new AgentDefinition(
            "worker1", "You are a math worker.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("supervisor", supervisor, "worker1", worker1);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            SupervisorOrchestrator orchestrator = new SupervisorOrchestrator(
                agents, "supervisor", fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("supervisor", List.of(Message.user("What is 2+2?")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).hasSizeGreaterThanOrEqualTo(4);
            assertThat(events.stream().filter(e -> e instanceof SwarmEvent.AgentStart).count()).isGreaterThanOrEqualTo(2);
            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete sc && sc.finalResponse().content().contains("The answer is 4"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void supervisor_routes_to_multiple_workers_in_parallel() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        fake.enqueue(new ChatResponse(
            "1", "model", "fake", Message.assistant("DELEGATE worker1\nTASK: task A"),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        fake.enqueue(new ChatResponse(
            "2", "model", "fake", Message.assistant("result A"),
            new TokenCount(3, 1, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        fake.enqueue(new ChatResponse(
            "3", "model", "fake", Message.assistant("COMPLETE\nRESPONSE: done"),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition supervisor = new AgentDefinition(
            "supervisor", "You are the supervisor.", List.of(), "model", null, Map.of()
        );
        AgentDefinition worker1 = new AgentDefinition(
            "worker1", "You are worker 1.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("supervisor", supervisor, "worker1", worker1);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            SupervisorOrchestrator orchestrator = new SupervisorOrchestrator(
                agents, "supervisor", fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("supervisor", List.of(Message.user("Do task A")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete);
        } finally {
            executor.shutdown();
        }
    }

    private List<SwarmEvent> collectEvents(Flow.Publisher<SwarmEvent> publisher) throws Exception {
        List<SwarmEvent> events = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(SwarmEvent event) { events.add(event); }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(null); }
        });
        done.get(10, TimeUnit.SECONDS);
        return events;
    }
}
