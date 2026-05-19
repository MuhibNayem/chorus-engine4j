package com.chorus.engine.springboot;

import com.chorus.engine.llm.LlmClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator health indicator for Chorus Engine.
 */
@Component
public class ChorusHealthIndicator implements HealthIndicator {

    private final LlmClient llmClient;

    public ChorusHealthIndicator(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public Health health() {
        if (llmClient == null) {
            return Health.down()
                .withDetail("llm", "No LLM client configured")
                .build();
        }

        LlmClient.HealthStatus status = llmClient.health();
        return switch (status) {
            case HEALTHY -> Health.up()
                .withDetail("provider", llmClient.providerName())
                .withDetail("status", status.name())
                .build();
            case DEGRADED -> Health.status("DEGRADED")
                .withDetail("provider", llmClient.providerName())
                .withDetail("status", status.name())
                .build();
            case UNAVAILABLE -> Health.down()
                .withDetail("provider", llmClient.providerName())
                .withDetail("status", status.name())
                .build();
        };
    }
}
