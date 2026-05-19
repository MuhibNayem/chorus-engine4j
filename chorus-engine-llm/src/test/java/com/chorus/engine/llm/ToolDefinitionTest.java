package com.chorus.engine.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ToolDefinitionTest {

    @Test
    void of_factory_creates_tool_with_defaults() {
        ToolDefinition tool = ToolDefinition.of("get_weather", "Get weather info");

        assertThat(tool.name()).isEqualTo("get_weather");
        assertThat(tool.description()).isEqualTo("Get weather info");
        assertThat(tool.parametersSchema()).containsEntry("type", "object");
        assertThat(tool.returnsSchema()).isNull();
        assertThat(tool.required()).isTrue();
    }

    @Test
    void builder_creates_tool() {
        ToolDefinition tool = ToolDefinition.builder("search", "Search the web")
            .parametersSchema(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))))
            .returnsSchema(Map.of("type", "string"))
            .required(false)
            .build();

        assertThat(tool.name()).isEqualTo("search");
        assertThat(tool.description()).isEqualTo("Search the web");
        assertThat(tool.parametersSchema()).containsEntry("type", "object");
        assertThat(tool.returnsSchema()).containsEntry("type", "string");
        assertThat(tool.required()).isFalse();
    }

    @Test
    void valid_names_accepted() {
        assertThatCode(() -> ToolDefinition.of("a", "d")).doesNotThrowAnyException();
        assertThatCode(() -> ToolDefinition.of("A", "d")).doesNotThrowAnyException();
        assertThatCode(() -> ToolDefinition.of("tool_1", "d")).doesNotThrowAnyException();
        assertThatCode(() -> ToolDefinition.of("tool-1", "d")).doesNotThrowAnyException();
        assertThatCode(() -> ToolDefinition.of("a1_B2-c3", "d")).doesNotThrowAnyException();
    }

    @Test
    void invalid_name_empty() {
        assertThatThrownBy(() -> ToolDefinition.of("", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tool name must match");
    }

    @Test
    void invalid_name_with_spaces() {
        assertThatThrownBy(() -> ToolDefinition.of("tool name", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tool name must match");
    }

    @Test
    void invalid_name_with_special_chars() {
        assertThatThrownBy(() -> ToolDefinition.of("tool$name", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tool name must match");
    }

    @Test
    void null_name_rejected() {
        assertThatThrownBy(() -> new ToolDefinition(null, "desc", Map.of(), null, true))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_description_rejected() {
        assertThatThrownBy(() -> new ToolDefinition("name", null, Map.of(), null, true))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_parametersSchema_rejected() {
        assertThatThrownBy(() -> new ToolDefinition("name", "desc", null, null, true))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void description_stored_correctly_including_long() {
        String longDesc = "a".repeat(1000);
        ToolDefinition tool = ToolDefinition.of("name", longDesc);
        assertThat(tool.description()).hasSize(1000);
    }
}
