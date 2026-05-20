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

You can implement tools programmatically or declare them using Spring Boot annotations.

### Option A: Declarative Method-Level Tools (Recommended for Spring Boot)

Declare a method inside a `@Agent` component as a tool using `@Tool` and `@ToolParam`. The framework handles schema generation and argument coercion:

```java
@Agent(name = "assistant")
@Component
public class MathAgent {

    @Tool(description = "Evaluates mathematical expressions")
    public double calculate(
        @ToolParam(description = "The math expression (e.g. 2 + 2)") String expression
    ) {
        // Evaluate expression...
        return result;
    }
}
```

### Option B: Class-Level Tool Scan (`@ChorusTool`)

Annotate a class implementing `com.chorus.engine.tools.Tool` with `@ChorusTool` to register it automatically:

```java
@ChorusTool("calculator")
@Component
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

### Option C: Programmatic Custom Tool

Implement the `com.chorus.engine.tools.Tool` interface manually:

```java
public class CalculatorTool implements Tool {
    @Override public String name() { return "calculator"; }
    @Override public String description() { return "Evaluate mathematical expressions"; }
    // Schema and execute methods...
}
```

## Registering Tools

### Declarative Mode (Spring Boot)
Any class annotated with `@ChorusTool` or containing `@Tool` inside an `@Agent` component is auto-discovered and registered in the Spring ApplicationContext's `ToolRegistry`.

### Programmatic Mode
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
