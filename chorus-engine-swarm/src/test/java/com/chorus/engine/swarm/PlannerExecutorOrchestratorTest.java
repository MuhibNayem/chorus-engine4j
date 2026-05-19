package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import com.chorus.engine.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;

class PlannerExecutorOrchestratorTest {

    @Test
    void run_withValidPlan_executesAllStepsAndCompletes() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        // Planner response with 2 steps
        fake.enqueue(new ChatResponse(
            "p1", "model", "fake", Message.assistant("1. Research topic\n2. Summarize findings"),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        // Step 1 executor response
        fake.enqueue(new ChatResponse(
            "s1", "model", "fake", Message.assistant("Research result"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        // Step 2 executor response
        fake.enqueue(new ChatResponse(
            "s2", "model", "fake", Message.assistant("Summary done"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition planner = new AgentDefinition(
            "planner", "You are the planner.", List.of(), "model", null, Map.of()
        );
        AgentDefinition worker = new AgentDefinition(
            "worker", "You handle research and summarization tasks.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("planner", planner, "worker", worker);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            PlannerExecutorOrchestrator orchestrator = new PlannerExecutorOrchestrator(
                agents, "planner", fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("planner",
                List.of(Message.user("Research and summarize AI")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.AgentStart as &&
                as.agentName().equals("planner"));
            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.AgentStart as &&
                as.agentName().equals("worker"));
            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete sc &&
                sc.finalResponse().content().equals("Summary done"));
            assertThat(session.activeAgent()).isEqualTo("worker");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void run_withEmptyPlan_emitsSwarmError() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        fake.enqueue(new ChatResponse(
            "p1", "model", "fake", Message.assistant("I cannot create a plan for this."),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition planner = new AgentDefinition(
            "planner", "You are the planner.", List.of(), "model", null, Map.of()
        );
        AgentDefinition worker = new AgentDefinition(
            "worker", "You do work.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("planner", planner, "worker", worker);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            PlannerExecutorOrchestrator orchestrator = new PlannerExecutorOrchestrator(
                agents, "planner", fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("planner",
                List.of(Message.user("Do something")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmError se &&
                se.error().getMessage().contains("empty plan"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void run_stepParsing_edgeCases() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        // Lines: valid, missing content, no space after dot, plain text, valid with extra spaces
        fake.enqueue(new ChatResponse(
            "p1", "model", "fake", Message.assistant("1. Step one\n2.\n3.Step three\nplain text\n4.  Step four  "),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        fake.enqueue(new ChatResponse(
            "s1", "model", "fake", Message.assistant("Result one"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        fake.enqueue(new ChatResponse(
            "s2", "model", "fake", Message.assistant("Result four"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition planner = new AgentDefinition(
            "planner", "You are the planner.", List.of(), "model", null, Map.of()
        );
        AgentDefinition worker = new AgentDefinition(
            "worker", "You do work.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("planner", planner, "worker", worker);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            PlannerExecutorOrchestrator orchestrator = new PlannerExecutorOrchestrator(
                agents, "planner", fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("planner",
                List.of(Message.user("Test parsing")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            // Only 2 valid steps: "Step one" and "Step four"
            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmComplete sc &&
                sc.finalResponse().content().equals("Result four"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void run_agentTimeout_emitsSwarmError() throws Exception {
        BlockingFakeLlmClient fake = new BlockingFakeLlmClient();

        // Planner response
        fake.enqueueComplete(new ChatResponse(
            "p1", "model", "fake", Message.assistant("1. Do something slow"),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition planner = new AgentDefinition(
            "planner", "You are the planner.", List.of(), "model", null, Map.of()
        );
        AgentDefinition worker = new AgentDefinition(
            "worker", "You do work.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("planner", planner, "worker", worker);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofMillis(100), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            PlannerExecutorOrchestrator orchestrator = new PlannerExecutorOrchestrator(
                agents, "planner", fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("planner",
                List.of(Message.user("Slow task")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmError se &&
                se.error() instanceof TimeoutException);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void constructor_rejectsNulls() {
        AgentDefinition agent = new AgentDefinition(
            "a", "inst", List.of(), "m", null, Map.of()
        );
        Map<String, AgentDefinition> agents = Map.of("a", agent);
        FakeLlmClient fake = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(10, Duration.ofSeconds(1), false, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            assertThatNullPointerException().isThrownBy(() ->
                new PlannerExecutorOrchestrator(null, "a", fake, registry, config, executor)
            ).withMessageContaining("agents");

            assertThatThrownBy(() ->
                new PlannerExecutorOrchestrator(agents, "missing", fake, registry, config, executor)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("Planner not found");

            assertThatNullPointerException().isThrownBy(() ->
                new PlannerExecutorOrchestrator(agents, "a", null, registry, config, executor)
            ).withMessageContaining("llmClient");

            assertThatNullPointerException().isThrownBy(() ->
                new PlannerExecutorOrchestrator(agents, "a", fake, null, config, executor)
            ).withMessageContaining("toolRegistry");

            assertThatNullPointerException().isThrownBy(() ->
                new PlannerExecutorOrchestrator(agents, "a", fake, registry, null, executor)
            ).withMessageContaining("config");

            assertThatNullPointerException().isThrownBy(() ->
                new PlannerExecutorOrchestrator(agents, "a", fake, registry, config, null)
            ).withMessageContaining("executor");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void run_maxTurns_enforced() throws Exception {
        FakeLlmClient fake = new FakeLlmClient();

        fake.enqueue(new ChatResponse(
            "p1", "model", "fake", Message.assistant("1. Step one\n2. Step two\n3. Step three"),
            new TokenCount(5, 5, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        fake.enqueue(new ChatResponse(
            "s1", "model", "fake", Message.assistant("Result one"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        fake.enqueue(new ChatResponse(
            "s2", "model", "fake", Message.assistant("Result two"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        // Step 3 response never consumed because maxTurns=2
        fake.enqueue(new ChatResponse(
            "s3", "model", "fake", Message.assistant("Result three"),
            new TokenCount(3, 3, "test"), Duration.ZERO, "stop", null, null, Map.of()
        ));

        AgentDefinition planner = new AgentDefinition(
            "planner", "You are the planner.", List.of(), "model", null, Map.of()
        );
        AgentDefinition worker = new AgentDefinition(
            "worker", "You do work.", List.of(), "model", null, Map.of()
        );

        Map<String, AgentDefinition> agents = Map.of("planner", planner, "worker", worker);
        ToolRegistry registry = new ToolRegistry();
        SwarmConfig config = new SwarmConfig(2, Duration.ofSeconds(30), false, false);
        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            PlannerExecutorOrchestrator orchestrator = new PlannerExecutorOrchestrator(
                agents, "planner", fake, registry, config, executor
            );

            SwarmSession session = orchestrator.createSession("planner",
                List.of(Message.user("Many steps")));
            List<SwarmEvent> events = collectEvents(orchestrator.run(session, CancellationToken.create()));

            assertThat(events).anyMatch(e -> e instanceof SwarmEvent.SwarmError se &&
                se.error().getMessage().contains("Max turns reached"));
            assertThat(session.turnCount()).isEqualTo(2);
        } finally {
            executor.shutdown();
        }
    }

    private List<SwarmEvent> collectEvents(Flow.Publisher<SwarmEvent> publisher) throws Exception {
        List<SwarmEvent> events = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(SwarmEvent event) { events.add(event); }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(null); }
        });
        done.get(10, TimeUnit.SECONDS);
        return events;
    }

    /**
     * Fake LLM client where {@code complete()} uses a normal queue but {@code stream()}
     * blocks forever (never calls onComplete/onError) so that agent invocation times out.
     */
    private static final class BlockingFakeLlmClient implements LlmClient {
        private final BlockingQueue<ChatResponse> completeResponses = new LinkedBlockingQueue<>();

        void enqueueComplete(ChatResponse response) {
            completeResponses.add(response);
        }

        @Override
        public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            ChatResponse response = completeResponses.poll();
            if (response == null) {
                throw new IllegalStateException("No fake response enqueued for complete()");
            }
            return response;
        }

        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {}
                @Override public void cancel() {}
            });
            // Never calls onNext/onComplete/onError -> blocks forever
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.HEALTHY;
        }

        @Override
        public String providerName() {
            return "blocking-fake";
        }
    }
}
