package com.chorus.engine.core.trace;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @Test
    void testCreateRoot() {
        TraceContext ctx = TraceContext.createRoot();
        assertThat(ctx.traceId()).isNotNull().hasSize(32);
        assertThat(ctx.parentId()).isNotNull().hasSize(16);
        assertThat(ctx.flags()).isEqualTo(0x01);
    }

    @Test
    void testW3CFormat() {
        TraceContext ctx = new TraceContext(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            0x01,
            Map.of("user-id", "12345")
        );

        String traceparent = ctx.toTraceparent();
        assertThat(traceparent).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        TraceContext parsed = TraceContext.fromTraceparent(traceparent);
        assertThat(parsed).isNotNull();
        assertThat(parsed.traceId()).isEqualTo(ctx.traceId());
        assertThat(parsed.parentId()).isEqualTo(ctx.parentId());
        assertThat(parsed.flags()).isEqualTo(ctx.flags());
    }

    @Test
    void testInjectExtract() {
        TraceContext original = TraceContext.createRoot();
        original = new TraceContext(original.traceId(), original.parentId(), original.flags(),
            Map.of("session-id", "abc-123", "user-id", "user-42"));

        Map<String, String> headers = new HashMap<>();
        original.inject(headers);

        assertThat(headers).containsKey("traceparent");
        assertThat(headers).containsKey("baggage");

        TraceContext extracted = TraceContext.extract(headers);
        assertThat(extracted).isNotNull();
        assertThat(extracted.traceId()).isEqualTo(original.traceId());
        assertThat(extracted.baggage()).containsEntry("session-id", "abc-123");
        assertThat(extracted.baggage()).containsEntry("user-id", "user-42");
    }

    @Test
    void testCreateChild() {
        TraceContext parent = TraceContext.createRoot();
        TraceContext child = parent.createChild("new-parent-id-1234");

        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.parentId()).isEqualTo("new-parent-id-1234");
        assertThat(child.baggage()).isEqualTo(parent.baggage());
    }

    @Test
    void testBaggageEncoding() {
        TraceContext ctx = new TraceContext("abc", "def", 1,
            Map.of("key", "value,with=special"));
        String baggage = ctx.toBaggageHeader();
        assertThat(baggage).contains("key=value%2Cwith%3Dspecial");

        Map<String, String> parsed = TraceContext.parseBaggage(baggage);
        assertThat(parsed).containsEntry("key", "value,with=special");
    }

    @Test
    void testNullHeaders() {
        assertThat(TraceContext.fromTraceparent(null)).isNull();
        assertThat(TraceContext.fromTraceparent("")).isNull();
        assertThat(TraceContext.extract(Map.of())).isNull();
    }
}
