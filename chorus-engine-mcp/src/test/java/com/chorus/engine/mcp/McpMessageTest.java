package com.chorus.engine.mcp;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcError;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcNotification;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class McpMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializeRequest() throws Exception {
        JsonRpcRequest request = JsonRpcRequest.of(1, "initialize", Map.of("protocolVersion", "2024-11-05"));
        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"id\":1");
        assertThat(json).contains("\"method\":\"initialize\"");
    }

    @Test
    void deserializeRequest() throws Exception {
        String json = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
            """;

        JsonRpcMessage message = mapper.readValue(json, JsonRpcMessage.class);
        assertThat(message).isInstanceOf(JsonRpcRequest.class);

        JsonRpcRequest request = (JsonRpcRequest) message;
        assertThat(request.id()).isEqualTo(1);
        assertThat(request.method()).isEqualTo("initialize");
        assertThat(request.params()).containsEntry("protocolVersion", "2024-11-05");
    }

    @Test
    void serializeResponse() throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        JsonRpcResponse response = JsonRpcResponse.of(1, result);
        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"id\":1");
        assertThat(json).contains("\"protocolVersion\":\"2024-11-05\"");
    }

    @Test
    void deserializeResponse() throws Exception {
        String json = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}
            """;

        JsonRpcMessage message = mapper.readValue(json, JsonRpcMessage.class);
        assertThat(message).isInstanceOf(JsonRpcResponse.class);

        JsonRpcResponse response = (JsonRpcResponse) message;
        assertThat(response.id()).isEqualTo(1);
        assertThat(response.result()).isNotNull();
        assertThat(response.result().has("tools")).isTrue();
    }

    @Test
    void serializeNotification() throws Exception {
        JsonRpcNotification notification = JsonRpcNotification.of("notifications/initialized", null);
        String json = mapper.writeValueAsString(notification);

        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"method\":\"notifications/initialized\"");
        assertThat(json).doesNotContain("\"id\"");
    }

    @Test
    void deserializeNotification() throws Exception {
        String json = """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """;

        JsonRpcMessage message = mapper.readValue(json, JsonRpcMessage.class);
        assertThat(message).isInstanceOf(JsonRpcNotification.class);

        JsonRpcNotification notification = (JsonRpcNotification) message;
        assertThat(notification.method()).isEqualTo("notifications/initialized");
    }

    @Test
    void serializeError() throws Exception {
        JsonRpcError error = JsonRpcError.of(1, -32601, "Method not found", null);
        String json = mapper.writeValueAsString(error);

        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"id\":1");
        assertThat(json).contains("\"code\":-32601");
        assertThat(json).contains("\"message\":\"Method not found\"");
    }

    @Test
    void deserializeError() throws Exception {
        String json = """
            {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}
            """;

        JsonRpcMessage message = mapper.readValue(json, JsonRpcMessage.class);
        assertThat(message).isInstanceOf(JsonRpcError.class);

        JsonRpcError error = (JsonRpcError) message;
        assertThat(error.id()).isEqualTo(1);
        assertThat(error.error().code()).isEqualTo(-32601);
        assertThat(error.error().message()).isEqualTo("Method not found");
    }

    @Test
    void requestNullGuards() {
        assertThatThrownBy(() -> new JsonRpcRequest(null, 1, "m", null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JsonRpcRequest("2.0", null, "m", null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JsonRpcRequest("2.0", 1, null, null))
            .isInstanceOf(NullPointerException.class);
    }
}
