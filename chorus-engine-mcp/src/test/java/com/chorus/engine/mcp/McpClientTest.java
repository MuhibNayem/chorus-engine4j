package com.chorus.engine.mcp;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.protocol.McpError;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcError;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcResponse;
import com.chorus.engine.mcp.protocol.McpPrompt;
import com.chorus.engine.mcp.protocol.McpResource;
import com.chorus.engine.mcp.protocol.McpResult.CallToolResult;
import com.chorus.engine.mcp.protocol.McpResult.GetPromptResult;
import com.chorus.engine.mcp.protocol.McpResult.ReadResourceResult;
import com.chorus.engine.mcp.protocol.McpTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeTransport transport;
    private McpClient client;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        transport.start();
        client = new McpClient(transport, mapper);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void initialize_success() throws Exception {
        // Server side: respond to initialize
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("initialize")) {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("protocolVersion", "2024-11-05");
                    result.set("capabilities", mapper.createObjectNode());
                    ObjectNode serverInfo = mapper.createObjectNode();
                    serverInfo.put("name", "test-server");
                    serverInfo.put("version", "1.0.0");
                    result.set("serverInfo", serverInfo);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<com.fasterxml.jackson.databind.JsonNode, McpError> result = client.initialize();
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().path("protocolVersion").asText()).isEqualTo("2024-11-05");
    }

    @Test
    void listTools_success() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("tools/list")) {
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode tools = mapper.createArrayNode();
                    ObjectNode tool = mapper.createObjectNode();
                    tool.put("name", "read_file");
                    tool.put("description", "Read a file");
                    tool.set("inputSchema", mapper.createObjectNode());
                    tools.add(tool);
                    result.set("tools", tools);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<List<McpTool>, McpError> result = client.listTools();
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap()).hasSize(1);
        assertThat(result.unwrap().get(0).name()).isEqualTo("read_file");
    }

    @Test
    void callTool_success() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("tools/call")) {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("isError", false);
                    ArrayNode content = mapper.createArrayNode();
                    ObjectNode text = mapper.createObjectNode();
                    text.put("type", "text");
                    text.put("text", "hello world");
                    content.add(text);
                    result.set("content", content);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<CallToolResult, McpError> result = client.callTool("echo", Map.of("msg", "hello"));
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().isError()).isFalse();
    }

    @Test
    void listResources_success() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("resources/list")) {
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode resources = mapper.createArrayNode();
                    ObjectNode resource = mapper.createObjectNode();
                    resource.put("uri", "file:///tmp/test.txt");
                    resource.put("name", "test.txt");
                    resource.put("description", "A test file");
                    resource.put("mimeType", "text/plain");
                    resources.add(resource);
                    result.set("resources", resources);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<List<McpResource>, McpError> result = client.listResources();
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap()).hasSize(1);
        assertThat(result.unwrap().get(0).uri()).isEqualTo("file:///tmp/test.txt");
    }

    @Test
    void readResource_success() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("resources/read")) {
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode contents = mapper.createArrayNode();
                    ObjectNode content = mapper.createObjectNode();
                    content.put("uri", "file:///tmp/test.txt");
                    content.put("mimeType", "text/plain");
                    content.put("text", "hello");
                    contents.add(content);
                    result.set("contents", contents);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<ReadResourceResult, McpError> result = client.readResource("file:///tmp/test.txt");
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().contents()).hasSize(1);
    }

    @Test
    void listPrompts_success() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("prompts/list")) {
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode prompts = mapper.createArrayNode();
                    ObjectNode prompt = mapper.createObjectNode();
                    prompt.put("name", "summarize");
                    prompt.put("description", "Summarize text");
                    ArrayNode args = mapper.createArrayNode();
                    ObjectNode arg = mapper.createObjectNode();
                    arg.put("name", "text");
                    arg.put("required", true);
                    args.add(arg);
                    prompt.set("arguments", args);
                    prompts.add(prompt);
                    result.set("prompts", prompts);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<List<McpPrompt>, McpError> result = client.listPrompts();
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap()).hasSize(1);
        assertThat(result.unwrap().get(0).name()).isEqualTo("summarize");
    }

    @Test
    void getPrompt_success() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req && req.method().equals("prompts/get")) {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("description", "Summarize the given text");
                    ArrayNode messages = mapper.createArrayNode();
                    ObjectNode message = mapper.createObjectNode();
                    message.put("role", "user");
                    ObjectNode content = mapper.createObjectNode();
                    content.put("type", "text");
                    content.put("text", "Please summarize: hello world");
                    message.set("content", content);
                    messages.add(message);
                    result.set("messages", messages);
                    transport.inject(JsonRpcResponse.of(req.id(), result));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<GetPromptResult, McpError> result = client.getPrompt("summarize", Map.of("text", "hello world"));
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().messages()).hasSize(1);
    }

    @Test
    void requestReturnsError() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                var msg = transport.pollOutbound();
                if (msg instanceof JsonRpcRequest req) {
                    transport.inject(JsonRpcError.of(req.id(), -32601, "Method not found", null));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Result<List<McpTool>, McpError> result = client.listTools();
        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr().code()).isEqualTo(-32601);
    }
}
