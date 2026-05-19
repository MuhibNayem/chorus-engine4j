package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void registerAndFind() {
        ToolRegistry registry = new ToolRegistry();
        Tool tool = dummyTool("dummy");
        registry.register(tool);

        assertThat(registry.find("dummy")).isSameAs(tool);
        assertThat(registry.find("missing")).isNull();
    }

    @Test
    void register_duplicateThrows() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("dummy"));
        assertThatThrownBy(() -> registry.register(dummyTool("dummy")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void discover_byNameOrDescription() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("alpha", "first tool"));
        registry.register(dummyTool("beta", "second tool"));

        assertThat(registry.discover("alpha")).hasSize(1);
        assertThat(registry.discover("beta")).hasSize(1);
        assertThat(registry.discover("tool")).hasSize(2);
    }

    @Test
    void execute_success() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("echo"));
        Result<ToolOutput, ToolError> result = registry.execute(
            "echo", Map.of(), CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().content()).isEqualTo("ok");
    }

    @Test
    void execute_notFound() {
        ToolRegistry registry = new ToolRegistry();
        Result<ToolOutput, ToolError> result = registry.execute(
            "missing", Map.of(), CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.NotFound.class);
    }

    @Test
    void execute_validationMissingRequired() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "req"; }
            @Override public String description() { return "requires x"; }
            @Override public Map<String, Object> parametersSchema() {
                return Map.of("type", "object", "required", List.of("x"));
            }
            @Override public Result<ToolOutput, ToolError> execute(Map<String, Object> args, CancellationToken token) {
                return Result.ok(ToolOutput.of("done"));
            }
        });

        Result<ToolOutput, ToolError> result = registry.execute(
            "req", Map.of(), CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.ValidationError.class);
        ToolError.ValidationError err = (ToolError.ValidationError) result.unwrapErr();
        assertThat(err.field()).isEqualTo("x");
    }

    @Test
    void allTools_returnsSnapshot() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("one"));
        registry.register(dummyTool("two"));

        assertThat(registry.allTools()).hasSize(2);
    }

    private Tool dummyTool(String name) {
        return dummyTool(name, "a tool");
    }

    private Tool dummyTool(String name, String description) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> parametersSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public Result<ToolOutput, ToolError> execute(Map<String, Object> args, CancellationToken token) {
                return Result.ok(ToolOutput.of("ok"));
            }
        };
    }
}
