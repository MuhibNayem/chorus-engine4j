package com.chorus.engine.telemetry.otel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OtelConfigTest {

    @Test
    void defaultsFactory() {
        OtelConfig config = OtelConfig.defaults();

        assertThat(config.endpoint()).isEqualTo("http://localhost:4317");
        assertThat(config.headers()).isEmpty();
        assertThat(config.samplingRate()).isEqualTo(1.0);
    }

    @Test
    void samplingRateAtZeroBoundary() {
        OtelConfig config = new OtelConfig("http://localhost:4317", Map.of(), 0.0);
        assertThat(config.samplingRate()).isEqualTo(0.0);
    }

    @Test
    void samplingRateAtOneBoundary() {
        OtelConfig config = new OtelConfig("http://localhost:4317", Map.of(), 1.0);
        assertThat(config.samplingRate()).isEqualTo(1.0);
    }

    @Test
    void invalidSamplingRateNegativeThrows() {
        assertThatThrownBy(() -> new OtelConfig("http://localhost:4317", Map.of(), -0.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("samplingRate must be between 0.0 and 1.0");
    }

    @Test
    void invalidSamplingRateAboveOneThrows() {
        assertThatThrownBy(() -> new OtelConfig("http://localhost:4317", Map.of(), 1.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("samplingRate must be between 0.0 and 1.0");
    }

    @Test
    void nullEndpointRejection() {
        assertThatThrownBy(() -> new OtelConfig(null, Map.of(), 0.5))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("endpoint cannot be null");
    }

    @Test
    void nullHeadersRejection() {
        assertThatThrownBy(() -> new OtelConfig("http://localhost:4317", null, 0.5))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void headersAreImmutableCopy() {
        Map<String, String> mutable = new java.util.HashMap<>();
        mutable.put("x-key", "value");

        OtelConfig config = new OtelConfig("http://localhost:4317", mutable, 0.5);
        mutable.put("x-extra", "extra");

        assertThat(config.headers()).hasSize(1).containsEntry("x-key", "value");
    }
}
