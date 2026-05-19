package com.chorus.engine.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Chorus OpenTelemetry telemetry.
 * Wires OTLP tracing, Micrometer metrics, and the span processor when
 * {@code chorus.telemetry.enabled=true}.
 */
@AutoConfiguration
@EnableConfigurationProperties(TelemetryProperties.class)
@ConditionalOnProperty(prefix = "chorus.telemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(OpenTelemetry.class)
public class TelemetryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetry openTelemetry(TelemetryProperties properties) {
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), properties.getServiceName())));

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(properties.getOtlpEndpoint())
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .setResource(resource)
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();
    }

    @Bean
    @ConditionalOnMissingBean
    public Tracer chorusTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.chorus.engine.telemetry", "0.1.0");
    }

    @Bean
    @ConditionalOnMissingBean
    public Meter chorusMeter(OpenTelemetry openTelemetry) {
        return openTelemetry.getMeter("com.chorus.engine.telemetry");
    }

    @Bean
    @ConditionalOnMissingBean
    public ChorusTelemetry chorusTelemetry(Tracer tracer, Meter meter) {
        return new ChorusTelemetry(tracer, meter);
    }

    @Bean
    @ConditionalOnMissingBean
    public CostTracker costTracker(TelemetryProperties properties) {
        return new CostTracker(new CostTracker.Pricing(properties.getInputTokenPrice(), properties.getOutputTokenPrice()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentSpanProcessor agentSpanProcessor(ChorusTelemetry telemetry, CostTracker costTracker) {
        return new AgentSpanProcessor(telemetry, costTracker);
    }
}
