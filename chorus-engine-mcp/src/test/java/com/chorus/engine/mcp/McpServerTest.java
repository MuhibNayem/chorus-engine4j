package com.chorus.engine.mcp;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcResponse;
import com.chorus.engine.mcp.protocol.McpPrompt;
import com.chorus.engine.mcp.protocol.McpResource;
import com.chorus.engine.mcp.protocol.McpResult.CallToolResult;
import com.chorus.engine.mcp.protocol.McpResult.GetPromptResult;
import com.chorus.engine.mcp.protocol.McpResult.ReadResourceResult;
import com.chorus.engine.mcp.protocol.McpTool;
import com.chorus.engine.mcp.server.McpServer;
import com.chorus.engine.mcp.server.ServerCapabilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeTransport transport;
    private McpServer server;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        transport.start();
        server = new McpServer(transport, ServerCapabilities.none(), mapper);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void initialize_handshake() throws Exception {
        server.start();

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "test");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);

        transport.inject(JsonRpcRequest.of(1, "initialize", mapper.convertValue(params, Map.class)));

        JsonRpcMessage response = transport.pollOutbound();
        assertThat(response).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse resp = (JsonRpcResponse) response;
        assertThat(resp.id()).isEqualTo(1);
        assertThat(resp.result().path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(resp.result().path("serverInfo").path("name").asText()).isEqualTo("chorus-mcp-server");
    }

    @Test
    void toolsListAndCall() throws Exception {
        server.registerTool(
            new McpTool("echo", "Echo tool", Map.of("type", "object")),
            args -> CallToolResult.text("echo: " + args.get("msg"))
        );
        server.start();

        transport.inject(JsonRpcRequest.of(1, "tools/list", null));
        JsonRpcMessage response = transport.pollOutbound();
        assertThat(response).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse listResp = (JsonRpcResponse) response;
        assertThat(listResp.result().path("tools").size()).isEqualTo(1);

        Map<String, Object> params = Map.of("name", "echo", "arguments", Map.of("msg", "hello"));
        transport.inject(JsonRpcRequest.of(2, "tools/call", params));
        JsonRpcMessage callResponse = transport.pollOutbound();
        assertThat(callResponse).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse callResp = (JsonRpcResponse) callResponse;
        assertThat(callResp.result().path("isError").asBoolean()).isFalse();
    }

    @Test
    void resourcesListAndRead() throws Exception {
        server.registerResource(
            new McpResource("file:///tmp/test.txt", "test.txt", "A test file", "text/plain"),
            uri -> new ReadResourceResult(List.of(new com.chorus.engine.mcp.protocol.McpResult.ResourceContent(uri, "text/plain", "hello", null)))
        );
        server.start();

        transport.inject(JsonRpcRequest.of(1, "resources/list", null));
        JsonRpcMessage response = transport.pollOutbound();
        assertThat(response).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse listResp = (JsonRpcResponse) response;
        assertThat(listResp.result().path("resources").size()).isEqualTo(1);

        Map<String, Object> params = Map.of("uri", "file:///tmp/test.txt");
        transport.inject(JsonRpcRequest.of(2, "resources/read", params));
        JsonRpcMessage readResponse = transport.pollOutbound();
        assertThat(readResponse).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse readResp = (JsonRpcResponse) readResponse;
        assertThat(readResp.result().path("contents").size()).isEqualTo(1);
    }

    @Test
    void promptsListAndGet() throws Exception {
        server.registerPrompt(
            new McpPrompt("greet", "Greeting prompt", List.of()),
            args -> new GetPromptResult("A greeting", List.of())
        );
        server.start();

        transport.inject(JsonRpcRequest.of(1, "prompts/list", null));
        JsonRpcMessage response = transport.pollOutbound();
        assertThat(response).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse listResp = (JsonRpcResponse) response;
        assertThat(listResp.result().path("prompts").size()).isEqualTo(1);

        Map<String, Object> params = Map.of("name", "greet", "arguments", Map.of());
        transport.inject(JsonRpcRequest.of(2, "prompts/get", params));
        JsonRpcMessage getResponse = transport.pollOutbound();
        assertThat(getResponse).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse getResp = (JsonRpcResponse) getResponse;
        assertThat(getResp.result().path("description").asText()).isEqualTo("A greeting");
    }

    @Test
    void methodNotFound() throws Exception {
        server.start();

        transport.inject(JsonRpcRequest.of(1, "unknown/method", null));
        JsonRpcMessage response = transport.pollOutbound();
        assertThat(response).isInstanceOf(com.chorus.engine.mcp.protocol.McpMessage.JsonRpcError.class);

        com.chorus.engine.mcp.protocol.McpMessage.JsonRpcError error =
            (com.chorus.engine.mcp.protocol.McpMessage.JsonRpcError) response;
        assertThat(error.error().code()).isEqualTo(-32601);
    }
}
