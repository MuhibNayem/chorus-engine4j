package com.chorus.engine.llm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ChatResponseTest {

    @Test
    void construction_and_accessors() {
        ChatResponse response = new ChatResponse(
            "id-1",
            "gpt-4o",
            "openai",
            Message.assistant("Hello"),
            new TokenCount(10, 5, "o200k_base"),
            Duration.ofMillis(100),
            "stop",
            null,
            null,
            Map.of("key", "value")
        );

        assertThat(response.id()).isEqualTo("id-1");
        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.message().content()).isEqualTo("Hello");
        assertThat(response.tokenCount().inputTokens()).isEqualTo(10);
        assertThat(response.latency()).isEqualTo(Duration.ofMillis(100));
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.hasToolCalls()).isFalse();
        assertThat(response.reasoningContent()).isNull();
        assertThat(response.rawMetadata()).containsEntry("key", "value");
    }

    @Test
    void hasToolCalls_true_when_non_empty() {
        ChatResponse response = new ChatResponse(
            "id-2",
            "gpt-4o",
            "openai",
            Message.assistant(""),
            new TokenCount(1, 1, "t"),
            Duration.ZERO,
            "tool_calls",
            List.of(new ChatResponse.ToolCall("c1", "get_weather", Map.of("city", "Paris"))),
            null,
            Map.of()
        );

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
    }

    @Test
    void hasToolCalls_false_when_null() {
        ChatResponse response = new ChatResponse(
            "id-3",
            "gpt-4o",
            "openai",
            Message.assistant("Hi"),
            new TokenCount(1, 1, "t"),
            Duration.ZERO,
            null,
            null,
            null,
            Map.of()
        );

        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    void hasToolCalls_false_when_empty() {
        ChatResponse response = new ChatResponse(
            "id-4",
            "gpt-4o",
            "openai",
            Message.assistant("Hi"),
            new TokenCount(1, 1, "t"),
            Duration.ZERO,
            null,
            List.of(),
            null,
            Map.of()
        );

        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    void toolCall_record_construction() {
        ChatResponse.ToolCall tc = new ChatResponse.ToolCall("call_01", "search", Map.of("q", "java"));

        assertThat(tc.id()).isEqualTo("call_01");
        assertThat(tc.toolName()).isEqualTo("search");
        assertThat(tc.arguments()).containsEntry("q", "java");
    }

    @Test
    void null_finishReason_accepted() {
        assertThatCode(() -> new ChatResponse(
            "id", "m", "p", Message.assistant("x"), new TokenCount(1, 1, "t"),
            Duration.ZERO, null, null, null, Map.of()
        )).doesNotThrowAnyException();
    }

    @Test
    void null_toolCalls_accepted() {
        assertThatCode(() -> new ChatResponse(
            "id", "m", "p", Message.assistant("x"), new TokenCount(1, 1, "t"),
            Duration.ZERO, null, null, null, Map.of()
        )).doesNotThrowAnyException();
    }

    @Test
    void null_reasoningContent_accepted() {
        assertThatCode(() -> new ChatResponse(
            "id", "m", "p", Message.assistant("x"), new TokenCount(1, 1, "t"),
            Duration.ZERO, null, null, null, Map.of()
        )).doesNotThrowAnyException();
    }
}
