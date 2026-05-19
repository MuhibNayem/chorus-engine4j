package com.chorus.engine.integration;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.llm.ChorusChatModel;
import com.chorus.engine.core.llm.SpringAiChatModelAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for SpringAiChatModelAdapter using local Ollama.
 * Requires Ollama to be running with phi3:mini model.
 */
class SpringAiAdapterTest {

    private static ChorusChatModel chatModel;

    @BeforeAll
    static void setUp() {
        OllamaApi ollamaApi = OllamaApi.builder()
            .baseUrl("http://localhost:11434")
            .build();

        OllamaChatOptions options = OllamaChatOptions.builder()
            .model("phi3:mini")
            .temperature(0.7)
            .build();

        OllamaChatModel ollamaChatModel = OllamaChatModel.builder()
            .ollamaApi(ollamaApi)
            .defaultOptions(options)
            .build();

        ChatClient chatClient = ChatClient.create(ollamaChatModel);
        chatModel = new SpringAiChatModelAdapter(chatClient, "ollama-phi3");
    }

    @Test
    void testSimpleGeneration() {
        List<ChatMessage> messages = List.of(ChatMessage.user("Say hello in exactly one word"));

        ChorusChatModel.ModelResponse response = chatModel.generate(
            messages, "You are a concise assistant.", List.of(), "phi3:mini"
        ).join();

        assertThat(response).isNotNull();
        assertThat(response.content()).isNotBlank();
        System.out.println("Response: " + response.content());
    }

    @Test
    void testStreaming() {
        List<ChatMessage> messages = List.of(ChatMessage.user("Count to 3"));

        ChorusChatModel.StreamingResponse response = chatModel.stream(
            messages, "You are a concise assistant.", List.of(), "phi3:mini"
        ).join();

        assertThat(response).isNotNull();
        assertThat(response.events()).isNotNull();

        List<ChorusChatModel.StreamEvent> events = new java.util.ArrayList<>();
        response.events().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ChorusChatModel.StreamEvent event) { events.add(event); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });

        // Give streaming a moment to complete
        try { Thread.sleep(3000); } catch (InterruptedException e) { }

        assertThat(events).isNotEmpty();
        events.forEach(e -> System.out.println("Stream event: " + e));
    }
}
