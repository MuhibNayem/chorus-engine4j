package com.chorus.engine.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.time.Instant;

/**
 * Production-grade OpenTelemetry facade for the Chorus agent framework.
 * Creates spans and metrics for agent rounds, tool calls, graph nodes,
 * LLM invocations, and HITL gate pauses.
 */
public class ChorusTelemetry {

    private final Tracer tracer;
    private final Meter meter;

    private final LongCounter toolCallCounter;
    private final LongCounter llmCallCounter;
    private final LongCounter graphNodeCounter;
    private final LongCounter hitlCounter;

    public static final AttributeKey<String> CHORUS_ROUND = AttributeKey.stringKey("chorus.agent.round");
    public static final AttributeKey<String> CHORUS_THREAD_ID = AttributeKey.stringKey("chorus.agent.thread_id");
    public static final AttributeKey<String> CHORUS_MODEL = AttributeKey.stringKey("chorus.llm.model");
    public static final AttributeKey<Long> CHORUS_INPUT_TOKENS = AttributeKey.longKey("chorus.llm.input_tokens");
    public static final AttributeKey<Long> CHORUS_OUTPUT_TOKENS = AttributeKey.longKey("chorus.llm.output_tokens");
    public static final AttributeKey<String> CHORUS_TOOL_NAME = AttributeKey.stringKey("chorus.tool.name");
    public static final AttributeKey<Long> CHORUS_DURATION_MS = AttributeKey.longKey("chorus.duration_ms");
    public static final AttributeKey<Boolean> CHORUS_SUCCESS = AttributeKey.booleanKey("chorus.success");
    public static final AttributeKey<String> CHORUS_NODE_NAME = AttributeKey.stringKey("chorus.graph.node_name");
    public static final AttributeKey<String> CHORUS_RESUME_KEY = AttributeKey.stringKey("chorus.hitl.resume_key");
    public static final AttributeKey<Long> CHORUS_LATENCY_MS = AttributeKey.longKey("chorus.llm.latency_ms");

    public ChorusTelemetry(Tracer tracer, Meter meter) {
        this.tracer = tracer;
        this.meter = meter;
        this.toolCallCounter = meter.counterBuilder("chorus.tool.calls").build();
        this.llmCallCounter = meter.counterBuilder("chorus.llm.calls").build();
        this.graphNodeCounter = meter.counterBuilder("chorus.graph.nodes").build();
        this.hitlCounter = meter.counterBuilder("chorus.hitl.pauses").build();
    }

    /**
     * Start a span for an agent round.
     */
    public Span startAgentRound(int round, String threadId, String model) {
        var builder = tracer.spanBuilder("agent.round")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(CHORUS_ROUND, Integer.toString(round))
            .setAttribute(CHORUS_THREAD_ID, threadId);
        if (model != null) {
            builder.setAttribute(CHORUS_MODEL, model);
        }
        return builder.startSpan();
    }

    /**
     * End an agent round span, recording token usage.
     */
    public void endAgentRound(Span span, int inputTokens, int outputTokens) {
        if (inputTokens >= 0) {
            span.setAttribute(CHORUS_INPUT_TOKENS, (long) inputTokens);
        }
        if (outputTokens >= 0) {
            span.setAttribute(CHORUS_OUTPUT_TOKENS, (long) outputTokens);
        }
        span.end();
    }

    /**
     * Start a span for a tool call.
     */
    public Span startToolCall(String toolName) {
        toolCallCounter.add(1);
        return tracer.spanBuilder("tool.call")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(CHORUS_TOOL_NAME, toolName)
            .startSpan();
    }

    /**
     * End a tool call span, recording outcome.
     */
    public void endToolCall(Span span, boolean success) {
        span.setAttribute(CHORUS_SUCCESS, success);
        if (!success) {
            span.setStatus(StatusCode.ERROR, "tool execution failed");
        }
        span.end();
    }

    /**
     * Record a graph node execution as a completed span.
     * The span is back-dated so its duration matches the reported value.
     */
    public void recordGraphNode(String nodeName, String threadId, long durationMs) {
        graphNodeCounter.add(1);
        Instant now = Instant.now();
        Instant start = now.minusMillis(durationMs);
        Span span = tracer.spanBuilder("graph.node")
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(start)
            .setAttribute(CHORUS_NODE_NAME, nodeName)
            .setAttribute(CHORUS_THREAD_ID, threadId)
            .setAttribute(CHORUS_DURATION_MS, durationMs)
            .startSpan();
        span.end(now);
    }

    /**
     * Start a span for an LLM call.
     */
    public Span startLlmCall(String model) {
        llmCallCounter.add(1);
        return tracer.spanBuilder("llm.call")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(CHORUS_MODEL, model)
            .startSpan();
    }

    /**
     * End an LLM call span, recording latency and token usage.
     */
    public void endLlmCall(Span span, long latencyMs, int inputTokens, int outputTokens) {
        span.setAttribute(CHORUS_LATENCY_MS, latencyMs);
        if (inputTokens >= 0) {
            span.setAttribute(CHORUS_INPUT_TOKENS, (long) inputTokens);
        }
        if (outputTokens >= 0) {
            span.setAttribute(CHORUS_OUTPUT_TOKENS, (long) outputTokens);
        }
        span.end();
    }

    /**
     * Start a span for an HITL gate pause.
     */
    public Span startHitlGate(String resumeKey) {
        hitlCounter.add(1);
        return tracer.spanBuilder("hitl.gate")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(CHORUS_RESUME_KEY, resumeKey)
            .startSpan();
    }

    /**
     * End an HITL gate span, recording total pause duration.
     */
    public void endHitlGate(Span span, long durationMs) {
        span.setAttribute(CHORUS_DURATION_MS, durationMs);
        span.end();
    }
}
