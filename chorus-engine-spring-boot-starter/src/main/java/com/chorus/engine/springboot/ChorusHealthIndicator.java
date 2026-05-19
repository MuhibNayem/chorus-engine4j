package com.chorus.engine.springboot;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.rag.store.VectorStore;
import com.chorus.engine.telemetry.event.EventBus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enterprise health indicator for Chorus Engine.
 *
 * <p>Checks every wired subsystem and reports granular status:
 * <ul>
 *   <li>LLM provider reachability</li>
 *   <li>Vector store connectivity</li>
 *   <li>Circuit breaker state</li>
 *   <li>MCP transport (if enabled)</li>
 *   <li>Event bus (if enabled)</li>
 * </ul>
 */
@Component
public class ChorusHealthIndicator implements HealthIndicator {

    private final LlmClient llmClient;
    private final VectorStore vectorStore;
    private final CircuitBreaker circuitBreaker;
    private final McpClient mcpClient;
    private final EventBus eventBus;

    public ChorusHealthIndicator(
        LlmClient llmClient,
        VectorStore vectorStore,
        CircuitBreaker circuitBreaker,
        org.springframework.beans.factory.ObjectProvider<McpClient> mcpClientProvider,
        org.springframework.beans.factory.ObjectProvider<EventBus> eventBusProvider
    ) {
        this.llmClient = llmClient;
        this.vectorStore = vectorStore;
        this.circuitBreaker = circuitBreaker;
        this.mcpClient = mcpClientProvider.getIfAvailable();
        this.eventBus = eventBusProvider.getIfAvailable();
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean allUp = true;

        // LLM
        if (llmClient != null) {
            LlmClient.HealthStatus status = llmClient.health();
            details.put("llm.provider", llmClient.providerName());
            details.put("llm.status", status.name());
            if (status == LlmClient.HealthStatus.UNAVAILABLE) {
                allUp = false;
            }
        } else {
            details.put("llm", "not_configured");
        }

        // Circuit Breaker
        if (circuitBreaker != null) {
            details.put("circuitBreaker.state", circuitBreaker.state().name());
            if (circuitBreaker.state() == CircuitBreaker.State.OPEN) {
                allUp = false;
            }
        }

        // Vector Store
        if (vectorStore != null) {
            try {
                long count = vectorStore.count();
                details.put("vectorStore.status", "UP");
                details.put("vectorStore.documents", count);
            } catch (Exception e) {
                details.put("vectorStore.status", "DOWN");
                details.put("vectorStore.error", e.getMessage());
                allUp = false;
            }
        }

        // MCP
        if (mcpClient != null) {
            details.put("mcp.status", "configured");
        } else {
            details.put("mcp.status", "not_configured");
        }

        // Event Bus
        if (eventBus != null) {
            details.put("eventBus.status", "configured");
        } else {
            details.put("eventBus.status", "not_configured");
        }

        if (allUp) {
            return Health.up().withDetails(details).build();
        }
        return Health.down().withDetails(details).build();
    }
}
