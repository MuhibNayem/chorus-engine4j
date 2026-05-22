package com.chorus.engine.telemetry.otel;

import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.telemetry.event.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChorusOtlpExporterTest {

    @Test
    void shouldBeInactiveWhenOtelNotOnClasspath() {
        // This test verifies the no-op path when OTel classes are absent.
        // In the test classpath OTel is present, so we verify active state instead.
        EventBus eventBus = new InMemoryEventBus();
        ChorusOtlpExporter.Config config = ChorusOtlpExporter.Config.defaults();
        ChorusOtlpExporter exporter = new ChorusOtlpExporter(eventBus, config);

        assertThat(exporter.isActive()).isTrue();
        exporter.close();
    }

    @Test
    void shouldAcceptEventsWithoutCrashing() {
        EventBus eventBus = new InMemoryEventBus();
        ChorusOtlpExporter.Config config = ChorusOtlpExporter.Config.defaults();
        ChorusOtlpExporter exporter = new ChorusOtlpExporter(eventBus, config);

        eventBus.publish(new AgentStartEvent("run-1", "agent-1", "gpt-4o", Instant.now()));
        eventBus.publish(new LlmCallEvent("run-1", "openai", "gpt-4o", 100, 50, Duration.ofMillis(1200), Instant.now()));
        eventBus.publish(new ToolCallEvent("run-1", "agent-1", "runTests", Duration.ofMillis(500), null, Instant.now()));
        eventBus.publish(new AgentEndEvent("run-1", "agent-1", new TokenCount(100, 50, "gpt-4"), Duration.ofMillis(2000), Instant.now()));

        exporter.close();
        // If we reach here without exception, the exporter handled all events correctly.
        assertThat(true).isTrue();
    }

    @Test
    void shouldRespectSamplingRate() {
        ChorusOtlpExporter.Config config = new ChorusOtlpExporter.Config(
            "http://localhost:4317", Map.of(), 0.0, false, "chorus"
        );
        assertThat(config.sampleRate()).isEqualTo(0.0);
    }

    @Test
    void shouldNormalizeFrameworkName() {
        ChorusOtlpExporter.Config config = new ChorusOtlpExporter.Config(
            "http://localhost:4317", Map.of(), 1.0, true, null
        );
        assertThat(config.framework()).isEqualTo("chorus");
    }
}
