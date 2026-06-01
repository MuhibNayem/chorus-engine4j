package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.reactive.FlowCollector;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.tools.ToolRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class SubAgentSpawner {

    private final LlmClient llmClient;
    private final String model;
    private final ExecutorService executor;
    private final CliRenderer renderer;
    private final AtomicInteger spawnCounter = new AtomicInteger(0);

    SubAgentSpawner(LlmClient llmClient, String model, ExecutorService executor, CliRenderer renderer) {
        this.llmClient = llmClient;
        this.model = model;
        this.executor = executor;
        this.renderer = renderer;
    }

    record SubAgentResult(String agentId, String task, String result, long durationMs) {}

    void spawnResearch(String task, List<Middleware> middlewares) {
        int id = spawnCounter.incrementAndGet();
        String agentId = "research-agent-" + id;
        renderer.info("Spawning sub-agent " + agentId + " for: " + task);

        AgentLoop subLoop = new AgentLoop(
                agentId,
                "You are a research sub-agent. Your task is to investigate thoroughly and report findings. "
                        + "Use available tools to explore the codebase. Be concise and accurate.",
                llmClient, model, 0.3, 2048, 15, new ArrayList<>(middlewares), null, executor);

        try {
            var future = CompletableFuture.supplyAsync(() -> {
                CancellationToken token = CancellationToken.create();
                var events = subLoop.run("sub-" + agentId, task, List.of(), token);
                try {
                    List<AgentEvent> all = FlowCollector.toList(events, Duration.ofMinutes(2), token);
                    return all.stream()
                            .filter(e -> e instanceof AgentEvent.Done)
                            .map(e -> ((AgentEvent.Done) e).finalAnswer())
                            .findFirst()
                            .orElse("No result");
                } catch (Exception e) {
                    return "Sub-agent error: " + e.getMessage();
                }
            });

            String result = future.get(2, TimeUnit.MINUTES);
            renderer.success("Sub-agent " + agentId + " completed.");
            renderer.text("Result: " + result);
        } catch (Exception e) {
            renderer.error("Sub-agent " + agentId + " failed: " + e.getMessage());
        }
    }
}
