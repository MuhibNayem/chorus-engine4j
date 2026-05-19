package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;

class GroupChatTest {

    @Test
    void round_robin_conversation_converges() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        // Round 1: all three agents agree immediately
        fake.enqueue(new ChatResponse(
            "1", "model", "fake", Message.assistant("I think we should go with option A. [AGREE]"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));
        fake.enqueue(new ChatResponse(
            "2", "model", "fake", Message.assistant("Option A sounds good. [AGREE]"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));
        fake.enqueue(new ChatResponse(
            "3", "model", "fake", Message.assistant("I agree. [AGREE]"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition a1 = new AgentDefinition(
            "a1", "You are agent 1.", List.of(), "model", null, Map.of()
        );
        AgentDefinition a2 = new AgentDefinition(
            "a2", "You are agent 2.", List.of(), "model", null, Map.of()
        );
        AgentDefinition a3 = new AgentDefinition(
            "a3", "You are agent 3.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("a1", a1, "a2", a2, "a3", a3);
        SwarmConfig config = new SwarmConfig(20, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            GroupChat groupChat = new GroupChat(
                List.of("a1", "a2", "a3"), agents, fake, config, executor, 5
            );

            SwarmSession session = groupChat.createSession("a1", List.of(Message.user("Discuss option A")));
            List<SwarmEvent> events = collectEvents(groupChat.run(session, CancellationToken.create()));

            List<SwarmEvent.AgentStart> starts = events.stream()
                .filter(e -> e instanceof SwarmEvent.AgentStart)
                .map(e -> (SwarmEvent.AgentStart) e)
                .toList();

            assertThat(starts).extracting(SwarmEvent.AgentStart::agentName)
                .containsExactly("a1", "a2", "a3");

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void identical_messages_trigger_convergence() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        fake.enqueue(new ChatResponse(
            "1", "model", "fake", Message.assistant("Consensus reached."),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));
        fake.enqueue(new ChatResponse(
            "2", "model", "fake", Message.assistant("Consensus reached."),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition a1 = new AgentDefinition(
            "a1", "You are agent 1.", List.of(), "model", null, Map.of()
        );
        AgentDefinition a2 = new AgentDefinition(
            "a2", "You are agent 2.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("a1", a1, "a2", a2);
        SwarmConfig config = new SwarmConfig(20, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            GroupChat groupChat = new GroupChat(
                List.of("a1", "a2"), agents, fake, config, executor, 5
            );

            SwarmSession session = groupChat.createSession("a1", List.of(Message.user("Decide")));
            List<SwarmEvent> events = collectEvents(groupChat.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete sc &&
                sc.finalResponse().content().equals("Consensus reached."));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void max_rounds_terminates_without_convergence() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        // Always different responses
        for (int i = 0; i < 6; i++) {
            fake.enqueue(new ChatResponse(
                "r" + i, "model", "fake", Message.assistant("Opinion " + i),
                new TokenCount(1, 1, "test"), Duration.ZERO, "stop", null, null, Map.of()
            ));
        }

        AgentDefinition a1 = new AgentDefinition(
            "a1", "You are agent 1.", List.of(), "model", null, Map.of()
        );
        AgentDefinition a2 = new AgentDefinition(
            "a2", "You are agent 2.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("a1", a1, "a2", a2);
        SwarmConfig config = new SwarmConfig(20, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            GroupChat groupChat = new GroupChat(
                List.of("a1", "a2"), agents, fake, config, executor, 2
            );

            SwarmSession session = groupChat.createSession("a1", List.of(Message.user("Discuss")));
            List<SwarmEvent> events = collectEvents(groupChat.run(session, CancellationToken.create()));

            // Should complete after max rounds (2 rounds = 4 agent responses)
            List<SwarmEvent.AgentStart> starts = events.stream()
                .filter(e -> e instanceof SwarmEvent.AgentStart)
                .map(e -> (SwarmEvent.AgentStart) e)
                .toList();
            assertThat(starts).hasSize(4); // 2 rounds * 2 agents
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
