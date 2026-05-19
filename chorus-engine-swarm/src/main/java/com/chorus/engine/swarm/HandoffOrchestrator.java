package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolError;
import com.chorus.engine.tools.ToolOutput;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * Dynamic handoff orchestrator (OpenAI Swarm-style).
 *
 * <p>The active agent decides to hand off to another agent via a special
 * tool call {@code transfer_to_<agent>}. The orchestrator intercepts the
 * call, emits a {@link SwarmEvent.HandoffEvent}, and switches the active
 * agent. Context (messages + variables) is passed to the new agent.
 *
 * <p>Max turns prevents infinite loops.
 */
public final class HandoffOrchestrator implements SwarmOrchestrator {

    private final Map<String, AgentDefinition> agents;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final SwarmConfig config;
    private final ExecutorService executor;
    private final Map<String, CircuitBreaker> circuitBreakers;

    public HandoffOrchestrator(
        @NonNull Map<String, AgentDefinition> agents,
        @NonNull LlmClient llmClient,
        @NonNull ToolRegistry toolRegistry,
        @NonNull SwarmConfig config,
        @NonNull ExecutorService executor
    ) {
        this.agents = Map.copyOf(Objects.requireNonNull(agents, "agents cannot be null"));
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.circuitBreakers = new ConcurrentHashMap<>();
        if (config.enableCircuitBreakers()) {
            for (String name : this.agents.keySet()) {
                circuitBreakers.put(name, new CircuitBreaker());
            }
        }
    }

    @Override
    public Flow.@NonNull Publisher<SwarmEvent> run(
        @NonNull SwarmSession session,
        @NonNull CancellationToken token
    ) {
        return subscriber -> executor.submit(() -> {
            try {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() { token.cancel("Subscriber cancelled"); }
                });
                runHandoffLoop(session, token, subscriber);
                subscriber.onComplete();
            } catch (Exception e) {
                subscriber.onError(e);
            }
        });
    }

    @Override
    public @NonNull SwarmSession createSession(
        @NonNull String initialAgent,
        @NonNull List<Message> messages
    ) {
        return new SwarmSession(
            UUID.randomUUID().toString(),
            messages,
            Map.of(),
            initialAgent
        );
    }

    private void runHandoffLoop(
        SwarmSession session,
        CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) {
        while (session.turnCount() < config.maxTurns() && !token.isCancelled()) {
            String activeName = session.activeAgent();
            AgentDefinition agent = agents.get(activeName);
            if (agent == null) {
                emit(subscriber, new SwarmEvent.SwarmError(activeName,
                    new IllegalStateException("Unknown agent: " + activeName)));
                return;
            }

            CircuitBreaker cb = circuitBreakers.get(activeName);
            if (cb != null && !cb.allowRequest()) {
                emit(subscriber, new SwarmEvent.CircuitBreakerOpen(activeName));
                return;
            }

            List<ToolDefinition> allTools = new ArrayList<>();
            allTools.addAll(agent.tools().stream().map(this::toToolDefinition).toList());
            List<String> targets = agent.handoffTargets() != null ? agent.handoffTargets() : List.of();
            for (String target : targets) {
                allTools.add(createTransferTool(target));
            }

            List<Message> requestMessages = new ArrayList<>();
            requestMessages.add(Message.system(buildSystemPrompt(agent, session)));
            requestMessages.addAll(session.messages());

            ChatRequest request = ChatRequest.builder()
                .model(agent.model())
                .messages(requestMessages)
                .tools(allTools)
                .temperature(0.7)
                .maxTokens(4096)
                .build();

            emit(subscriber, new SwarmEvent.AgentStart(activeName, List.copyOf(requestMessages)));

            ChatResponse response;
            try {
                response = llmClient.complete(request, token);
                if (cb != null) cb.recordSuccess();
            } catch (Exception e) {
                if (cb != null) cb.recordFailure();
                emit(subscriber, new SwarmEvent.SwarmError(activeName, e));
                return;
            }

            Message assistantMsg = response.message();
            emit(subscriber, new SwarmEvent.AgentResponse(activeName, assistantMsg, response.tokenCount()));
            session.addMessage(assistantMsg);

            if (response.hasToolCalls()) {
                List<ChatResponse.ToolCall> handoffs = new ArrayList<>();
                List<ChatResponse.ToolCall> regular = new ArrayList<>();
                for (ChatResponse.ToolCall tc : response.toolCalls()) {
                    if (tc.toolName().startsWith("transfer_to_")) {
                        handoffs.add(tc);
                    } else {
                        regular.add(tc);
                    }
                }

                if (!handoffs.isEmpty()) {
                    ChatResponse.ToolCall handoffCall = handoffs.get(0);
                    String targetAgent = handoffCall.toolName().substring("transfer_to_".length());
                    String reason = handoffCall.arguments().getOrDefault("reason", "handoff").toString();

                    Handoff handoff = new Handoff(activeName, targetAgent, reason, List.copyOf(session.messages()));
                    emit(subscriber, new SwarmEvent.HandoffEvent(handoff));

                    session.setActiveAgent(targetAgent);
                    session.incrementTurnCount();
                    continue;
                }

                for (ChatResponse.ToolCall tc : regular) {
                    executeTool(activeName, tc, token, subscriber, session);
                }
            } else {
                emit(subscriber, new SwarmEvent.SwarmComplete(activeName, assistantMsg));
                return;
            }

            session.incrementTurnCount();
        }

        if (token.isCancelled()) {
            emit(subscriber, new SwarmEvent.SwarmError(session.activeAgent(),
                new CancellationException(token.reason())));
        } else {
            emit(subscriber, new SwarmEvent.SwarmError(session.activeAgent(),
                new IllegalStateException("Max turns reached: " + config.maxTurns())));
        }
    }

    private void executeTool(
        @NonNull String agentName,
        ChatResponse.@NonNull ToolCall tc,
        @NonNull CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber,
        @NonNull SwarmSession session
    ) {
        Tool tool = toolRegistry.find(tc.toolName());
        if (tool != null) {
            Result<ToolOutput, ToolError> result = tool.execute(tc.arguments(), token);
            if (result.isOk()) {
                ToolOutput output = result.unwrap();
                emit(subscriber, new SwarmEvent.ToolExecution(agentName, tc.toolName(), output));
                session.addMessage(Message.tool(output.content(), tc.id()));
            } else {
                session.addMessage(Message.tool("Error: " + result.unwrapErr(), tc.id()));
            }
        } else {
            session.addMessage(Message.tool("Tool not found: " + tc.toolName(), tc.id()));
        }
    }

    private @NonNull ToolDefinition createTransferTool(@NonNull String targetAgent) {
        return new ToolDefinition(
            "transfer_to_" + targetAgent,
            "Transfer control to the " + targetAgent + " agent.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "reason", Map.of("type", "string", "description", "Reason for the handoff")
                ),
                "required", List.of("reason")
            ),
            null,
            true
        );
    }

    private @NonNull String buildSystemPrompt(@NonNull AgentDefinition agent, @NonNull SwarmSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append(agent.instructions());
        if (!session.contextVariables().isEmpty()) {
            sb.append("\n\nContext variables:\n");
            for (Map.Entry<String, Object> entry : session.contextVariables().entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        List<String> targets = agent.handoffTargets();
        if (targets != null && !targets.isEmpty()) {
            sb.append("\nYou can hand off to: ").append(String.join(", ", targets)).append("\n");
        }
        return sb.toString();
    }

    private @NonNull ToolDefinition toToolDefinition(@NonNull Tool tool) {
        return new ToolDefinition(
            tool.name(),
            tool.description(),
            tool.parametersSchema(),
            null,
            true
        );
    }

    private void emit(Flow.Subscriber<? super SwarmEvent> subscriber, SwarmEvent event) {
        subscriber.onNext(event);
    }
}
