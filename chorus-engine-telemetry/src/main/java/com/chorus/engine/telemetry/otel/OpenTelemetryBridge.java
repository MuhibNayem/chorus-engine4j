package com.chorus.engine.telemetry.otel;

import com.chorus.engine.telemetry.event.AgentEndEvent;
import com.chorus.engine.telemetry.event.AgentStartEvent;
import com.chorus.engine.telemetry.event.ChorusEvent;
import com.chorus.engine.telemetry.event.CircuitBreakerEvent;
import com.chorus.engine.telemetry.event.EventBus;
import com.chorus.engine.telemetry.event.GuardrailEvent;
import com.chorus.engine.telemetry.event.HandoffEvent;
import com.chorus.engine.telemetry.event.LlmCallEvent;
import com.chorus.engine.telemetry.event.RagQueryEvent;
import com.chorus.engine.telemetry.event.ToolCallEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Bridges {@link ChorusEvent} instances to OpenTelemetry spans and metrics.
 * <p>
 * OTel classes are {@code compileOnly}; this class uses an inner delegate that is
 * only loaded when {@code io.opentelemetry.api.trace.Tracer} is present on the
 * classpath.  If OTel is absent the bridge is a no-op.
 */
public final class OpenTelemetryBridge {

    private final EventBus eventBus;
    private final @Nullable OtelDelegate delegate;

    public OpenTelemetryBridge(@NonNull EventBus eventBus, @NonNull OtelConfig config) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(config, "config");
        this.delegate = OtelDelegate.create(config);
        if (delegate != null) {
            eventBus.subscribe("*", this::onEvent);
        }
    }

    /**
     * Returns true when OTel is on the classpath and the bridge is active.
     */
    public boolean isActive() {
        return delegate != null;
    }

    public void close() {
        if (delegate != null) {
            delegate.close();
        }
    }

    private void onEvent(@NonNull ChorusEvent event) {
        if (delegate != null) {
            delegate.handle(event);
        }
    }

    /**
     * Inner class that actually references OTel APIs.
     * The JVM only loads this class when {@link #create} instantiates it,
     * which happens after the classpath probe succeeds.
     */
    private static final class OtelDelegate {

        private final io.opentelemetry.sdk.OpenTelemetrySdk openTelemetry;
        private final io.opentelemetry.api.trace.Tracer tracer;
        private final io.opentelemetry.api.metrics.Meter meter;

        private final io.opentelemetry.api.metrics.LongCounter agentRunsCounter;
        private final io.opentelemetry.api.metrics.LongCounter llmTokensCounter;
        private final io.opentelemetry.api.metrics.LongHistogram llmLatencyHistogram;
        private final io.opentelemetry.api.metrics.LongCounter toolCallsCounter;
        private final io.opentelemetry.api.metrics.LongCounter ragQueriesCounter;

        private final java.util.concurrent.ConcurrentHashMap<String, io.opentelemetry.api.trace.Span> activeSpans =
            new java.util.concurrent.ConcurrentHashMap<>();

        static @Nullable OtelDelegate create(@NonNull OtelConfig config) {
            try {
                // Loading OtelDelegate triggers resolution of OTel classes.
                // In native image, if OTel is absent, this throws NoClassDefFoundError.
                return new OtelDelegate(config);
            } catch (NoClassDefFoundError e) {
                return null;
            }
        }

        private OtelDelegate(@NonNull OtelConfig config) {
            io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter spanExporter =
                io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter.builder()
                    .setEndpoint(config.endpoint())
                    .build();

            for (java.util.Map.Entry<String, String> header : config.headers().entrySet()) {
                spanExporter = spanExporter.toBuilder().addHeader(header.getKey(), header.getValue()).build();
            }

            io.opentelemetry.sdk.trace.SdkTracerProvider tracerProvider =
                io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                    .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.traceIdRatioBased(config.samplingRate()))
                    .addSpanProcessor(io.opentelemetry.sdk.trace.export.BatchSpanProcessor.builder(spanExporter).build())
                    .build();

            io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter metricExporter =
                io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter.builder()
                    .setEndpoint(config.endpoint())
                    .build();

            io.opentelemetry.sdk.metrics.SdkMeterProvider meterProvider =
                io.opentelemetry.sdk.metrics.SdkMeterProvider.builder()
                    .registerMetricReader(
                        io.opentelemetry.sdk.metrics.export.PeriodicMetricReader.builder(metricExporter).build())
                    .build();

            this.openTelemetry = io.opentelemetry.sdk.OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();

            this.tracer = openTelemetry.getTracer("com.chorus.engine.telemetry");
            this.meter = openTelemetry.getMeter("com.chorus.engine.telemetry");

            this.agentRunsCounter = meter.counterBuilder("chorus.agent.runs").build();
            this.llmTokensCounter = meter.counterBuilder("chorus.llm.tokens").build();
            this.llmLatencyHistogram = meter.histogramBuilder("chorus.llm.latency").ofLongs().build();
            this.toolCallsCounter = meter.counterBuilder("chorus.tool.calls").build();
            this.ragQueriesCounter = meter.counterBuilder("chorus.rag.queries").build();
        }

        void handle(@NonNull ChorusEvent event) {
            switch (event) {
                case AgentStartEvent e -> {
                    agentRunsCounter.add(1,
                        io.opentelemetry.api.common.Attributes.of(
                            io.opentelemetry.api.common.AttributeKey.stringKey("agent.id"), e.agentId(),
                            io.opentelemetry.api.common.AttributeKey.stringKey("model"), e.model()));

                    io.opentelemetry.api.trace.Span span = tracer.spanBuilder("agent.run")
                        .setAttribute("agent.id", e.agentId())
                        .setAttribute("model", e.model())
                        .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                        .startSpan();
                    activeSpans.put(e.runId(), span);
                }
                case AgentEndEvent e -> {
                    io.opentelemetry.api.trace.Span span = activeSpans.remove(e.runId());
                    if (span != null) {
                        span.setAttribute("input.tokens", e.tokens().inputTokens());
                        span.setAttribute("output.tokens", e.tokens().outputTokens());
                        span.setAttribute("latency.ms", e.latency().toMillis());
                        span.end();
                    }
                }
                case LlmCallEvent e -> {
                    llmTokensCounter.add(e.inputTokens() + e.outputTokens(),
                        io.opentelemetry.api.common.Attributes.of(
                            io.opentelemetry.api.common.AttributeKey.stringKey("provider"), e.provider(),
                            io.opentelemetry.api.common.AttributeKey.stringKey("model"), e.model()));
                    llmLatencyHistogram.record(e.latency().toMillis(),
                        io.opentelemetry.api.common.Attributes.of(
                            io.opentelemetry.api.common.AttributeKey.stringKey("provider"), e.provider(),
                            io.opentelemetry.api.common.AttributeKey.stringKey("model"), e.model()));
                }
                case ToolCallEvent e -> {
                    var attrBuilder = io.opentelemetry.api.common.Attributes.builder()
                        .put("agent.id", e.agentId())
                        .put("tool.name", e.toolName());
                    if (e.error() != null) {
                        attrBuilder.put("error.type", e.error().errorType());
                    }
                    toolCallsCounter.add(1, attrBuilder.build());
                }
                case RagQueryEvent e -> {
                    ragQueriesCounter.add(1,
                        io.opentelemetry.api.common.Attributes.of(
                            io.opentelemetry.api.common.AttributeKey.stringKey("run.id"), e.runId()));
                }
                case HandoffEvent e -> {
                    io.opentelemetry.api.trace.Span span = tracer.spanBuilder("handoff")
                        .setAttribute("from.agent", e.fromAgent())
                        .setAttribute("to.agent", e.toAgent())
                        .setAttribute("reason", e.reason())
                        .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                        .startSpan();
                    span.end();
                }
                case GuardrailEvent e -> {
                    io.opentelemetry.api.trace.Span span = tracer.spanBuilder("guardrail")
                        .setAttribute("guardrail.name", e.guardrailName())
                        .setAttribute("action", e.action().name())
                        .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                        .startSpan();
                    span.end();
                }
                case CircuitBreakerEvent e -> {
                    io.opentelemetry.api.trace.Span span = tracer.spanBuilder("circuit.breaker")
                        .setAttribute("agent.id", e.agentId())
                        .setAttribute("state", e.state().name())
                        .setAttribute("failure.count", e.failureCount())
                        .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                        .startSpan();
                    span.end();
                }
                default -> {
                    // CheckpointEvent and any future events are silently ignored for OTel spans
                }
            }
        }

        void close() {
            openTelemetry.getSdkTracerProvider().shutdown()
                .join(5, java.util.concurrent.TimeUnit.SECONDS);
            openTelemetry.getSdkMeterProvider().shutdown()
                .join(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
