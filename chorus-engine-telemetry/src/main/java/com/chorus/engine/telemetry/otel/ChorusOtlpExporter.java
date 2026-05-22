package com.chorus.engine.telemetry.otel;

import com.chorus.engine.telemetry.event.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exports {@link ChorusEvent} instances as OTLP spans with full GenAI semantic conventions.
 * <p>
 * This exporter is designed specifically for Chorus Observe and enriches spans with:
 * <ul>
 *   <li>Standard OTel GenAI attributes (gen_ai.system, gen_ai.request.model, etc.)</li>
 *   <li>Chorus-specific extension attributes (chorus.run_id, chorus.framework, etc.)</li>
 *   <li>Optional provenance chain and checkpoint IDs</li>
 * </ul>
 * <p>
 * OTel classes are {@code compileOnly}; this class uses an inner delegate that is only
 * loaded when OTel is present on the classpath. If absent, the exporter is a no-op.
 */
public final class ChorusOtlpExporter implements AutoCloseable {

    private final EventBus eventBus;
    private final Config config;
    private final @Nullable Delegate delegate;

    public ChorusOtlpExporter(@NonNull EventBus eventBus, @NonNull Config config) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
        this.delegate = Delegate.create(config);
        if (delegate != null) {
            eventBus.subscribe("*", this::onEvent);
        }
    }

    public boolean isActive() {
        return delegate != null;
    }

    @Override
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
     * Configuration for the Chorus OTLP exporter.
     */
    public record Config(
        @NonNull String endpoint,
        @NonNull Map<String, String> headers,
        double sampleRate,
        boolean exportProvenance,
        @NonNull String framework
    ) {
        public Config {
            Objects.requireNonNull(endpoint, "endpoint");
            headers = Map.copyOf(headers);
            if (sampleRate < 0.0 || sampleRate > 1.0) {
                throw new IllegalArgumentException("sampleRate must be between 0.0 and 1.0");
            }
            if (framework == null || framework.isBlank()) {
                framework = "chorus";
            }
        }

        public static @NonNull Config defaults() {
            return new Config("http://localhost:4317", Map.of(), 1.0, true, "chorus");
        }
    }

    /**
     * Inner delegate that references OTel APIs directly.
     */
    private static final class Delegate {

        private final io.opentelemetry.sdk.OpenTelemetrySdk openTelemetry;
        private final io.opentelemetry.api.trace.Tracer tracer;
        private final io.opentelemetry.api.metrics.Meter meter;
        private final Config config;

        private final io.opentelemetry.api.metrics.LongCounter tokenCounter;
        private final io.opentelemetry.api.metrics.LongHistogram latencyHistogram;
        private final io.opentelemetry.api.metrics.LongCounter toolCounter;
        private final io.opentelemetry.api.metrics.DoubleCounter costCounter;

        private final ConcurrentHashMap<String, io.opentelemetry.api.trace.Span> activeRuns = new ConcurrentHashMap<>();

        static @Nullable Delegate create(@NonNull Config config) {
            try {
                return new Delegate(config);
            } catch (NoClassDefFoundError e) {
                return null;
            }
        }

        private Delegate(@NonNull Config config) {
            this.config = config;

            io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter spanExporter =
                io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter.builder()
                    .setEndpoint(config.endpoint())
                    .build();

            for (Map.Entry<String, String> header : config.headers().entrySet()) {
                spanExporter = spanExporter.toBuilder().addHeader(header.getKey(), header.getValue()).build();
            }

            io.opentelemetry.sdk.trace.SdkTracerProvider tracerProvider =
                io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                    .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.traceIdRatioBased(config.sampleRate()))
                    .addSpanProcessor(io.opentelemetry.sdk.trace.export.BatchSpanProcessor.builder(spanExporter).build())
                    .build();

            this.openTelemetry = io.opentelemetry.sdk.OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

            this.tracer = openTelemetry.getTracer("com.chorus.engine.telemetry");
            this.meter = openTelemetry.getMeter("com.chorus.engine.telemetry");

            this.tokenCounter = meter.counterBuilder("gen_ai.client.token.usage").build();
            this.latencyHistogram = meter.histogramBuilder("gen_ai.client.operation.duration").ofLongs().build();
            this.toolCounter = meter.counterBuilder("chorus.tool.calls").build();
            this.costCounter = meter.counterBuilder("chorus.cost.usd").ofDoubles().build();
        }

        void handle(@NonNull ChorusEvent event) {
            switch (event) {
                case AgentStartEvent e -> startRunSpan(e);
                case AgentEndEvent e -> endRunSpan(e);
                case LlmCallEvent e -> recordLlmCall(e);
                case ToolCallEvent e -> recordToolCall(e);
                case GuardrailEvent e -> recordGuardrail(e);
                case HandoffEvent e -> recordHandoff(e);
                case CheckpointEvent e -> recordCheckpoint(e);
                case CircuitBreakerEvent e -> recordCircuitBreaker(e);
                case RagQueryEvent e -> recordRagQuery(e);
            }
        }

        private void startRunSpan(@NonNull AgentStartEvent e) {
            var builder = tracer.spanBuilder("gen_ai.agent.run")
                .setAttribute("gen_ai.agent.id", e.agentId())
                .setAttribute("gen_ai.request.model", e.model())
                .setAttribute("gen_ai.system", config.framework())
                .setAttribute("chorus.run_id", e.runId())
                .setAttribute("chorus.framework", config.framework())
                .setAttribute("chorus.event_type", e.eventType())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL);

            if (config.exportProvenance()) {
                builder.setAttribute("chorus.provenance.enabled", true);
            }

            io.opentelemetry.api.trace.Span span = builder.startSpan();
            activeRuns.put(e.runId(), span);
        }

        private void endRunSpan(@NonNull AgentEndEvent e) {
            io.opentelemetry.api.trace.Span span = activeRuns.remove(e.runId());
            if (span != null) {
                var tokens = e.tokens();
                if (tokens != null) {
                    span.setAttribute("gen_ai.usage.input_tokens", tokens.inputTokens());
                    span.setAttribute("gen_ai.usage.output_tokens", tokens.outputTokens());
                }
                span.setAttribute("chorus.latency_ms", e.latency().toMillis());
                span.end();
            }
        }

        private void recordLlmCall(@NonNull LlmCallEvent e) {
            io.opentelemetry.api.trace.Span parent = activeRuns.get(e.runId());
            var builder = tracer.spanBuilder("gen_ai.chat")
                .setAttribute("gen_ai.system", e.provider())
                .setAttribute("gen_ai.request.model", e.model())
                .setAttribute("gen_ai.usage.input_tokens", e.inputTokens())
                .setAttribute("gen_ai.usage.output_tokens", e.outputTokens())
                .setAttribute("chorus.run_id", e.runId())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.CLIENT);

            if (parent != null) {
                builder.setParent(io.opentelemetry.context.Context.current().with(parent));
            }

            io.opentelemetry.api.trace.Span span = builder.startSpan();
            span.end();

            tokenCounter.add(e.inputTokens() + e.outputTokens(),
                io.opentelemetry.api.common.Attributes.of(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.system"), e.provider(),
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.model"), e.model()));

            latencyHistogram.record(e.latency().toMillis(),
                io.opentelemetry.api.common.Attributes.of(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.system"), e.provider(),
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.model"), e.model()));

            costCounter.add(0.0,
                io.opentelemetry.api.common.Attributes.of(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.system"), e.provider()));
        }

        private void recordToolCall(@NonNull ToolCallEvent e) {
            io.opentelemetry.api.trace.Span parent = activeRuns.get(e.runId());
            var builder = tracer.spanBuilder("gen_ai.tool.use")
                .setAttribute("gen_ai.tool.name", e.toolName())
                .setAttribute("gen_ai.agent.id", e.agentId())
                .setAttribute("chorus.run_id", e.runId())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL);

            if (e.error() != null) {
                builder.setAttribute("error.type", e.error().errorType());
                builder.setAttribute("error.message", e.error().errorMessage());
            }

            if (parent != null) {
                builder.setParent(io.opentelemetry.context.Context.current().with(parent));
            }

            io.opentelemetry.api.trace.Span span = builder.startSpan();
            span.end();

            toolCounter.add(1,
                io.opentelemetry.api.common.Attributes.of(
                    io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.name"), e.toolName()));
        }

        private void recordGuardrail(@NonNull GuardrailEvent e) {
            io.opentelemetry.api.trace.Span parent = activeRuns.get(e.runId());
            var builder = tracer.spanBuilder("chorus.guardrail")
                .setAttribute("guardrail.name", e.guardrailName())
                .setAttribute("guardrail.action", e.action().name())
                .setAttribute("chorus.run_id", e.runId())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL);

            if (parent != null) {
                builder.setParent(io.opentelemetry.context.Context.current().with(parent));
            }

            io.opentelemetry.api.trace.Span span = builder.startSpan();
            span.end();
        }

        private void recordHandoff(@NonNull HandoffEvent e) {
            io.opentelemetry.api.trace.Span span = tracer.spanBuilder("chorus.handoff")
                .setAttribute("from.agent", e.fromAgent())
                .setAttribute("to.agent", e.toAgent())
                .setAttribute("handoff.reason", e.reason())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
            span.end();
        }

        private void recordCheckpoint(@NonNull CheckpointEvent e) {
            io.opentelemetry.api.trace.Span parent = activeRuns.get(e.runId());
            var builder = tracer.spanBuilder("chorus.checkpoint")
                .setAttribute("checkpoint.id", e.checkpointId())
                .setAttribute("chorus.run_id", e.runId())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL);

            if (config.exportProvenance()) {
                builder.setAttribute("chorus.checkpoint_id", e.checkpointId());
            }

            if (parent != null) {
                builder.setParent(io.opentelemetry.context.Context.current().with(parent));
            }

            io.opentelemetry.api.trace.Span span = builder.startSpan();
            span.end();
        }

        private void recordCircuitBreaker(@NonNull CircuitBreakerEvent e) {
            io.opentelemetry.api.trace.Span span = tracer.spanBuilder("chorus.circuit_breaker")
                .setAttribute("circuit_breaker.state", e.state().name())
                .setAttribute("circuit_breaker.failure_count", e.failureCount())
                .setAttribute("chorus.run_id", e.runId())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
            span.end();
        }

        private void recordRagQuery(@NonNull RagQueryEvent e) {
            io.opentelemetry.api.trace.Span parent = activeRuns.get(e.runId());
            var builder = tracer.spanBuilder("chorus.rag.query")
                .setAttribute("chorus.run_id", e.runId())
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL);

            if (parent != null) {
                builder.setParent(io.opentelemetry.context.Context.current().with(parent));
            }

            io.opentelemetry.api.trace.Span span = builder.startSpan();
            span.end();
        }

        void close() {
            openTelemetry.getSdkTracerProvider().shutdown()
                .join(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
