package com.chorus.engine.core.llm;

import com.chorus.engine.core.event.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/**
 * Bridges the Chorus {@link ChorusChatModel} abstraction to Spring AI's {@link ChatClient}.
 * Uses user-controlled tool execution so the AgentLoop retains control of the ReAct cycle.
 *
 * <p>Compatible with Spring AI 2.0.0-M6.</p>
 */
public class SpringAiChatModelAdapter implements ChorusChatModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final String name;

    public SpringAiChatModelAdapter(ChatClient chatClient, String name) {
        this.chatClient = chatClient;
        this.name = name;
    }

    public SpringAiChatModelAdapter(ChatClient chatClient) {
        this(chatClient, "spring-ai");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CompletableFuture<ModelResponse> generate(List<ChatMessage> messages, String systemPrompt,
                                                      List<ToolDef> tools, String model) {
        return CompletableFuture.supplyAsync(() -> {
            List<Message> springMessages = toSpringMessages(messages);
            List<ToolCallback> toolCallbacks = toDefinitionCallbacks(tools);

            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(springMessages);

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }

            if (!toolCallbacks.isEmpty()) {
                spec = spec.toolCallbacks(toolCallbacks);
            }

            ChatResponse response = spec.call().chatResponse();
            return fromChatResponse(response);
        });
    }

    @Override
    public CompletableFuture<StreamingResponse> stream(List<ChatMessage> messages, String systemPrompt,
                                                        List<ToolDef> tools, String model) {
        return CompletableFuture.supplyAsync(() -> {
            List<Message> springMessages = toSpringMessages(messages);
            List<ToolCallback> toolCallbacks = toDefinitionCallbacks(tools);

            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(springMessages);

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }

            if (!toolCallbacks.isEmpty()) {
                spec = spec.toolCallbacks(toolCallbacks);
            }

            Flux<String> contentFlux = spec.stream().content();
            return new StreamingResponse(toFlowPublisher(contentFlux));
        });
    }

    private Flow.Publisher<StreamEvent> toFlowPublisher(Flux<String> flux) {
        return subscriber -> {
            flux.subscribe(
                token -> subscriber.onNext(new StreamEvent.TokenEvent(token)),
                subscriber::onError,
                () -> subscriber.onNext(new StreamEvent.DoneEvent(
                    new ModelResponse("", "", List.of(), 0, 0)))
            );
        };
    }

    private List<Message> toSpringMessages(List<ChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            switch (msg.role()) {
                case SYSTEM -> result.add(new SystemMessage(msg.content()));
                case USER -> result.add(new UserMessage(msg.content()));
                case ASSISTANT -> {
                    if (msg.toolCalls().isPresent() && !msg.toolCalls().get().isEmpty()) {
                        List<AssistantMessage.ToolCall> toolCalls = msg.toolCalls().get().stream()
                            .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.arguments()))
                            .collect(Collectors.toList());
                        result.add(AssistantMessage.builder()
                            .content(msg.content())
                            .toolCalls(toolCalls)
                            .build());
                    } else {
                        result.add(new AssistantMessage(msg.content()));
                    }
                }
                case TOOL -> {
                    if (msg.toolCallId().isPresent()) {
                        result.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                msg.toolCallId().get(), "", msg.content())))
                            .build());
                    }
                }
            }
        }
        return result;
    }

    private List<ToolCallback> toDefinitionCallbacks(List<ToolDef> tools) {
        return tools.stream()
            .map(DefinitionOnlyToolCallback::new)
            .collect(Collectors.toList());
    }

    private ModelResponse fromChatResponse(ChatResponse response) {
        String content = "";
        String reasoningContent = "";
        List<ToolCall> toolCalls = List.of();
        int inputTokens = 0;
        int outputTokens = 0;

        if (response.getResult() != null && response.getResult().getOutput() != null) {
            AssistantMessage am = response.getResult().getOutput();
            content = am.getText() != null ? am.getText() : "";

            if (am.hasToolCalls()) {
                toolCalls = am.getToolCalls().stream()
                    .map(tc -> new ToolCall(tc.id(), tc.name(), tc.arguments()))
                    .collect(Collectors.toList());
            }
        }

        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) inputTokens = usage.getPromptTokens();
            if (usage.getCompletionTokens() != null) outputTokens = usage.getCompletionTokens();
        }

        return new ModelResponse(content, reasoningContent, toolCalls, inputTokens, outputTokens);
    }

    /**
     * A definition-only ToolCallback that provides schema to the model but never executes.
     * Execution is handled by the AgentLoop's ReAct cycle.
     */
    private static class DefinitionOnlyToolCallback implements ToolCallback {
        private final ToolDef toolDef;
        private final ToolDefinition toolDefinition;

        DefinitionOnlyToolCallback(ToolDef toolDef) {
            this.toolDef = toolDef;
            this.toolDefinition = ToolDefinition.builder()
                .name(toolDef.name())
                .description(toolDef.description())
                .inputSchema(schemaToString(toolDef.parameters()))
                .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException(
                "DefinitionOnlyToolCallback does not execute. Tool '" + toolDef.name() + "' should be executed by AgentLoop.");
        }

        private static String schemaToString(Map<String, Object> schema) {
            if (schema == null || schema.isEmpty()) return "{}";
            try {
                return MAPPER.writeValueAsString(schema);
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
