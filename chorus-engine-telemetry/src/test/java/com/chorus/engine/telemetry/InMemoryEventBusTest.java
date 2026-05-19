package com.chorus.engine.telemetry;

import com.chorus.engine.telemetry.event.AgentStartEvent;
import com.chorus.engine.telemetry.event.ChorusEvent;
import com.chorus.engine.telemetry.event.InMemoryEventBus;
import com.chorus.engine.telemetry.event.LlmCallEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class InMemoryEventBusTest {

    @Test
    void publish_deliversToSubscribedType() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<ChorusEvent> received = new ArrayList<>();

        bus.subscribe("agent.start", received::add);

        AgentStartEvent event = new AgentStartEvent("run-1", "agent-a", "gpt-4o", Instant.now());
        bus.publish(event);

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(AgentStartEvent.class);
        assertThat(((AgentStartEvent) received.get(0)).agentId()).isEqualTo("agent-a");
    }

    @Test
    void publish_doesNotDeliverToUnsubscribedType() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<ChorusEvent> received = new ArrayList<>();

        bus.subscribe("agent.start", received::add);

        LlmCallEvent event = new LlmCallEvent("run-1", "openai", "gpt-4o", 10, 20, Duration.ofMillis(100), Instant.now());
        bus.publish(event);

        assertThat(received).isEmpty();
    }

    @Test
    void wildcardReceivesAllEvents() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<ChorusEvent> received = new ArrayList<>();

        bus.subscribe("*", received::add);

        bus.publish(new AgentStartEvent("run-1", "agent-a", "gpt-4o", Instant.now()));
        bus.publish(new LlmCallEvent("run-1", "openai", "gpt-4o", 10, 20, Duration.ofMillis(100), Instant.now()));

        assertThat(received).hasSize(2);
    }

    @Test
    void multipleSubscribersReceiveEvent() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<ChorusEvent> r1 = new ArrayList<>();
        List<ChorusEvent> r2 = new ArrayList<>();

        bus.subscribe("agent.start", r1::add);
        bus.subscribe("agent.start", r2::add);

        bus.publish(new AgentStartEvent("run-1", "agent-a", "gpt-4o", Instant.now()));

        assertThat(r1).hasSize(1);
        assertThat(r2).hasSize(1);
    }

    @Test
    void subscriberExceptionDoesNotBreakBus() throws InterruptedException {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<ChorusEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        bus.subscribe("agent.start", e -> { throw new RuntimeException("boom"); });
        bus.subscribe("agent.start", e -> {
            received.add(e);
            latch.countDown();
        });

        bus.publish(new AgentStartEvent("run-1", "agent-a", "gpt-4o", Instant.now()));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
    }
}
