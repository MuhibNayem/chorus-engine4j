# chorus-engine-tools

Tool registry, schema generation, and built-in tool implementations.

## Purpose

The `tools` module provides the infrastructure for LLM-invokable tools: automatic JSON Schema generation from Java methods, a thread-safe registry, and built-in tools for common operations.

## Key APIs

| Class / Interface | Purpose |
|---|---|
| `Tool` | Interface for all tools. Implement `name()`, `description()`, `parametersSchema()`, and `execute(Map<String, Object>)`. |
| `ToolRegistry` | Register, lookup, and list tools. Thread-safe. Supports tool grouping and filtering. |
| `ToolDefinition` | Schema-driven tool definition consumed by LLM clients. Auto-generated from `Tool` implementations. |
| `ShellTool` | Execute shell commands. ⚠️ No sandbox — runs on host OS. |
| `FilesystemTool` | Read/write files. ⚠️ No sandbox — direct filesystem access. |

## Built-in Tools

| Tool | Description |
|---|---|
| `ShellTool` | Execute shell commands with timeout and output capture |
| `FilesystemTool` | Read, write, list, and delete files |

## Writing a Custom Tool

```java
import com.chorus.engine.tools.Tool;
import java.util.Map;

public class CalculatorTool implements Tool {
    @Override public String name() { return "calculator"; }
    @Override public String description() { return "Evaluate mathematical expressions"; }

    @Override public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of("expression", Map.of("type", "string", "description", "Math expression")),
            "required", List.of("expression")
        );
    }

    @Override public Object execute(Map<String, Object> args) {
        String expr = args.get("expression").toString();
        // Evaluate expression...
        return result;
    }
}
```

## Registering Tools

```java
ToolRegistry registry = new ToolRegistry();
registry.register(new CalculatorTool());
registry.register(new ShellTool());
registry.register(new FilesystemTool());

// LLM sees all registered tools
ChatRequest request = ChatRequest.builder()
    .tools(registry.definitions())
    .build();
```

## Security Warning

`ShellTool` and `FilesystemTool` run directly on the host OS without sandboxing. In production, wrap these with:
- Container isolation (Docker)
- Permission restrictions (SELinux, AppArmor)
- Path whitelisting
- Command allowlists

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`

## Thread Safety

`ToolRegistry` is thread-safe. Tool implementations should be stateless or thread-safe.
