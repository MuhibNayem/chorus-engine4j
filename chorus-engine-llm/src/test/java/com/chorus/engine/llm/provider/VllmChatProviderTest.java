package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class VllmChatProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildRequestBody_basic() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("meta-llama/Llama-3-8B-Instruct")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.7)
            .maxTokens(4096)
            .build();

        String json = VllmChatProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("model").asText()).isEqualTo("meta-llama/Llama-3-8B-Instruct");
        assertThat(root.path("stream").asBoolean()).isTrue();
        assertThat(root.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(root.path("messages")).hasSize(1);
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(root.path("messages").get(0).path("content").asText()).isEqualTo("Hello");
    }

    @Test
    void buildRequestBody_withTools() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("meta-llama/Llama-3-8B-Instruct")
            .messages(List.of(Message.user("What's the weather?")))
            .tools(List.of(ToolDefinition.builder("get_weather", "Get weather info")
                .parametersSchema(Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))
                .build()))
            .build();

        String json = VllmChatProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("tools")).hasSize(1);
        assertThat(root.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(root.path("tools").get(0).path("function").path("name").asText()).isEqualTo("get_weather");
    }

    @Test
    void buildRequestBody_withResponseFormat() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("meta-llama/Llama-3-8B-Instruct")
            .messages(List.of(Message.user("Hello")))
            .responseFormat(ResponseFormat.json())
            .build();

        String json = VllmChatProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void buildRequestBody_withStopSequence() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("meta-llama/Llama-3-8B-Instruct")
            .messages(List.of(Message.user("Hello")))
            .stopSequence("STOP")
            .build();

        String json = VllmChatProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("stop").asText()).isEqualTo("STOP");
    }

    @Test
    void buildRequestBody_withProviderExtras() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("meta-llama/Llama-3-8B-Instruct")
            .messages(List.of(Message.user("Hello")))
            .providerExtras(Map.of("top_p", 0.9))
            .build();

        String json = VllmChatProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("top_p").asDouble()).isEqualTo(0.9);
    }

    @Test
    void parseResponse_basic() throws Exception {
        String json = """
            {
              "id": "chatcmpl-123",
              "choices": [
                {
                  "message": {"role": "assistant", "content": "Hello there"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 20}
            }
            """;

        ChatResponse response = VllmChatProvider.parseResponse(json, "meta-llama/Llama-3-8B-Instruct", MAPPER);

        assertThat(response.id()).isEqualTo("chatcmpl-123");
        assertThat(response.message().content()).isEqualTo("Hello there");
        assertThat(response.tokenCount().inputTokens()).isEqualTo(10);
        assertThat(response.tokenCount().outputTokens()).isEqualTo(20);
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.provider()).isEqualTo("vllm");
    }

    @Test
    void parseResponse_withToolCalls() throws Exception {
        String json = """
            {
              "id": "chatcmpl-456",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {"id": "call_01", "function": {"name": "get_weather", "arguments": "{\\"city\\": \\"Paris\\"}"}}
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ],
              "usage": {"prompt_tokens": 15, "completion_tokens": 25}
            }
            """;

        ChatResponse response = VllmChatProvider.parseResponse(json, "meta-llama/Llama-3-8B-Instruct", MAPPER);

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("call_01");
        assertThat(response.toolCalls().get(0).toolName()).isEqualTo("get_weather");
        assertThat(response.toolCalls().get(0).arguments()).containsEntry("city", "Paris");
    }

    @Test
    void parseSseEvents_basic() throws Exception {
        String data = """
            {"choices": [{"delta": {"content": "Hello"}}]}
            """;

        List<StreamEvent> events = VllmChatProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Token.class);
        assertThat(((StreamEvent.Token) events.get(0)).token()).isEqualTo("Hello");
    }

    @Test
    void parseSseEvents_withReasoning() throws Exception {
        String data = """
            {"choices": [{"delta": {"reasoning_content": "Let me think..."}}]}
            """;

        List<StreamEvent> events = VllmChatProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(1);
        assertThat(((StreamEvent.Token) events.get(0)).reasoningContent()).isEqualTo("Let me think...");
    }

    @Test
    void parseSseEvents_withFinish() throws Exception {
        String data = """
            {"choices": [{"delta": {"content": "Done"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 5, "completion_tokens": 10}}
            """;

        List<StreamEvent> events = VllmChatProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Token.class);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.Finish.class);
        StreamEvent.Finish finish = (StreamEvent.Finish) events.get(1);
        assertThat(finish.finishReason()).isEqualTo("stop");
        assertThat(finish.promptTokens()).isEqualTo(5);
        assertThat(finish.completionTokens()).isEqualTo(10);
    }

    @Test
    void parseSseEvents_withToolCallDelta() throws Exception {
        String data = """
            {"choices": [{"delta": {"tool_calls": [{"id": "call_01", "function": {"name": "get_weather"}}]}}]}
            """;

        List<StreamEvent> events = VllmChatProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.ToolCallStart.class);
        StreamEvent.ToolCallStart start = (StreamEvent.ToolCallStart) events.get(0);
        assertThat(start.toolCallId()).isEqualTo("call_01");
        assertThat(start.toolName()).isEqualTo("get_weather");
    }

    @Test
    void parseSseEvents_empty() throws Exception {
        assertThat(VllmChatProvider.parseSseEvents("", MAPPER)).isEmpty();
        assertThat(VllmChatProvider.parseSseEvents("[DONE]", MAPPER)).isEmpty();
    }
}
