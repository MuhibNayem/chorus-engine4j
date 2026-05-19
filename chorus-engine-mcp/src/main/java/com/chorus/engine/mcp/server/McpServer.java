package com.chorus.engine.mcp.server;

import com.chorus.engine.mcp.protocol.McpError;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcError;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcNotification;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcResponse;
import com.chorus.engine.mcp.protocol.McpPrompt;
import com.chorus.engine.mcp.protocol.McpResource;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP server implementation.
 *
 * <p>Registers tools, resources, and prompts with handler functions, then
 * dispatches incoming JSON-RPC requests to the appropriate handler.
 * Runs a background thread to read from the transport.
 */
public final class McpServer {

    private final McpTransport transport;
    private final ServerCapabilities capabilities;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, RegisteredTool> tools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegisteredResource> resources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegisteredPrompt> prompts = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Thread> readerThread = new AtomicReference<>();
    private final AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

    public McpServer(@NonNull McpTransport transport, @NonNull ServerCapabilities capabilities) {
        this(transport, capabilities, new ObjectMapper());
    }

    public McpServer(@NonNull McpTransport transport, @NonNull ServerCapabilities capabilities, @NonNull ObjectMapper mapper) {
        this.transport = transport;
        this.capabilities = capabilities;
        this.mapper = mapper;
    }

    public void registerTool(@NonNull McpTool tool, @NonNull ToolHandler handler) {
        tools.put(tool.name(), new RegisteredTool(tool, handler));
    }

    public void registerResource(@NonNull McpResource resource, @NonNull ResourceHandler handler) {
        resources.put(resource.uri(), new RegisteredResource(resource, handler));
    }

    public void registerPrompt(@NonNull McpPrompt prompt, @NonNull PromptHandler handler) {
        prompts.put(prompt.name(), new RegisteredPrompt(prompt, handler));
    }

    /**
     * Start the server. Blocks until the transport is closed.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        transport.start();
        transport.receive().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(JsonRpcMessage message) {
                handleMessage(message);
            }

            @Override
            public void onError(Throwable throwable) {
                if (!closed.get()) {
                    // Log or propagate as needed
                }
            }

            @Override
            public void onComplete() {
                running.set(false);
            }
        });
    }

    /**
     * Start the server in a background thread. Returns immediately.
     */
    public void startInBackground() {
        Thread thread = Thread.ofVirtual().name("mcp-server").start(this::start);
        readerThread.set(thread);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Flow.Subscription sub = subscriptionRef.get();
        if (sub != null) {
            sub.cancel();
        }
        transport.close();
        running.set(false);
        Thread t = readerThread.get();
        if (t != null) {
            t.interrupt();
        }
    }

    private void handleMessage(JsonRpcMessage message) {
        switch (message) {
            case JsonRpcRequest request -> handleRequest(request);
            case JsonRpcNotification notification -> handleNotification(notification);
            case JsonRpcResponse response -> { /* Server shouldn't receive responses */ }
            case JsonRpcError error -> { /* Server shouldn't receive errors */ }
        }
    }

    private void handleRequest(JsonRpcRequest request) {
        JsonRpcMessage response;
        try {
            response = switch (request.method()) {
                case "initialize" -> handleInitialize(request);
                case "tools/list" -> handleToolsList(request);
                case "tools/call" -> handleToolsCall(request);
                case "resources/list" -> handleResourcesList(request);
                case "resources/read" -> handleResourcesRead(request);
                case "prompts/list" -> handlePromptsList(request);
                case "prompts/get" -> handlePromptsGet(request);
                default -> JsonRpcError.of(request.id(), -32601, "Method not found: " + request.method(), null);
            };
        } catch (Exception e) {
            response = JsonRpcError.of(request.id(), -32603, e.getMessage(), null);
        }
        transport.send(response);
    }

    private void handleNotification(JsonRpcNotification notification) {
        // No-op for now — could handle logging, cancellation, etc.
    }

    private JsonRpcMessage handleInitialize(JsonRpcRequest request) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.set("capabilities", mapper.valueToTree(capabilities.toMap()));
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", "chorus-mcp-server");
        serverInfo.put("version", "0.1.0");
        result.set("serverInfo", serverInfo);
        return JsonRpcResponse.of(request.id(), result);
    }

    private JsonRpcMessage handleToolsList(JsonRpcRequest request) {
        ArrayNode array = mapper.createArrayNode();
        for (var reg : tools.values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", reg.tool.name());
            node.put("description", reg.tool.description());
            node.set("inputSchema", mapper.valueToTree(reg.tool.inputSchema()));
            array.add(node);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", array);
        return JsonRpcResponse.of(request.id(), result);
    }

    @SuppressWarnings("unchecked")
    private JsonRpcMessage handleToolsCall(JsonRpcRequest request) {
        Map<String, Object> params = request.params();
        if (params == null) {
            return JsonRpcError.of(request.id(), -32602, "Missing params", null);
        }
        String name = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
        RegisteredTool reg = tools.get(name);
        if (reg == null) {
            return JsonRpcError.of(request.id(), -32602, "Tool not found: " + name, null);
        }
        try {
            CallToolResult callResult = reg.handler.handle(arguments);
            ObjectNode result = mapper.createObjectNode();
            result.set("content", mapper.valueToTree(callResult.content()));
            result.put("isError", callResult.isError());
            return JsonRpcResponse.of(request.id(), result);
        } catch (Exception e) {
            return JsonRpcError.of(request.id(), -32603, "Tool execution failed: " + e.getMessage(), null);
        }
    }

    private JsonRpcMessage handleResourcesList(JsonRpcRequest request) {
        ArrayNode array = mapper.createArrayNode();
        for (var reg : resources.values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("uri", reg.resource.uri());
            node.put("name", reg.resource.name());
            if (reg.resource.description() != null) {
                node.put("description", reg.resource.description());
            }
            if (reg.resource.mimeType() != null) {
                node.put("mimeType", reg.resource.mimeType());
            }
            array.add(node);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("resources", array);
        return JsonRpcResponse.of(request.id(), result);
    }

    private JsonRpcMessage handleResourcesRead(JsonRpcRequest request) {
        Map<String, Object> params = request.params();
        if (params == null) {
            return JsonRpcError.of(request.id(), -32602, "Missing params", null);
        }
        String uri = (String) params.get("uri");
        RegisteredResource reg = resources.get(uri);
        if (reg == null) {
            return JsonRpcError.of(request.id(), -32602, "Resource not found: " + uri, null);
        }
        try {
            ReadResourceResult readResult = reg.handler.handle(uri);
            ObjectNode result = mapper.createObjectNode();
            result.set("contents", mapper.valueToTree(readResult.contents()));
            return JsonRpcResponse.of(request.id(), result);
        } catch (Exception e) {
            return JsonRpcError.of(request.id(), -32603, "Resource read failed: " + e.getMessage(), null);
        }
    }

    private JsonRpcMessage handlePromptsList(JsonRpcRequest request) {
        ArrayNode array = mapper.createArrayNode();
        for (var reg : prompts.values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", reg.prompt.name());
            if (reg.prompt.description() != null) {
                node.put("description", reg.prompt.description());
            }
            ArrayNode args = mapper.createArrayNode();
            for (var arg : reg.prompt.arguments()) {
                ObjectNode argNode = mapper.createObjectNode();
                argNode.put("name", arg.name());
                if (arg.description() != null) {
                    argNode.put("description", arg.description());
                }
                argNode.put("required", arg.required());
                args.add(argNode);
            }
            node.set("arguments", args);
            array.add(node);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("prompts", array);
        return JsonRpcResponse.of(request.id(), result);
    }

    @SuppressWarnings("unchecked")
    private JsonRpcMessage handlePromptsGet(JsonRpcRequest request) {
        Map<String, Object> params = request.params();
        if (params == null) {
            return JsonRpcError.of(request.id(), -32602, "Missing params", null);
        }
        String name = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
        RegisteredPrompt reg = prompts.get(name);
        if (reg == null) {
            return JsonRpcError.of(request.id(), -32602, "Prompt not found: " + name, null);
        }
        try {
            GetPromptResult getResult = reg.handler.handle(arguments);
            ObjectNode result = mapper.createObjectNode();
            if (getResult.description() != null) {
                result.put("description", getResult.description());
            }
            result.set("messages", mapper.valueToTree(getResult.messages()));
            return JsonRpcResponse.of(request.id(), result);
        } catch (Exception e) {
            return JsonRpcError.of(request.id(), -32603, "Prompt get failed: " + e.getMessage(), null);
        }
    }

    @FunctionalInterface
    public interface ToolHandler {
        @NonNull CallToolResult handle(@NonNull Map<String, Object> arguments);
    }

    @FunctionalInterface
    public interface ResourceHandler {
        @NonNull ReadResourceResult handle(@NonNull String uri);
    }

    @FunctionalInterface
    public interface PromptHandler {
        @NonNull GetPromptResult handle(@NonNull Map<String, Object> arguments);
    }

    private record RegisteredTool(@NonNull McpTool tool, @NonNull ToolHandler handler) {}
    private record RegisteredResource(@NonNull McpResource resource, @NonNull ResourceHandler handler) {}
    private record RegisteredPrompt(@NonNull McpPrompt prompt, @NonNull PromptHandler handler) {}
}
