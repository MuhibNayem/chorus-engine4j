package com.chorus.engine.swarm;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervisor / Worker orchestrator.
 *
 * <p>A central supervisor agent decides which worker(s) to invoke.
 * Workers execute (in parallel when possible) and return results to the
 * supervisor. The supervisor continues until the task is complete.
 *
 * <p>Parallel worker execution uses {@link StructuredTaskScope}.
 */
public final class SupervisorOrchestrator implements SwarmOrchestrator {

    private final Map<String, AgentDefinition> agents;
    private final String supervisorName;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final SwarmConfig config;
    private final ExecutorService executor;
    private final Map<String, CircuitBreaker> circuitBreakers;

    public SupervisorOrchestrator(
        @NonNull Map<String, AgentDefinition> agents,
        @NonNull String supervisorName,
        @NonNull LlmClient llmClient,
        @NonNull ToolRegistry toolRegistry,
        @NonNull SwarmConfig config,
        @NonNull ExecutorService executor
    ) {
        this.agents = Map.copyOf(Objects.requireNonNull(agents, "agents cannot be null"));
        if (!this.agents.containsKey(supervisorName)) {
            throw new IllegalArgumentException("Supervisor not found in agents: " + supervisorName);
        }
        this.supervisorName = supervisorName;
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
                runSupervisorLoop(session, token, subscriber);
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

    private void runSupervisorLoop(
        SwarmSession session,
        CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) {
        while (session.turnCount() < config.maxTurns() && !token.isCancelled()) {
            SupervisorDecision decision = querySupervisor(session, token, subscriber);
            if (decision == null) {
                return;
            }

            if (decision instanceof CompleteDecision complete) {
                Message finalMsg = Message.assistant(complete.response());
                emit(subscriber, new SwarmEvent.SwarmComplete(supervisorName, finalMsg));
                return;
            }

            if (decision instanceof DelegateDecision delegate) {
                List<String> targets = delegate.agents();
                List<Message> workerResults;
                try {
                    if (targets.size() == 1) {
                        workerResults = List.of(invokeWorker(targets.get(0), delegate.task(), token, subscriber));
                    } else {
                        workerResults = invokeWorkersParallel(targets, delegate.task(), token, subscriber);
                    }
                } catch (Exception e) {
                    emit(subscriber, new SwarmEvent.SwarmError(supervisorName, e));
                    return;
                }

                for (Message result : workerResults) {
                    session.addMessage(result);
                }
                session.incrementTurnCount();
            }
        }

        if (token.isCancelled()) {
            emit(subscriber, new SwarmEvent.SwarmError(supervisorName,
                new CancellationException(token.reason())));
        } else {
            emit(subscriber, new SwarmEvent.SwarmError(supervisorName,
                new IllegalStateException("Max turns reached: " + config.maxTurns())));
        }
    }

    private @Nullable SupervisorDecision querySupervisor(
        SwarmSession session,
        CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) {
        AgentDefinition supervisor = agents.get(supervisorName);
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the supervisor. Available workers:\n");
        for (Map.Entry<String, AgentDefinition> entry : agents.entrySet()) {
            if (entry.getKey().equals(supervisorName)) continue;
            prompt.append("- ").append(entry.getKey()).append(": ")
                  .append(entry.getValue().instructions()).append("\n");
        }
        prompt.append("\nRespond with one of:\n");
        prompt.append("DELEGATE <agent_name>\nTASK: <task description>\n");
        prompt.append("or\nCOMPLETE\nRESPONSE: <final response>\n");

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(prompt.toString()));
        messages.addAll(session.messages());

        ChatRequest request = ChatRequest.builder()
            .model(supervisor.model())
            .messages(messages)
            .tools(List.of())
            .build();

        emit(subscriber, new SwarmEvent.AgentStart(supervisorName, List.copyOf(messages)));

        try {
            ChatResponse response = llmClient.complete(request, token);
            emit(subscriber, new SwarmEvent.AgentResponse(supervisorName, response.message(), response.tokenCount()));
            session.addMessage(response.message());
            return parseDecision(response.message().content());
        } catch (Exception e) {
            emit(subscriber, new SwarmEvent.SwarmError(supervisorName, e));
            return null;
        }
    }

    private @NonNull SupervisorDecision parseDecision(@NonNull String content) {
        String trimmed = content.trim();
        if (trimmed.toUpperCase().startsWith("DELEGATE")) {
            String[] lines = trimmed.split("\n", 2);
            String agentLine = lines[0].substring("DELEGATE".length()).trim();
            String task = "";
            if (lines.length > 1 && lines[1].trim().toUpperCase().startsWith("TASK:")) {
                task = lines[1].trim().substring("TASK:".length()).trim();
            }
            return new DelegateDecision(List.of(agentLine), task);
        }
        if (trimmed.toUpperCase().startsWith("COMPLETE")) {
            String[] lines = trimmed.split("\n", 2);
            String response = trimmed;
            if (lines.length > 1 && lines[1].trim().toUpperCase().startsWith("RESPONSE:")) {
                response = lines[1].trim().substring("RESPONSE:".length()).trim();
            }
            return new CompleteDecision(response);
        }
        // Default to complete with raw content
        return new CompleteDecision(trimmed);
    }

    private @NonNull Message invokeWorker(
        @NonNull String agentName,
        @NonNull String task,
        @NonNull CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) throws Exception {
        AgentDefinition agent = agents.get(agentName);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown worker: " + agentName);
        }

        CircuitBreaker cb = circuitBreakers.get(agentName);
        if (cb != null && !cb.allowRequest()) {
            if (subscriber != null) {
                emit(subscriber, new SwarmEvent.CircuitBreakerOpen(agentName));
            }
            return Message.assistant("[Circuit breaker open for " + agentName + "]");
        }

        List<ToolDefinition> tools = agent.tools().stream()
            .map(this::toToolDefinition)
            .toList();

        AgentLoop loop = new AgentLoop(
            agentName,
            buildSystemPrompt(agent),
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

        loop.run(UUID.randomUUID().toString(), task, tools, token).subscribe(new Flow.Subscriber<>() {
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
            if (cb != null) cb.recordFailure();
            throw new TimeoutException("Worker " + agentName + " timed out");
        }

        String finalAnswer = events.stream()
            .filter(e -> e instanceof AgentEvent.Done)
            .map(e -> ((AgentEvent.Done) e).finalAnswer())
            .findFirst()
            .orElse("");

        Message result = Message.assistant(finalAnswer);
        if (subscriber != null) {
            emit(subscriber, new SwarmEvent.AgentStart(agentName, List.of()));
            emit(subscriber, new SwarmEvent.AgentResponse(agentName, result,
                new TokenCount(inputTokens.get(), outputTokens.get(), "unknown")));
        }

        if (cb != null) cb.recordSuccess();
        return result;
    }

    private @NonNull List<Message> invokeWorkersParallel(
        @NonNull List<String> agentNames,
        @NonNull String task,
        @NonNull CancellationToken token,
        Flow.Subscriber<? super SwarmEvent> subscriber
    ) throws Exception {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {
            List<StructuredTaskScope.Subtask<Message>> subtasks = new ArrayList<>();
            for (String name : agentNames) {
                subtasks.add(scope.fork(() -> invokeWorker(name, task, token, subscriber)));
            }
            scope.join();
            return subtasks.stream()
                .map(StructuredTaskScope.Subtask::get)
                .toList();
        }
    }

    private @NonNull String buildSystemPrompt(@NonNull AgentDefinition agent) {
        StringBuilder sb = new StringBuilder();
        sb.append(agent.instructions());
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

    private sealed interface SupervisorDecision {}
    private record DelegateDecision(@NonNull List<String> agents, @NonNull String task) implements SupervisorDecision {}
    private record CompleteDecision(@NonNull String response) implements SupervisorDecision {}
}
