package com.chorus.engine.telemetry.logging;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON structured logger with MDC-style context.
 * <p>
 * Outputs JSON-formatted log lines via {@link java.util.logging}.
 * Thread-local context (runId, agentId, etc.) is automatically included
 * in every log entry.
 */
public final class StructuredLogger {

    private static final Logger FALLBACK_LOGGER = Logger.getLogger(StructuredLogger.class.getName());

    private final ThreadLocal<Map<String, String>> mdc = ThreadLocal.withInitial(HashMap::new);

    public void putContext(@NonNull String key, @NonNull String value) {
        mdc.get().put(
            Objects.requireNonNull(key, "key cannot be null"),
            Objects.requireNonNull(value, "value cannot be null")
        );
    }

    public void removeContext(@NonNull String key) {
        mdc.get().remove(key);
    }

    public void clearContext() {
        mdc.get().clear();
    }

    public @NonNull Map<String, String> contextSnapshot() {
        return Map.copyOf(mdc.get());
    }

    public void info(@NonNull String message) {
        log("INFO", message, Map.of());
    }

    public void info(@NonNull String message, @NonNull Map<String, Object> fields) {
        log("INFO", message, fields);
    }

    public void warn(@NonNull String message) {
        log("WARN", message, Map.of());
    }

    public void warn(@NonNull String message, @NonNull Map<String, Object> fields) {
        log("WARN", message, fields);
    }

    public void error(@NonNull String message) {
        log("ERROR", message, Map.of());
    }

    public void error(@NonNull String message, @NonNull Map<String, Object> fields) {
        log("ERROR", message, fields);
    }

    private void log(@NonNull String level, @NonNull String message, @NonNull Map<String, Object> fields) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("level", level);
        entry.put("message", message);
        entry.putAll(mdc.get());
        entry.putAll(fields);

        String json = toJson(entry);

        Level javaLevel = switch (level) {
            case "ERROR" -> Level.SEVERE;
            case "WARN" -> Level.WARNING;
            default -> Level.INFO;
        };
        FALLBACK_LOGGER.log(javaLevel, json);
    }

    private @NonNull String toJson(@NonNull Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":");
            Object value = e.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(escapeJson(value.toString())).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private @NonNull String escapeJson(@NonNull String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
