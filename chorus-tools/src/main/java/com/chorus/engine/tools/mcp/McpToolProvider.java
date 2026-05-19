package com.chorus.engine.tools.mcp;

import com.chorus.engine.core.tool.AgentTool;
import com.chorus.engine.tools.McpAgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * MCP tool provider that discovers and manages tools from MCP servers.
 * Works with Spring AI 1.1.5's {@link SyncMcpToolCallbackProvider}.
 */
public class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    private final List<SyncMcpToolCallbackProvider> providers = new CopyOnWriteArrayList<>();

    public void addProvider(SyncMcpToolCallbackProvider provider) {
        providers.add(provider);
        log.info("Added MCP tool provider. Total providers: {}", providers.size());
    }

    /**
     * Discover all available tools from registered MCP providers.
     */
    public List<AgentTool> discoverTools() {
        return providers.stream()
            .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
            .map(this::adapt)
            .collect(Collectors.toList());
    }

    /**
     * Get tool by name across all providers.
     */
    public AgentTool getTool(String name) {
        for (SyncMcpToolCallbackProvider provider : providers) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                if (callback.getToolDefinition().name().equals(name)) {
                    return adapt(callback);
                }
            }
        }
        return null;
    }

    private AgentTool adapt(ToolCallback callback) {
        return new McpAgentTool(callback);
    }

    public int providerCount() {
        return providers.size();
    }
}
