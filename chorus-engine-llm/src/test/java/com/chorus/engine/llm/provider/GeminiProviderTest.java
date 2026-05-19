package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class GeminiProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildRequestBody_basic() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(Message.user("Hello")))
            .temperature(0.7)
            .maxTokens(4096)
            .build();

        String json = GeminiProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(root.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(4096);
        assertThat(root.path("contents")).hasSize(1);
        assertThat(root.path("contents").get(0).path("role").asText()).isEqualTo("user");
        assertThat(root.path("contents").get(0).path("parts").get(0).path("text").asText()).isEqualTo("Hello");
    }

    @Test
    void buildRequestBody_withSystem() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(
                Message.system("You are helpful"),
                Message.user("Hello")
            ))
            .build();

        String json = GeminiProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("systemInstruction").path("parts").get(0).path("text").asText()).isEqualTo("You are helpful");
        assertThat(root.path("contents")).hasSize(1);
    }

    @Test
    void buildRequestBody_withTools() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(Message.user("What's the weather?")))
            .tools(List.of(ToolDefinition.builder("get_weather", "Get weather info")
                .parametersSchema(Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))
                .build()))
            .build();

        String json = GeminiProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("tools")).hasSize(1);
        JsonNode functionDeclaration = root.path("tools").get(0).path("functionDeclarations").get(0);
        assertThat(functionDeclaration.path("name").asText()).isEqualTo("get_weather");
        assertThat(functionDeclaration.path("parameters").path("type").asText()).isEqualTo("object");
    }

    @Test
    void buildRequestBody_withStopSequence() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(Message.user("Hello")))
            .stopSequence("STOP")
            .build();

        String json = GeminiProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("generationConfig").path("stopSequences").get(0).asText()).isEqualTo("STOP");
    }

    @Test
    void buildRequestBody_assistantRoleMappedToModel() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(
                Message.user("Hello"),
                Message.assistant("Hi there")
            ))
            .build();

        String json = GeminiProvider.buildRequestBody(request, MAPPER);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.path("contents").get(1).path("role").asText()).isEqualTo("model");
    }

    @Test
    void parseResponse_basic() throws Exception {
        String json = """
            {
              "candidates": [
                {
                  "content": {"role": "model", "parts": [{"text": "Hello there"}]},
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 20}
            }
            """;

        ChatResponse response = GeminiProvider.parseResponse(json, "gemini-1.5-pro", MAPPER);

        assertThat(response.message().content()).isEqualTo("Hello there");
        assertThat(response.tokenCount().inputTokens()).isEqualTo(10);
        assertThat(response.tokenCount().outputTokens()).isEqualTo(20);
        assertThat(response.finishReason()).isEqualTo("STOP");
        assertThat(response.provider()).isEqualTo("gemini");
    }

    @Test
    void parseResponse_withFunctionCall() throws Exception {
        String json = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [{"functionCall": {"name": "get_weather", "args": {"city": "Paris"}}}]
                  },
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {"promptTokenCount": 15, "candidatesTokenCount": 25}
            }
            """;

        ChatResponse response = GeminiProvider.parseResponse(json, "gemini-1.5-pro", MAPPER);

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).toolName()).isEqualTo("get_weather");
        assertThat(response.toolCalls().get(0).arguments()).containsEntry("city", "Paris");
    }

    @Test
    void parseSseEvents_textDelta() throws Exception {
        String data = """
            {"candidates": [{"content": {"parts": [{"text": "Hello"}]}}]}
            """;

        List<StreamEvent> events = GeminiProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Token.class);
        assertThat(((StreamEvent.Token) events.get(0)).token()).isEqualTo("Hello");
    }

    @Test
    void parseSseEvents_withFinish() throws Exception {
        String data = """
            {"candidates": [{"content": {"parts": [{"text": "Done"}]}, "finishReason": "STOP"}], "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 10}}
            """;

        List<StreamEvent> events = GeminiProvider.parseSseEvents(data, MAPPER);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Token.class);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.Finish.class);
        StreamEvent.Finish finish = (StreamEvent.Finish) events.get(1);
        assertThat(finish.finishReason()).isEqualTo("STOP");
        assertThat(finish.promptTokens()).isEqualTo(5);
        assertThat(finish.completionTokens()).isEqualTo(10);
    }

    @Test
    void parseSseEvents_empty() throws Exception {
        assertThat(GeminiProvider.parseSseEvents("", MAPPER)).isEmpty();
        assertThat(GeminiProvider.parseSseEvents("[DONE]", MAPPER)).isEmpty();
    }
}
