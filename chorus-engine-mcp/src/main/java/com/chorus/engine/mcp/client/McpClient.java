package com.chorus.engine.mcp.client;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.mcp.protocol.McpError;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcResponse;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcError;
import com.chorus.engine.mcp.protocol.McpPrompt;
import com.chorus.engine.mcp.protocol.McpResource;
import com.chorus.engine.mcp.protocol.McpResult;
import com.chorus.engine.mcp.protocol.McpResult.CallToolResult;
import com.chorus.engine.mcp.protocol.McpResult.GetPromptResult;
import com.chorus.engine.mcp.protocol.McpResult.ReadResourceResult;
import com.chorus.engine.mcp.protocol.McpTool;
import com.chorus.engine.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

/**
 * MCP client implementation.
 *
 * <p>Manages the JSON-RPC lifecycle: initialize handshake, request/response
 * correlation, and graceful shutdown. Thread-safe.
 */
public final class McpClient {

    private final McpTransport transport;
    private final ObjectMapper mapper;
    private final AtomicInteger idGenerator = new AtomicInteger(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonRpcMessage>> pending = new ConcurrentHashMap<>();
    private final Flow.Subscription receiveSubscription;
    private volatile boolean initialized = false;

    public McpClient(@NonNull McpTransport transport) {
        this(transport, new ObjectMapper());
    }

    public McpClient(@NonNull McpTransport transport, @NonNull ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
        this.transport.start();

        CompletableFuture<Flow.Subscription> subFuture = new CompletableFuture<>();
        this.transport.receive().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subFuture.complete(subscription);
            }

            @Override
            public void onNext(JsonRpcMessage message) {
                handleIncoming(message);
            }

            @Override
            public void onError(Throwable throwable) {
                for (var entry : pending.entrySet()) {
                    entry.getValue().completeExceptionally(throwable);
                }
                pending.clear();
            }

            @Override
            public void onComplete() {
                for (var entry : pending.entrySet()) {
                    entry.getValue().completeExceptionally(new IllegalStateException("Transport closed"));
                }
                pending.clear();
            }
        });
        try {
            this.receiveSubscription = subFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(McpError.transportError("Failed to subscribe to transport", e).toString(), e);
        }
    }

    /**
     * Perform the initialize handshake.
     */
    public @NonNull Result<JsonNode, McpError> initialize() {
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "chorus");
        clientInfo.put("version", "0.1.0");

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", mapper.createObjectNode());
        params.set("clientInfo", clientInfo);

        Result<JsonNode, McpError> result = request("initialize", params);
        if (result.isOk()) {
            initialized = true;
        }
        return result;
    }

    public @NonNull Result<List<McpTool>, McpError> listTools() {
        return request("tools/list", null)
            .map(node -> {
                JsonNode toolsNode = node.get("tools");
                if (toolsNode == null || !toolsNode.isArray()) {
                    return List.<McpTool>of();
                }
                ArrayNode array = (ArrayNode) toolsNode;
                return StreamSupport.stream(array.spliterator(), false)
                    .map(this::parseTool)
                    .toList();
            });
    }

    public @NonNull Result<CallToolResult, McpError> callTool(@NonNull String name, @NonNull Map<String, Object> args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(args));
        return request("tools/call", params)
            .map(this::parseCallToolResult);
    }

    public @NonNull Result<List<McpResource>, McpError> listResources() {
        return request("resources/list", null)
            .map(node -> {
                JsonNode resourcesNode = node.get("resources");
                if (resourcesNode == null || !resourcesNode.isArray()) {
                    return List.<McpResource>of();
                }
                ArrayNode array = (ArrayNode) resourcesNode;
                return StreamSupport.stream(array.spliterator(), false)
                    .map(this::parseResource)
                    .toList();
            });
    }

    public @NonNull Result<ReadResourceResult, McpError> readResource(@NonNull String uri) {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        return request("resources/read", params)
            .map(this::parseReadResourceResult);
    }

    public @NonNull Result<List<McpPrompt>, McpError> listPrompts() {
        return request("prompts/list", null)
            .map(node -> {
                JsonNode promptsNode = node.get("prompts");
                if (promptsNode == null || !promptsNode.isArray()) {
                    return List.<McpPrompt>of();
                }
                ArrayNode array = (ArrayNode) promptsNode;
                return StreamSupport.stream(array.spliterator(), false)
                    .map(this::parsePrompt)
                    .toList();
            });
    }

    public @NonNull Result<GetPromptResult, McpError> getPrompt(@NonNull String name, @NonNull Map<String, Object> args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(args));
        return request("prompts/get", params)
            .map(this::parseGetPromptResult);
    }

    public void close() {
        receiveSubscription.cancel();
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException("Connection closed"));
        }
        pending.clear();
        transport.close();
    }

    private void handleIncoming(JsonRpcMessage message) {
        Object rawId = extractId(message);
        if (rawId != null) {
            String id = rawId.toString();
            CompletableFuture<JsonRpcMessage> future = pending.remove(id);
            if (future != null) {
                future.complete(message);
            }
        }
    }

    private @Nullable Object extractId(JsonRpcMessage message) {
        return switch (message) {
            case JsonRpcResponse r -> r.id();
            case JsonRpcError e -> e.id();
            case JsonRpcRequest r -> null;
            case com.chorus.engine.mcp.protocol.McpMessage.JsonRpcNotification n -> null;
        };
    }

    private @NonNull Result<JsonNode, McpError> request(@NonNull String method, @Nullable JsonNode paramsNode) {
        int id = idGenerator.getAndIncrement();
        String idKey = String.valueOf(id);
        Map<String, Object> params = paramsNode != null ? mapper.convertValue(paramsNode, Map.class) : null;
        JsonRpcRequest request = JsonRpcRequest.of(id, method, params);
        CompletableFuture<JsonRpcMessage> future = new CompletableFuture<>();
        pending.put(idKey, future);
        try {
            transport.send(request);
        } catch (Exception e) {
            pending.remove(idKey);
            return Result.err(McpError.transportError("Failed to send request", e));
        }
        try {
            JsonRpcMessage response = future.get(30, TimeUnit.SECONDS);
            return switch (response) {
                case JsonRpcResponse r -> {
                    if (r.result() == null) {
                        yield Result.err(McpError.invalidRequest("Empty result"));
                    }
                    yield Result.ok(r.result());
                }
                case JsonRpcError e -> Result.err(new McpError(e.error().code(), e.error().message(), null));
                default -> Result.err(McpError.invalidRequest("Unexpected message type"));
            };
        } catch (TimeoutException e) {
            pending.remove(idKey);
            return Result.err(McpError.transportError("Request timed out"));
        } catch (Exception e) {
            pending.remove(idKey);
            return Result.err(McpError.internalError("Request failed", e));
        }
    }

    private McpTool parseTool(JsonNode node) {
        return new McpTool(
            node.path("name").asText(""),
            node.path("description").asText(""),
            mapper.convertValue(node.path("inputSchema"), Map.class)
        );
    }

    private CallToolResult parseCallToolResult(JsonNode node) {
        boolean isError = node.path("isError").asBoolean(false);
        List<McpResult.Content> contents = node.has("content")
            ? mapper.convertValue(node.get("content"), List.class)
            : List.of();
        return new CallToolResult(contents, isError);
    }

    private McpResource parseResource(JsonNode node) {
        return new McpResource(
            node.path("uri").asText(""),
            node.path("name").asText(""),
            node.path("description").asText(null),
            node.path("mimeType").asText(null)
        );
    }

    private ReadResourceResult parseReadResourceResult(JsonNode node) {
        List<McpResult.ResourceContent> contents = node.has("contents")
            ? mapper.convertValue(node.get("contents"), List.class)
            : List.of();
        return new ReadResourceResult(contents);
    }

    private McpPrompt parsePrompt(JsonNode node) {
        List<McpPrompt.McpPromptArgument> args = List.of();
        if (node.has("arguments") && node.get("arguments").isArray()) {
            args = mapper.convertValue(node.get("arguments"), List.class);
        }
        return new McpPrompt(
            node.path("name").asText(""),
            node.path("description").asText(null),
            args
        );
    }

    private GetPromptResult parseGetPromptResult(JsonNode node) {
        String description = node.path("description").asText(null);
        List<McpResult.PromptMessage> messages = node.has("messages")
            ? mapper.convertValue(node.get("messages"), List.class)
            : List.of();
        return new GetPromptResult(description, messages);
    }
}
