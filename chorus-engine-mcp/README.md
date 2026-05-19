# chorus-engine-mcp

Model Context Protocol (MCP) client and server implementation.

## Purpose

The `mcp` module implements the Model Context Protocol — an open standard for connecting AI assistants to data sources and tools. It supports both client mode (calling external MCP servers) and server mode (exposing Chorus Engine tools as an MCP server).

## Key APIs

| Class | Purpose |
|---|---|
| `McpClient` | JSON-RPC 2.0 client for calling MCP servers. Supports tool listing, tool invocation, resource reading, and prompt retrieval. |
| `McpServer` | JSON-RPC 2.0 server that exposes tools, resources, and prompts to MCP clients. |
| `McpTransport` | Transport abstraction: `HttpSseTransport` (HTTP + Server-Sent Events) and `StdioTransport` (subprocess stdin/stdout). |
| `McpMessage` | JSON-RPC message types: `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcNotification`, `JsonRpcError` with custom Jackson deserializer. |
| `McpResult` | Result types: `CallToolResult`, `ReadResourceResult`, `GetPromptResult` with sealed `Content` hierarchy. |
| `ToolAdapter` | Adapts Chorus Engine `Tool` instances to MCP tool definitions. |

## Transports

| Transport | Use Case |
|---|---|
| `HttpSseTransport` | Connect to remote MCP servers over HTTP with SSE for streaming |
| `StdioTransport` | Spawn local MCP servers as subprocesses (stdin/stdout JSON-RPC) |

## Client Usage

```java
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.transport.HttpSseTransport;

McpTransport transport = new HttpSseTransport(
    URI.create("http://localhost:3000/sse"),
    httpClient,
    objectMapper
);

McpClient client = new McpClient(transport);

// List available tools
List<ToolDefinition> tools = client.listTools();

// Call a tool
McpResult.CallToolResult result = client.callTool("weather", Map.of("city", "Tokyo"));
result.content().forEach(c -> System.out.println(c.type() + ": " + c));
```

## Server Usage

```java
import com.chorus.engine.mcp.server.McpServer;
import com.chorus.engine.mcp.server.ServerCapabilities;

ServerCapabilities capabilities = ServerCapabilities.builder()
    .tools(true)
    .resources(true)
    .prompts(false)
    .build();

McpServer server = new McpServer(transport, capabilities, objectMapper);
server.registerTool(new CalculatorTool());
server.registerResource("docs://api", "OpenAPI spec", "application/json");
server.start();
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-tools`
- Jackson

## Thread Safety

`McpClient` is thread-safe. `McpServer` handles requests concurrently using virtual threads.
