package com.chorus.engine.swarm;

import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.hitl.HitlGate;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.llm.ChorusChatModel;
import com.chorus.engine.core.loop.AgentLoop;
import com.chorus.engine.core.loop.LoopOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SwarmOrchestrator {

    private final ChorusChatModel chatModel;
    private final String model;

    public SwarmOrchestrator(ChorusChatModel chatModel, String model) {
        this.chatModel = chatModel;
        this.model = model;
    }

    public Flux<AgentEvent> runSwarm(SwarmConfig config, String task, String threadId,
                                      HitlGate hitlGate, Checkpointer checkpointer) {
        return Flux.defer(() -> {
            Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
            new Thread(() -> executeSwarm(config, task, threadId, hitlGate, checkpointer, sink)).start();
            return sink.asFlux();
        });
    }

    private void executeSwarm(SwarmConfig config, String task, String threadId,
                              HitlGate hitlGate, Checkpointer checkpointer, Sinks.Many<AgentEvent> sink) {
        try {
            if ("graph".equals(config.executionModel())) {
                sink.tryEmitNext(new AgentEvent.ErrorEvent("Graph swarm not yet implemented", false));
                sink.tryEmitComplete();
                return;
            }

            Map<String, Object> sharedState = new ConcurrentHashMap<>();
            sharedState.put("task", task);
            sharedState.put("artifacts", new ArrayList<String>());

            String activeAgent = config.initialAgent();
            AtomicInteger roundCounter = new AtomicInteger(0);
            Instant startTime = Instant.now();
            Map<String, AtomicInteger> agentRounds = new HashMap<>();

            while (activeAgent != null && roundCounter.incrementAndGet() <= config.maxRounds()) {
                SwarmConfig.SwarmAgent agentDef = findAgent(config, activeAgent);
                if (agentDef == null) break;

                agentRounds.computeIfAbsent(activeAgent, k -> new AtomicInteger(0)).incrementAndGet();

                if (config.circuitBreaker().isPresent()) {
                    SwarmConfig.CircuitBreaker cb = config.circuitBreaker().get();
                    if (roundCounter.get() > cb.maxConsecutiveRounds()) {
                        sink.tryEmitNext(new AgentEvent.ErrorEvent("Circuit breaker: max consecutive rounds exceeded", true));
                        sink.tryEmitComplete();
                        return;
                    }
                    if (Duration.between(startTime, Instant.now()).toMillis() > cb.maxDurationMs()) {
                        sink.tryEmitNext(new AgentEvent.ErrorEvent("Circuit breaker: max duration exceeded", true));
                        sink.tryEmitComplete();
                        return;
                    }
                }

                String systemPrompt = buildAgentPrompt(agentDef, sharedState);
                List<com.chorus.engine.core.event.ChatMessage> messages = List.of(
                    com.chorus.engine.core.event.ChatMessage.user(task)
                );

                LoopOptions options = new LoopOptions(
                    chatModel, model, agentDef.tools(), messages, systemPrompt,
                    threadId + "-" + activeAgent, hitlGate,
                    com.chorus.engine.core.ApprovalPolicy.AUTO_EDIT,
                    checkpointer, 50, Optional.empty(), List.of(), null, 120_000, null,
                    Optional.empty()
                );

                AgentLoop loop = new AgentLoop(options);
                loop.run().blockLast();

                if (!agentDef.handoffDestinations().isEmpty()) {
                    activeAgent = agentDef.handoffDestinations().get(0);
                } else {
                    activeAgent = null;
                }
            }

            sink.tryEmitComplete();
        } catch (Exception e) {
            sink.tryEmitNext(new AgentEvent.ErrorEvent(e.getMessage(), true));
            sink.tryEmitComplete();
        }
    }

    private SwarmConfig.SwarmAgent findAgent(SwarmConfig config, String name) {
        return config.agents().stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
    }

    private String buildAgentPrompt(SwarmConfig.SwarmAgent agent, Map<String, Object> sharedState) {
        return agent.systemPrompt() + "\n\nShared state:\n" + sharedState.toString();
    }
}
