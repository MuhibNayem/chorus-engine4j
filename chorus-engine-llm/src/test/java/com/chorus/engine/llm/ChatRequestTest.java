package com.chorus.engine.llm;

import com.chorus.engine.core.context.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ChatRequestTest {

    @Test
    void record_construction_with_defaults() {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        assertThat(request.model()).isEqualTo("gpt-4o");
        assertThat(request.messages()).hasSize(1);
        assertThat(request.tools()).isEmpty();
        assertThat(request.temperature()).isEqualTo(0.7);
        assertThat(request.maxTokens()).isEqualTo(4096);
        assertThat(request.responseFormat()).isNull();
        assertThat(request.stopSequence()).isNull();
        assertThat(request.providerExtras()).isNull();
    }

    @Test
    void boundary_temperature_zero() {
        assertThatCode(() -> new ChatRequest("m", List.of(), List.of(), 0.0, 1, null, null, null))
            .doesNotThrowAnyException();
    }

    @Test
    void boundary_temperature_two() {
        assertThatCode(() -> new ChatRequest("m", List.of(), List.of(), 2.0, 1, null, null, null))
            .doesNotThrowAnyException();
    }

    @Test
    void boundary_maxTokens_one() {
        assertThatCode(() -> new ChatRequest("m", List.of(), List.of(), 0.5, 1, null, null, null))
            .doesNotThrowAnyException();
    }

    @Test
    void invalid_temperature_negative() {
        assertThatThrownBy(() -> new ChatRequest("m", List.of(), List.of(), -0.1, 1, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("temperature");
    }

    @Test
    void invalid_temperature_above_two() {
        assertThatThrownBy(() -> new ChatRequest("m", List.of(), List.of(), 2.1, 1, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("temperature");
    }

    @Test
    void invalid_maxTokens_zero() {
        assertThatThrownBy(() -> new ChatRequest("m", List.of(), List.of(), 0.5, 0, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxTokens");
    }

    @Test
    void withMessages_replaces_messages() {
        ChatRequest original = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        ChatRequest updated = original.withMessages(List.of(Message.assistant("Hi")));

        assertThat(updated.messages()).hasSize(1);
        assertThat(updated.messages().get(0).role()).isEqualTo(com.chorus.engine.core.context.Role.ASSISTANT);
        assertThat(updated.model()).isEqualTo(original.model());
        assertThat(updated.tools()).isEqualTo(original.tools());
    }

    @Test
    void withTools_replaces_tools() {
        ToolDefinition tool = ToolDefinition.of("get_weather", "Get weather");
        ChatRequest original = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .build();

        ChatRequest updated = original.withTools(List.of(tool));

        assertThat(updated.tools()).hasSize(1);
        assertThat(updated.messages()).isEqualTo(original.messages());
    }

    @Test
    void null_model_rejected() {
        assertThatThrownBy(() -> ChatRequest.builder().model(null).build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_messages_rejected() {
        assertThatThrownBy(() -> ChatRequest.builder().messages(null).build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_tools_rejected() {
        assertThatThrownBy(() -> ChatRequest.builder().tools(null).build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_message_element_rejected() {
        assertThatThrownBy(() -> new ChatRequest("m", List.of((Message) null), List.of(), 0.5, 1, null, null, null))
            .isInstanceOf(NullPointerException.class);
    }
}
