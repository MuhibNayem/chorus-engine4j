package com.chorus.engine.springboot;

import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.protocol.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for MCP (Model Context Protocol) operations.
 * Only available when an McpClient bean is configured.
 */
@RestController
@RequestMapping("/api/mcp")
@ConditionalOnBean(McpClient.class)
public class McpController {

    private final McpClient mcpClient;

    public McpController(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    @GetMapping("/tools")
    public ResponseEntity<List<ToolDto>> listTools() {
        var result = mcpClient.listTools();
        if (result.isErr()) {
            return ResponseEntity.internalServerError().build();
        }
        List<ToolDto> tools = result.unwrap().stream()
            .map(t -> new ToolDto(t.name(), t.description(), t.inputSchema()))
            .toList();
        return ResponseEntity.ok(tools);
    }

    @PostMapping("/tools/{toolName}/call")
    public ResponseEntity<ToolCallResponse> callTool(
        @PathVariable String toolName,
        @RequestBody Map<String, Object> args
    ) {
        var result = mcpClient.callTool(toolName, args);
        if (result.isErr()) {
            return ResponseEntity.internalServerError().body(
                new ToolCallResponse(toolName, null, result.unwrapErr().message())
            );
        }
        var callResult = result.unwrap();
        return ResponseEntity.ok(new ToolCallResponse(
            toolName, callResult.content(), null
        ));
    }

    @GetMapping("/resources")
    public ResponseEntity<List<ResourceDto>> listResources() {
        var result = mcpClient.listResources();
        if (result.isErr()) {
            return ResponseEntity.internalServerError().build();
        }
        List<ResourceDto> resources = result.unwrap().stream()
            .map(r -> new ResourceDto(r.uri(), r.name(), r.mimeType()))
            .toList();
        return ResponseEntity.ok(resources);
    }

    @GetMapping("/prompts")
    public ResponseEntity<List<PromptDto>> listPrompts() {
        var result = mcpClient.listPrompts();
        if (result.isErr()) {
            return ResponseEntity.internalServerError().build();
        }
        List<PromptDto> prompts = result.unwrap().stream()
            .map(p -> new PromptDto(p.name(), p.description()))
            .toList();
        return ResponseEntity.ok(prompts);
    }

    public record ToolDto(String name, String description, Map<String, Object> inputSchema) {}
    public record ToolCallResponse(String toolName, List<?> content, String error) {}
    public record ResourceDto(String uri, String name, String mimeType) {}
    public record PromptDto(String name, String description) {}
}
