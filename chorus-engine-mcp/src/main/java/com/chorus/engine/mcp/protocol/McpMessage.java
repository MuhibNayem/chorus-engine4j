package com.chorus.engine.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * JSON-RPC 2.0 message types used by the Model Context Protocol.
 *
 * <p>A custom Jackson deserializer inspects the JSON structure to decide
 * whether an incoming object is a request, response, notification, or error.
 */
public final class McpMessage {

    private McpMessage() {}

    public static final String JSONRPC_VERSION = "2.0";

    @JsonDeserialize(using = JsonRpcMessageDeserializer.class)
    public sealed interface JsonRpcMessage permits JsonRpcRequest, JsonRpcResponse, JsonRpcNotification, JsonRpcError {
        @NonNull String jsonrpc();
    }

    /**
     * JSON-RPC request object.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcRequest(
        @NonNull String jsonrpc,
        @NonNull Object id,
        @NonNull String method,
        @Nullable Map<String, Object> params
    ) implements JsonRpcMessage {
        public JsonRpcRequest {
            Objects.requireNonNull(jsonrpc, "jsonrpc cannot be null");
            Objects.requireNonNull(id, "id cannot be null");
            Objects.requireNonNull(method, "method cannot be null");
        }

        public static @NonNull JsonRpcRequest of(@NonNull Object id, @NonNull String method, @Nullable Map<String, Object> params) {
            return new JsonRpcRequest(JSONRPC_VERSION, id, method, params);
        }
    }

    /**
     * JSON-RPC response object (success branch).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcResponse(
        @NonNull String jsonrpc,
        @NonNull Object id,
        @Nullable JsonNode result
    ) implements JsonRpcMessage {
        public JsonRpcResponse {
            Objects.requireNonNull(jsonrpc, "jsonrpc cannot be null");
            Objects.requireNonNull(id, "id cannot be null");
        }

        public static @NonNull JsonRpcResponse of(@NonNull Object id, @Nullable JsonNode result) {
            return new JsonRpcResponse(JSONRPC_VERSION, id, result);
        }
    }

    /**
     * JSON-RPC notification (request without id).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcNotification(
        @NonNull String jsonrpc,
        @NonNull String method,
        @Nullable Map<String, Object> params
    ) implements JsonRpcMessage {
        public JsonRpcNotification {
            Objects.requireNonNull(jsonrpc, "jsonrpc cannot be null");
            Objects.requireNonNull(method, "method cannot be null");
        }

        public static @NonNull JsonRpcNotification of(@NonNull String method, @Nullable Map<String, Object> params) {
            return new JsonRpcNotification(JSONRPC_VERSION, method, params);
        }
    }

    /**
     * JSON-RPC error object.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcError(
        @NonNull String jsonrpc,
        @NonNull Object id,
        @NonNull ErrorDetail error
    ) implements JsonRpcMessage {
        public JsonRpcError {
            Objects.requireNonNull(jsonrpc, "jsonrpc cannot be null");
            Objects.requireNonNull(id, "id cannot be null");
            Objects.requireNonNull(error, "error cannot be null");
        }

        public static @NonNull JsonRpcError of(@NonNull Object id, int code, @NonNull String message, @Nullable JsonNode data) {
            return new JsonRpcError(JSONRPC_VERSION, id, new ErrorDetail(code, message, data));
        }

        public record ErrorDetail(int code, @NonNull String message, @Nullable JsonNode data) {
            public ErrorDetail {
                Objects.requireNonNull(message, "message cannot be null");
            }
        }
    }

    /**
     * Jackson deserializer that routes JSON-RPC messages to the correct concrete type
     * based on structural inspection.
     */
    static final class JsonRpcMessageDeserializer extends StdDeserializer<JsonRpcMessage> {

        JsonRpcMessageDeserializer() {
            super(JsonRpcMessage.class);
        }

        @Override
        public JsonRpcMessage deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode tree = p.getCodec().readTree(p);
            if (tree == null || !tree.isObject()) {
                throw new IOException("Invalid JSON-RPC message: not an object");
            }

            String jsonrpc = tree.path("jsonrpc").asText("");
            if (!JSONRPC_VERSION.equals(jsonrpc)) {
                throw new IOException("Invalid JSON-RPC version: " + jsonrpc);
            }

            JsonNode idNode = tree.get("id");
            JsonNode errorNode = tree.get("error");
            JsonNode resultNode = tree.get("result");
            JsonNode methodNode = tree.get("method");

            if (errorNode != null && errorNode.isObject()) {
                int code = errorNode.path("code").asInt(0);
                String message = errorNode.path("message").asText("");
                JsonNode data = errorNode.get("data");
                Object id = parseId(idNode);
                return JsonRpcError.of(id, code, message, data);
            }

            if (resultNode != null) {
                Object id = parseId(idNode);
                return JsonRpcResponse.of(id, resultNode);
            }

            if (methodNode != null && methodNode.isTextual()) {
                String method = methodNode.asText();
                JsonNode paramsNode = tree.get("params");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = paramsNode != null
                    ? p.getCodec().treeToValue(paramsNode, Map.class)
                    : null;
                if (idNode != null) {
                    Object id = parseId(idNode);
                    return JsonRpcRequest.of(id, method, params);
                }
                return JsonRpcNotification.of(method, params);
            }

            throw new IOException("Unrecognized JSON-RPC message structure");
        }

        private static Object parseId(JsonNode idNode) throws IOException {
            if (idNode == null) {
                throw new IOException("Missing id in JSON-RPC message");
            }
            if (idNode.isNumber()) {
                return idNode.asInt();
            }
            if (idNode.isTextual()) {
                return idNode.asText();
            }
            throw new IOException("Unsupported id type: " + idNode.getNodeType());
        }
    }
}
