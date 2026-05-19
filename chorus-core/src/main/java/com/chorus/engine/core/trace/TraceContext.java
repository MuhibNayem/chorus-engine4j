package com.chorus.engine.core.trace;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * W3C Trace Context propagation for distributed tracing across agent boundaries.
 *
 * <p>Implements the W3C Trace Context specification:</p>
 * <ul>
 *   <li>{@code traceparent}: {@code version-traceid-parentid-flags}</li>
 *   <li>{@code tracestate}: vendor-specific trace state</li>
 * </ul>
 *
 * <p>2026 SOTA: Used by OpenTelemetry as the default propagator. Baggage API
 * carries business context (user ID, session ID) without modifying function signatures.</p>
 */
public class TraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";
    public static final String BAGGAGE_HEADER = "baggage";

    private final String traceId;
    private final String parentId;
    private final int flags;
    private final Map<String, String> baggage;

    public TraceContext(String traceId, String parentId, int flags, Map<String, String> baggage) {
        this.traceId = traceId;
        this.parentId = parentId;
        this.flags = flags;
        this.baggage = baggage != null ? Map.copyOf(baggage) : Map.of();
    }

    public String traceId() { return traceId; }
    public String parentId() { return parentId; }
    public int flags() { return flags; }
    public Map<String, String> baggage() { return baggage; }

    /**
     * Create a child context with a new parent ID.
     */
    public TraceContext createChild(String newParentId) {
        return new TraceContext(traceId, newParentId, flags, baggage);
    }

    /**
     * Serialize to W3C traceparent format.
     */
    public String toTraceparent() {
        return String.format("00-%s-%s-%02x", traceId, parentId, flags);
    }

    /**
     * Serialize baggage to W3C format.
     */
    public String toBaggageHeader() {
        return baggage.entrySet().stream()
            .map(e -> e.getKey() + "=" + encodeBaggageValue(e.getValue()))
            .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Parse from W3C traceparent string.
     */
    public static TraceContext fromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length != 4) {
            return null;
        }
        String traceId = parts[1];
        String parentId = parts[2];
        int flags = Integer.parseInt(parts[3], 16);
        return new TraceContext(traceId, parentId, flags, Map.of());
    }

    /**
     * Parse baggage from W3C baggage header.
     */
    public static Map<String, String> parseBaggage(String header) {
        if (header == null || header.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (String item : header.split(",")) {
            String[] kv = item.trim().split("=", 2);
            if (kv.length == 2) {
                result.put(kv[0].trim(), decodeBaggageValue(kv[1].trim()));
            }
        }
        return result;
    }

    /**
     * Generate a new root trace context.
     */
    public static TraceContext createRoot() {
        return new TraceContext(
            generateId(32),
            generateId(16),
            0x01, // sampled
            new HashMap<>()
        );
    }

    /**
     * Inject this context into HTTP headers.
     */
    public void inject(Map<String, String> headers) {
        headers.put(TRACEPARENT_HEADER, toTraceparent());
        if (!baggage.isEmpty()) {
            headers.put(BAGGAGE_HEADER, toBaggageHeader());
        }
    }

    /**
     * Extract trace context from HTTP headers.
     */
    public static TraceContext extract(Map<String, String> headers) {
        String traceparent = headers.get(TRACEPARENT_HEADER);
        if (traceparent == null) {
            // Try lowercase
            traceparent = headers.get(TRACEPARENT_HEADER.toLowerCase());
        }
        TraceContext ctx = fromTraceparent(traceparent);
        if (ctx == null) {
            return null;
        }
        String baggageHeader = headers.get(BAGGAGE_HEADER);
        if (baggageHeader == null) {
            baggageHeader = headers.get(BAGGAGE_HEADER.toLowerCase());
        }
        Map<String, String> baggage = parseBaggage(baggageHeader);
        return new TraceContext(ctx.traceId, ctx.parentId, ctx.flags, baggage);
    }

    private static String generateId(int length) {
        byte[] bytes = new byte[length / 2];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String encodeBaggageValue(String value) {
        // Simple encoding for baggage values
        return value.replace("%", "%25")
            .replace(",", "%2C")
            .replace("=", "%3D");
    }

    private static String decodeBaggageValue(String value) {
        return value.replace("%2C", ",")
            .replace("%3D", "=")
            .replace("%25", "%");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TraceContext that)) return false;
        return flags == that.flags &&
               Objects.equals(traceId, that.traceId) &&
               Objects.equals(parentId, that.parentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(traceId, parentId, flags);
    }

    @Override
    public String toString() {
        return toTraceparent();
    }
}
