package com.chorus.engine.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Chorus telemetry.
 */
@ConfigurationProperties(prefix = "chorus.telemetry")
public class TelemetryProperties {

    private boolean enabled = true;
    private String otlpEndpoint = "http://localhost:4317";
    private String serviceName = "chorus-engine";
    private double inputTokenPrice = 0.0;
    private double outputTokenPrice = 0.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOtlpEndpoint() {
        return otlpEndpoint;
    }

    public void setOtlpEndpoint(String otlpEndpoint) {
        this.otlpEndpoint = otlpEndpoint;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public double getInputTokenPrice() {
        return inputTokenPrice;
    }

    public void setInputTokenPrice(double inputTokenPrice) {
        this.inputTokenPrice = inputTokenPrice;
    }

    public double getOutputTokenPrice() {
        return outputTokenPrice;
    }

    public void setOutputTokenPrice(double outputTokenPrice) {
        this.outputTokenPrice = outputTokenPrice;
    }
}
