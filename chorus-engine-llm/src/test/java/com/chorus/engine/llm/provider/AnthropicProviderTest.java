package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AnthropicProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildRequestBody_basic() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.7)
            .maxTokens(4096)
            .build();

        String json = AnthropicProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("model").asText()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(root.path("stream").asBoolean()).isTrue();
        assertThat(root.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(root.path("messages")).hasSize(1);
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(root.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
    }

    @Test
    void buildRequestBody_withSystem() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                Message.system("You are helpful"),
                Message.user("Hello")
            ))
            .build();

        String json = AnthropicProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("system").asText()).isEqualTo("You are helpful");
        assertThat(root.path("messages")).hasSize(1);
    }

    @Test
    void buildRequestBody_withTools() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("What's the weather?")))
            .tools(List.of(ToolDefinition.builder("get_weather", "Get weather info")
                .parametersSchema(Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))
                .build()))
            .build();

        String json = AnthropicProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("tools")).hasSize(1);
        assertThat(root.path("tools").get(0).path("name").asText()).isEqualTo("get_weather");
        assertThat(root.path("tools").get(0).path("input_schema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void buildRequestBody_withStopSequence() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("Hello")))
            .stopSequence("STOP")
            .build();

        String json = AnthropicProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("stop_sequence").asText()).isEqualTo("STOP");
    }

    @Test
    void buildRequestBody_withProviderExtras() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(Message.user("Hello")))
            .providerExtras(Map.of("top_p", 0.9))
            .build();

        String json = AnthropicProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("top_p").asDouble()).isEqualTo(0.9);
    }

    @Test
    void parseResponse_basic() throws Exception {
        String json = """
            {
              "id": "msg_123",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "Hello there"}],
              "usage": {"input_tokens": 10, "output_tokens": 20},
              "stop_reason": "end_turn"
            }
            """;

        ChatResponse response = AnthropicProvider.parseResponse(json, "claude-3-5-sonnet", MAPPER);

        assertThat(response.id()).isEqualTo("msg_123");
        assertThat(response.message().content()).isEqualTo("Hello there");
        assertThat(response.tokenCount().inputTokens()).isEqualTo(10);
        assertThat(response.tokenCount().outputTokens()).isEqualTo(20);
        assertThat(response.finishReason()).isEqualTo("end_turn");
        assertThat(response.provider()).isEqualTo("anthropic");
    }

    @Test
    void parseResponse_withToolUse() throws Exception {
        String json = """
            {
              "id": "msg_456",
              "type": "message",
              "role": "assistant",
              "content": [
                {"type": "tool_use", "id": "tu_01", "name": "get_weather", "input": {"city": "Paris"}}
              ],
              "usage": {"input_tokens": 15, "output_tokens": 25}
            }
            """;

        ChatResponse response = AnthropicProvider.parseResponse(json, "claude-3-5-sonnet", MAPPER);

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("tu_01");
        assertThat(response.toolCalls().get(0).toolName()).isEqualTo("get_weather");
        assertThat(response.toolCalls().get(0).arguments()).containsEntry("city", "Paris");
    }

    @Test
    void parseResponse_withThinking() throws Exception {
        String json = """
            {
              "id": "msg_789",
              "type": "message",
              "role": "assistant",
              "content": [
                {"type": "thinking", "thinking": "Let me think...", "signature": "abc123"},
                {"type": "text", "text": "Final answer"}
              ],
              "usage": {"input_tokens": 5, "output_tokens": 10}
            }
            """;

        ChatResponse response = AnthropicProvider.parseResponse(json, "claude-3-7-sonnet", MAPPER);

        assertThat(response.message().content()).isEqualTo("Final answer");
        assertThat(response.reasoningContent()).isEqualTo("Let me think...");
    }

    @Test
    void parseSseEvents_textDelta() throws Exception {
        String data = """
            {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
            """;

        List<StreamEvent> events = AnthropicProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Token.class);
        assertThat(((StreamEvent.Token) events.get(0)).token()).isEqualTo("Hello");
    }

    @Test
    void parseSseEvents_thinkingDelta() throws Exception {
        String data = """
            {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Hmm..."}}
            """;

        List<StreamEvent> events = AnthropicProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(1);
        assertThat(((StreamEvent.Token) events.get(0)).reasoningContent()).isEqualTo("Hmm...");
    }

    @Test
    void parseSseEvents_messageDelta() throws Exception {
        String data = """
            {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}
            """;

        List<StreamEvent> events = AnthropicProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Finish.class);
        StreamEvent.Finish finish = (StreamEvent.Finish) events.get(0);
        assertThat(finish.finishReason()).isEqualTo("end_turn");
        assertThat(finish.completionTokens()).isEqualTo(42);
    }

    @Test
    void parseSseEvents_empty() throws Exception {
        assertThat(AnthropicProvider.parseSseEvents("", MAPPER)).isEmpty();
        assertThat(AnthropicProvider.parseSseEvents("[DONE]", MAPPER)).isEmpty();
    }
}
