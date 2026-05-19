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

class HandoffOrchestratorTest {

    @Test
    void dynamic_handoff_between_agents() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        // Agent1 hands off to agent2 via tool call
        fake.enqueue(new ChatResponse(
            "1", "model", "fake", Message.assistant("I'll transfer this to the math specialist."),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop",
            List.of(new ChatResponse.ToolCall("call_1", "transfer_to_agent2",
                Map.of("reason", "need math"))),
            null, Map.of()
        ));

        // Agent2 completes without tool calls
        fake.enqueue(new ChatResponse(
            "2", "model", "fake", Message.assistant("42"),
            new TokenCount(3, 1, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition agent1 = new AgentDefinition(
            "agent1", "You are the triage agent.", List.of(), "model", List.of("agent2"), Map.of()
        );
        AgentDefinition agent2 = new AgentDefinition(
            "agent2", "You are the math agent.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("agent1", agent1, "agent2", agent2);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            HandoffOrchestrator orchestrator = new HandoffOrchestrator(
                agents, fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("agent1", List.of(Message.user("What is 6*7?")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.HandoffEvent he &&
                he.handoff().fromAgent().equals("agent1") &&
                he.handoff().toAgent().equals("agent2") &&
                he.handoff().reason().equals("need math"));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete sc &&
                sc.finalAgent().equals("agent2") &&
                sc.finalResponse().content().equals("42"));

            assertThat(session.activeAgent()).isEqualTo("agent2");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void agent_without_handoff_completes_directly() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        fake.enqueue(new ChatResponse(
            "1", "model", "fake", Message.assistant("Hello!"),
            new TokenCount(3, 2, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition agent = new AgentDefinition(
            "agent", "You are a friendly agent.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("agent", agent);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            HandoffOrchestrator orchestrator = new HandoffOrchestrator(
                agents, fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("agent", List.of(Message.user("Hi")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete sc &&
                sc.finalAgent().equals("agent") &&
                sc.finalResponse().content().equals("Hello!"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void max_turns_terminates_infinite_loop() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        // Agent1 always hands off to agent2
        for (int i = 0; i < 5; i++) {
            fake.enqueue(new ChatResponse(
                "r" + i, "model", "fake", Message.assistant("handoff"),
                new TokenCount(1, 1, "test"), Duration.ZERO, "stop",
                List.of(new ChatResponse.ToolCall("c" + i, "transfer_to_agent2", Map.of("reason", "loop"))),
                null, Map.of()
            ));
        }

        AgentDefinition agent1 = new AgentDefinition(
            "agent1", "You are agent 1.", List.of(), "model", List.of("agent2"), Map.of()
        );
        AgentDefinition agent2 = new AgentDefinition(
            "agent2", "You are agent 2.", List.of(), "model", List.of("agent1"), Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("agent1", agent1, "agent2", agent2);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(3, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            HandoffOrchestrator orchestrator = new HandoffOrchestrator(
                agents, fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("agent1", List.of(Message.user("Loop test")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmError);
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
