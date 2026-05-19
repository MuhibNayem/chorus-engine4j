package com.chorus.engine.core.llm;

import com.chorus.engine.core.event.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Framework-agnostic LLM provider abstraction.
 * Adapters bridge this to Spring AI ChatModel, LangChain4j, or raw HTTP clients.
 */
public interface ChorusChatModel {

    String name();

    CompletableFuture<ModelResponse> generate(List<ChatMessage> messages, String systemPrompt,
                                                 List<ToolDef> tools, String model);

    CompletableFuture<StreamingResponse> stream(List<ChatMessage> messages, String systemPrompt,
                                                 List<ToolDef> tools, String model);

    default double estimateCost(int inputTokens, int outputTokens) {
        return 0.0;
    }

    record ModelResponse(
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        int inputTokens,
        int outputTokens
    ) {}

    record StreamingResponse(Flow.Publisher<StreamEvent> events) {}

    sealed interface StreamEvent permits StreamEvent.TokenEvent, StreamEvent.ThinkingEvent, StreamEvent.DoneEvent {
        record TokenEvent(String text) implements StreamEvent {}
        record ThinkingEvent(String text) implements StreamEvent {}
        record DoneEvent(ModelResponse response) implements StreamEvent {}
    }

    record ToolDef(String name, String description, Map<String, Object> parameters) {}

    record ToolCall(String id, String name, String arguments) {}
}
