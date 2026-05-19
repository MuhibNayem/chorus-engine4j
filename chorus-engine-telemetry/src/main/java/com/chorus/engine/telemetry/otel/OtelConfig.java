package com.chorus.engine.telemetry.otel;

import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the OpenTelemetry bridge.
 */
public record OtelConfig(
    @NonNull String endpoint,
    @NonNull Map<String, String> headers,
    double samplingRate
) {

    public OtelConfig {
        Objects.requireNonNull(endpoint, "endpoint cannot be null");
        headers = Map.copyOf(headers);
        if (samplingRate < 0.0 || samplingRate > 1.0) {
            throw new IllegalArgumentException("samplingRate must be between 0.0 and 1.0");
        }
    }

    public static @NonNull OtelConfig defaults() {
        return new OtelConfig("http://localhost:4317", Map.of(), 1.0);
    }
}
