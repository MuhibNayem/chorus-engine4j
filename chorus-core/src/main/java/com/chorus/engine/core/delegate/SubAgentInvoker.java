package com.chorus.engine.core.delegate;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.llm.ChorusChatModel;
import com.chorus.engine.core.loop.AgentLoop;
import com.chorus.engine.core.loop.LoopOptions;
import com.chorus.engine.core.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Invokes a sub-agent with an isolated context, optionally copying tools and
 * system prompt from the parent. Returns the final assistant message.
 */
public class SubAgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(SubAgentInvoker.class);

    private final ChorusChatModel chatModel;
    private final List<AgentTool> tools;
    private final String systemPrompt;
    private final LoopOptions loopOptions;

    public SubAgentInvoker(ChorusChatModel chatModel, List<AgentTool> tools,
                           String systemPrompt, LoopOptions loopOptions) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.loopOptions = loopOptions;
    }

    /**
     * Invoke a sub-agent with a fresh isolated context.
     */
    public CompletableFuture<ChatMessage> invoke(String task, Function<IsolatedContext, String> promptBuilder) {
        IsolatedContext ctx = new IsolatedContext("parent");
        ctx.addMessage(ChatMessage.system(systemPrompt));
        ctx.addMessage(ChatMessage.user(promptBuilder.apply(ctx)));
        return runLoop(ctx);
    }

    /**
     * Invoke a sub-agent with a pre-built isolated context.
     * Propagates trace context if present in the isolated context.
     */
    public CompletableFuture<ChatMessage> invoke(IsolatedContext ctx) {
        return runLoop(ctx);
    }

    private CompletableFuture<ChatMessage> runLoop(IsolatedContext ctx) {
        LoopOptions opts = new LoopOptions(
            chatModel, null, tools, ctx.messages(), systemPrompt,
            ctx.threadId(), null, null, null, 500, null, null, null, 120_000, null,
            java.util.Optional.ofNullable(ctx.traceContext()));
        AgentLoop loop = new AgentLoop(opts);

        // Propagate trace context into the event stream if available
        var traceContext = ctx.traceContext();

        return loop.run()
            .collectList()
            .map(events -> {
                // Extract the final response from DoneEvent
                for (int i = events.size() - 1; i >= 0; i--) {
                    if (events.get(i) instanceof com.chorus.engine.core.event.AgentEvent.DoneEvent done) {
                        return ChatMessage.assistant(done.response());
                    }
                }
                return ChatMessage.assistant("(no response)");
            })
            .toFuture();
    }

    /**
     * Builder for fluent configuration.
     */
    public static Builder builder(ChorusChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static class Builder {
        private final ChorusChatModel chatModel;
        private List<AgentTool> tools = List.of();
        private String systemPrompt = "You are a helpful assistant.";
        private LoopOptions loopOptions = null;

        Builder(ChorusChatModel chatModel) { this.chatModel = chatModel; }

        public Builder tools(List<AgentTool> tools) {
            this.tools = tools;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder loopOptions(LoopOptions loopOptions) {
            this.loopOptions = loopOptions;
            return this;
        }

        public SubAgentInvoker build() {
            return new SubAgentInvoker(chatModel, tools, systemPrompt,
                loopOptions != null ? loopOptions :
                    new LoopOptions(chatModel, null, tools, null, systemPrompt,
                        null, null, null, null, 500, null, null, null, 120_000, null,
                        java.util.Optional.empty()));
        }
    }
}
