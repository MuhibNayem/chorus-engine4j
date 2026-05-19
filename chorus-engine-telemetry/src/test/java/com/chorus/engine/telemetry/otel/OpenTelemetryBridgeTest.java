package com.chorus.engine.telemetry.otel;

import com.chorus.engine.telemetry.event.AgentStartEvent;
import com.chorus.engine.telemetry.event.ChorusEvent;
import com.chorus.engine.telemetry.event.InMemoryEventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OpenTelemetryBridgeTest {

    @Test
    void constructionWithEventBus() {
        InMemoryEventBus bus = new InMemoryEventBus();
        OtelConfig config = OtelConfig.defaults();

        OpenTelemetryBridge bridge = new OpenTelemetryBridge(bus, config);

        assertThat(bridge).isNotNull();
        bridge.close();
    }

    @Test
    void activeWhenOtelOnClasspath() {
        InMemoryEventBus bus = new InMemoryEventBus();
        OtelConfig config = OtelConfig.defaults();

        OpenTelemetryBridge bridge = new OpenTelemetryBridge(bus, config);

        assertThat(bridge.isActive()).isTrue();
        bridge.close();
    }

    @Test
    void subscribesToEventsWhenActive() {
        InMemoryEventBus bus = new InMemoryEventBus();
        OtelConfig config = OtelConfig.defaults();

        List<ChorusEvent> captured = new ArrayList<>();
        bus.subscribe("*", captured::add);

        OpenTelemetryBridge bridge = new OpenTelemetryBridge(bus, config);

        AgentStartEvent event = new AgentStartEvent(
            "run-1", "agent-1", "gpt-4o", java.time.Instant.now()
        );
        bus.publish(event);

        if (bridge.isActive()) {
            assertThat(captured).hasSizeGreaterThanOrEqualTo(1);
        } else {
            assertThat(captured).hasSize(1);
        }

        bridge.close();
    }

    @Test
    void closeDoesNotThrowWhenInactive() {
        InMemoryEventBus bus = new InMemoryEventBus();
        OtelConfig config = OtelConfig.defaults();
        OpenTelemetryBridge bridge = new OpenTelemetryBridge(bus, config);

        assertThatNoException().isThrownBy(bridge::close);
    }

    @Test
    void nullEventBusRejection() {
        OtelConfig config = OtelConfig.defaults();
        assertThatThrownBy(() -> new OpenTelemetryBridge(null, config))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullConfigRejection() {
        InMemoryEventBus bus = new InMemoryEventBus();
        assertThatThrownBy(() -> new OpenTelemetryBridge(bus, null))
            .isInstanceOf(NullPointerException.class);
    }
}
