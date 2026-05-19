package com.chorus.engine.mcp.adapter;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.protocol.McpError;
import com.chorus.engine.mcp.protocol.McpResult.CallToolResult;
import com.chorus.engine.mcp.protocol.McpTool;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolError;
import com.chorus.engine.tools.ToolOutput;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * Bridges between Chorus {@link Tool} / {@link ToolRegistry} and MCP primitives.
 */
public final class ToolAdapter {

    private ToolAdapter() {}

    /**
     * Convert a Chorus {@link Tool} to an {@link McpTool} definition.
     */
    public static @NonNull McpTool fromChorusTool(@NonNull Tool tool) {
        return new McpTool(
            tool.name(),
            tool.description(),
            tool.parametersSchema()
        );
    }

    /**
     * Wrap an {@link McpClient} as a Chorus {@link ToolRegistry}.
     * Every tool exposed by the MCP server becomes a Chorus {@link Tool}.
     */
    public static @NonNull ToolRegistry toMcpToolRegistry(@NonNull McpClient client) {
        ToolRegistry registry = new ToolRegistry();
        var toolsResult = client.listTools();
        if (toolsResult.isErr()) {
            return registry;
        }
        for (McpTool mcpTool : toolsResult.unwrap()) {
            registry.register(new McpToolAdapter(mcpTool, client));
        }
        return registry;
    }

    /**
     * Adapts a single MCP tool to the Chorus {@link Tool} interface.
     */
    private static final class McpToolAdapter implements Tool {

        private final McpTool mcpTool;
        private final McpClient client;

        McpToolAdapter(@NonNull McpTool mcpTool, @NonNull McpClient client) {
            this.mcpTool = mcpTool;
            this.client = client;
        }

        @Override
        public @NonNull String name() {
            return mcpTool.name();
        }

        @Override
        public @NonNull String description() {
            return mcpTool.description();
        }

        @Override
        public @NonNull Map<String, Object> parametersSchema() {
            return mcpTool.inputSchema();
        }

        @Override
        public @NonNull Result<ToolOutput, ToolError> execute(@NonNull Map<String, Object> args, @NonNull CancellationToken token) {
            Result<CallToolResult, McpError> result = client.callTool(mcpTool.name(), args);
            return result.map(
                callResult -> ToolOutput.of(callResult.content().isEmpty() ? "" : callResult.content().get(0).toString())
            ).mapErr(
                mcpError -> new ToolError.ExecutionError(mcpTool.name(), mcpError.message(), mcpError.code())
            );
        }
    }
}
