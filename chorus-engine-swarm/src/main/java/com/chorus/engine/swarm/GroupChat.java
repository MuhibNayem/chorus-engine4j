package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.tools.Tool;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * Round-robin multi-agent conversation.
 *
 * <p>Multiple agents participate in a shared conversation. Each agent speaks
 * in turn. Convergence is detected when the last message from each agent
 * contains the marker {@code [AGREE]} or when all last messages are equal.
 * Max rounds prevents infinite debate.
 */
public final class GroupChat implements SwarmOrchestrator {

    private final List<String> agentNames;
    private final Map<String, AgentDefinition> agents;
    private final LlmClient llmClient;
    private final SwarmConfig config;
    private final ExecutorService executor;
    private final int maxRounds;

    public GroupChat(
        @NonNull List<String> agentNames,
        @NonNull Map<String, AgentDefinition> agents,
        @NonNull LlmClient llmClient,
        @NonNull SwarmConfig config,
        @NonNull ExecutorService executor,
        int maxRounds
    ) {
        this.agentNames = List.copyOf(Objects.requireNonNull(agentNames, "agentNames cannot be null"));
        this.agents = Map.copyOf(Objects.requireNonNull(agents, "agents cannot be null"));
        for (String name : this.agentNames) {
            if (!this.agents.containsKey(name)) {
                throw new IllegalArgumentException("Agent not defined: " + name);
            }
        }
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1");
        }
        this.maxRounds = maxRounds;
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
                runGroupChat(session, token, subscriber);
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

    private void runGroupChat(
        SwarmSession session,
        CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) {
        int rounds = 0;
        while (rounds < maxRounds && !token.isCancelled()) {
            for (String agentName : agentNames) {
                if (token.isCancelled()) break;

                AgentDefinition agent = agents.get(agentName);
                List<Message> requestMessages = new ArrayList<>();
                requestMessages.add(Message.system(buildSystemPrompt(agent, session)));
                requestMessages.addAll(session.messages());

                ChatRequest request = ChatRequest.builder()
                    .model(agent.model())
                    .messages(requestMessages)
                    .tools(agent.tools().stream().map(this::toToolDefinition).toList())
                    .temperature(0.7)
                    .maxTokens(4096)
                    .build();

                emit(subscriber, new SwarmEvent.AgentStart(agentName, List.copyOf(requestMessages)));

                ChatResponse response;
                try {
                    response = llmClient.complete(request, token);
                } catch (Exception e) {
                    emit(subscriber, new SwarmEvent.SwarmError(agentName, e));
                    return;
                }

                Message msg = response.message();
                emit(subscriber, new SwarmEvent.AgentResponse(agentName, msg, response.tokenCount()));
                session.addMessage(msg);
                session.setActiveAgent(agentName);
            }

            rounds++;
            session.addTurns(agentNames.size());

            if (hasConverged(session)) {
                Message last = session.messages().get(session.messages().size() - 1);
                emit(subscriber, new SwarmEvent.SwarmComplete(session.activeAgent(), last));
                return;
            }

            if (session.turnCount() >= config.maxTurns()) {
                emit(subscriber, new SwarmEvent.SwarmError(session.activeAgent(),
                    new IllegalStateException("Max turns reached in group chat")));
                return;
            }
        }

        if (token.isCancelled()) {
            emit(subscriber, new SwarmEvent.SwarmError(session.activeAgent(),
                new CancellationException(token.reason())));
        } else {
            Message last = session.messages().isEmpty()
                ? Message.assistant("")
                : session.messages().get(session.messages().size() - 1);
            emit(subscriber, new SwarmEvent.SwarmComplete(session.activeAgent(), last));
        }
    }

    private boolean hasConverged(@NonNull SwarmSession session) {
        List<Message> messages = session.messages();
        if (messages.size() < agentNames.size()) {
            return false;
        }

        // Collect last message from each agent in the most recent round
        Set<String> lastContents = new LinkedHashSet<>();
        boolean allAgree = true;
        for (int i = messages.size() - 1, count = 0; i >= 0 && count < agentNames.size(); i--) {
            Message msg = messages.get(i);
            if (msg.role() != com.chorus.engine.core.context.Role.ASSISTANT) continue;
            lastContents.add(msg.content().trim());
            if (!msg.content().contains("[AGREE]")) {
                allAgree = false;
            }
            count++;
        }

        return allAgree || lastContents.size() == 1;
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
        sb.append("\nWhen you agree with the consensus, end your message with [AGREE].");
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
