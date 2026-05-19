package com.chorus.engine.mcp;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.mcp.adapter.ToolAdapter;
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcResponse;
import com.chorus.engine.mcp.protocol.McpTool;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolError;
import com.chorus.engine.tools.ToolOutput;
import com.chorus.engine.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ToolAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fromChorusTool_convertsCorrectly() {
        Tool chorusTool = new Tool() {
            @Override
            public String name() { return "read_file"; }

            @Override
            public String description() { return "Read a file"; }

            @Override
            public Map<String, Object> parametersSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Result<ToolOutput, ToolError> execute(Map<String, Object> args, CancellationToken token) {
                return Result.ok(ToolOutput.of("content"));
            }
        };

        McpTool mcpTool = ToolAdapter.fromChorusTool(chorusTool);
        assertThat(mcpTool.name()).isEqualTo("read_file");
        assertThat(mcpTool.description()).isEqualTo("Read a file");
        assertThat(mcpTool.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void toMcpToolRegistry_wrapsClientTools() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.start();
        McpClient client = new McpClient(transport, mapper);

        // Fake server response for tools/list
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("tools/list")) {
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode tools = mapper.createArrayNode();
                    ObjectNode tool = mapper.createObjectNode();
                    tool.put("name", "echo");
                    tool.put("description", "Echo");
                    ObjectNode schema = mapper.createObjectNode();
                    schema.put("type", "object");
                    tool.set("inputSchema", schema);
                    tools.add(tool);
                    result.set("tools", tools);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ToolRegistry registry = ToolAdapter.toMcpToolRegistry(client);
        assertThat(registry.allTools()).hasSize(1);

        Tool wrapped = registry.find("echo");
        assertThat(wrapped).isNotNull();
        assertThat(wrapped.name()).isEqualTo("echo");
        assertThat(wrapped.description()).isEqualTo("Echo");

        client.close();
    }
}
