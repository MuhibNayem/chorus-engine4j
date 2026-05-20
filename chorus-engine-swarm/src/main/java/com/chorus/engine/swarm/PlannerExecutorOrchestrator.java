package com.chorus.engine.swarm;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Planner-Executor orchestrator.
 *
 * <p>A planning agent generates a step-by-step plan upfront. Each step is
 * then executed sequentially by the most appropriate worker agent.
 */
public final class PlannerExecutorOrchestrator implements SwarmOrchestrator {

    private final Map<String, AgentDefinition> agents;
    private final String plannerName;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final SwarmConfig config;
    private final ExecutorService executor;

    public PlannerExecutorOrchestrator(
        @NonNull Map<String, AgentDefinition> agents,
        @NonNull String plannerName,
        @NonNull LlmClient llmClient,
        @NonNull ToolRegistry toolRegistry,
        @NonNull SwarmConfig config,
        @NonNull ExecutorService executor
    ) {
        this.agents = Map.copyOf(Objects.requireNonNull(agents, "agents cannot be null"));
        if (!this.agents.containsKey(plannerName)) {
            throw new IllegalArgumentException("Planner not found in agents: " + plannerName);
        }
        this.plannerName = plannerName;
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
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
                runPlanThenExecute(session, token, subscriber);
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

    private void runPlanThenExecute(
        SwarmSession session,
        CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) {
        // Planning phase
        List<String> plan = generatePlan(session, token, subscriber);
        if (plan.isEmpty()) {
            emit(subscriber, new SwarmEvent.SwarmError(plannerName,
                new IllegalStateException("Planner produced empty plan")));
            return;
        }

        // Execution phase
        List<Message> stepResults = new ArrayList<>();
        for (int i = 0; i < plan.size() && !token.isCancelled(); i++) {
            String step = plan.get(i);
            String agentName = selectAgentForStep(step);
            AgentDefinition agent = agents.get(agentName);
            if (agent == null) {
                emit(subscriber, new SwarmEvent.SwarmError(agentName,
                    new IllegalStateException("No agent available for step: " + step)));
                return;
            }

            session.setActiveAgent(agentName);
            emit(subscriber, new SwarmEvent.AgentStart(agentName, List.of()));

            Message result;
            try {
                result = invokeAgent(agent, step, token, subscriber);
            } catch (Exception e) {
                emit(subscriber, new SwarmEvent.SwarmError(agentName, e));
                return;
            }

            stepResults.add(result);
            session.addMessage(result);
            session.incrementTurnCount();

            if (session.turnCount() >= config.maxTurns()) {
                emit(subscriber, new SwarmEvent.SwarmError(agentName,
                    new IllegalStateException("Max turns reached during execution")));
                return;
            }
        }

        if (token.isCancelled()) {
            emit(subscriber, new SwarmEvent.SwarmError(session.activeAgent(),
                new CancellationException(token.reason())));
            return;
        }

        String finalAnswer = stepResults.isEmpty() ? "" : stepResults.get(stepResults.size() - 1).content();
        emit(subscriber, new SwarmEvent.SwarmComplete(session.activeAgent(), Message.assistant(finalAnswer)));
    }

    private @NonNull List<String> generatePlan(
        SwarmSession session,
        CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) {
        AgentDefinition planner = agents.get(plannerName);
        String prompt = "Create a numbered plan to solve the following task. "
            + "Each line should start with a number followed by a period.\n\n";

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(prompt));
        messages.addAll(session.messages());

        ChatRequest request = ChatRequest.builder()
            .model(planner.model())
            .messages(messages)
            .tools(List.of())
            .build();

        emit(subscriber, new SwarmEvent.AgentStart(plannerName, List.copyOf(messages)));

        try {
            ChatResponse response = llmClient.complete(request, token);
            emit(subscriber, new SwarmEvent.AgentResponse(plannerName, response.message(), response.tokenCount()));
            session.addMessage(response.message());
            return parsePlan(response.message().content());
        } catch (Exception e) {
            emit(subscriber, new SwarmEvent.SwarmError(plannerName, e));
            return List.of();
        }
    }

    private @NonNull List<String> parsePlan(@NonNull String content) {
        List<String> steps = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+\\.\\s+.+")) {
                int dotIndex = trimmed.indexOf('.');
                steps.add(trimmed.substring(dotIndex + 1).trim());
            }
        }
        return List.copyOf(steps);
    }

    private @NonNull String selectAgentForStep(@NonNull String step) {
        String lower = step.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, AgentDefinition> entry : agents.entrySet()) {
            if (entry.getKey().equals(plannerName)) continue;
            String instructions = entry.getValue().instructions().toLowerCase(Locale.ROOT);
            // Simple keyword matching
            String[] words = lower.split("\\s+");
            for (String word : words) {
                if (word.length() > 3 && instructions.contains(word)) {
                    return entry.getKey();
                }
            }
        }
        // Fallback to first non-planner agent
        return agents.keySet().stream()
            .filter(k -> !k.equals(plannerName))
            .findFirst()
            .orElse(plannerName);
    }

    private @NonNull Message invokeAgent(
        @NonNull AgentDefinition agent,
        @NonNull String input,
        @NonNull CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) throws Exception {
        List<ToolDefinition> tools = agent.tools().stream()
            .map(this::toToolDefinition)
            .toList();

        AgentLoop loop = new AgentLoop(
            agent.name(),
            agent.instructions(),
            llmClient,
            agent.model(),
            0.7,
            4096,
            5,
            List.of(),
            null,
            executor
        );

        List<AgentEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);

        loop.run(UUID.randomUUID().toString(), input, tools, token).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent event) {
                events.add(event);
                if (event instanceof AgentEvent.StreamEnd se) {
                    inputTokens.addAndGet(se.totalInputTokens());
                    outputTokens.addAndGet(se.totalOutputTokens());
                }
            }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        boolean completed = latch.await(config.timeoutPerAgent().toSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            throw new TimeoutException("Agent " + agent.name() + " timed out");
        }

        String finalAnswer = events.stream()
            .filter(e -> e instanceof AgentEvent.Done)
            .map(e -> ((AgentEvent.Done) e).finalAnswer())
            .findFirst()
            .orElse("");

        Message result = Message.assistant(finalAnswer);
        if (subscriber != null) {
            emit(subscriber, new SwarmEvent.AgentResponse(agent.name(), result,
                new TokenCount(inputTokens.get(), outputTokens.get(), "unknown")));
        }
        return result;
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
