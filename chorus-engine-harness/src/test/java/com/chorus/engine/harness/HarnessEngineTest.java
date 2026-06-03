package com.chorus.engine.harness;

import com.chorus.engine.agent.hitl.HitlGate;
import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class HarnessEngineTest {

    @Test
    void nullConfigRejection() {
        assertThatThrownBy(() -> new HarnessEngine(
            null,
            fakeLlmClient("hi"),
            fakeAgentLoop(fakeLlmClient("hi")),
            null,
            Path.of(".").toAbsolutePath()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullLlmClientRejection() {
        assertThatThrownBy(() -> new HarnessEngine(
            HarnessConfig.defaults(Path.of(".").toAbsolutePath()),
            null,
            fakeAgentLoop(fakeLlmClient("hi")),
            null,
            Path.of(".").toAbsolutePath()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullAgentLoopRejection() {
        assertThatThrownBy(() -> new HarnessEngine(
            HarnessConfig.defaults(Path.of(".").toAbsolutePath()),
            fakeLlmClient("hi"),
            null,
            null,
            Path.of(".").toAbsolutePath()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullWorkspaceRejection() {
        assertThatThrownBy(() -> new HarnessEngine(
            HarnessConfig.defaults(Path.of(".").toAbsolutePath()),
            fakeLlmClient("hi"),
            fakeAgentLoop(fakeLlmClient("hi")),
            null,
            null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeSyncAnswerOnlyTask(@TempDir Path workspace) {
        LlmClient llmClient = fakeLlmClient("The answer is 4.");
        AgentLoop agentLoop = fakeAgentLoop(llmClient);
        HarnessConfig config = HarnessConfig.builder()
            .projectMemoryPath(workspace.resolve(".chorus/project-memory.json"))
            .approvalLogPath(workspace.resolve(".chorus/approval-log.ndjson"))
            .trajectoryLogPath(workspace.resolve(".chorus/trajectory.jsonl"))
            .enableSemanticRouting(false)
            .semanticConfidenceThreshold(0.55)
            .build();

        HarnessEngine engine = new HarnessEngine(config, llmClient, agentLoop, null, workspace);

        var result = engine.executeSync("What is 2+2?", "", "You are a math tutor.", ExecutionMode.PLAN);

        assertThat(result.isOk()).isTrue();
        CompletedTaskExecution completed = result.unwrap();
        assertThat(completed.task().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.verification().ok()).isTrue();

        agentLoop.close();
    }

    @Test
    void metricsReflectExecution(@TempDir Path workspace) {
        LlmClient llmClient = fakeLlmClient("Hello.");
        AgentLoop agentLoop = fakeAgentLoop(llmClient);
        HarnessConfig config = HarnessConfig.builder()
            .projectMemoryPath(workspace.resolve(".chorus/project-memory.json"))
            .approvalLogPath(workspace.resolve(".chorus/approval-log.ndjson"))
            .trajectoryLogPath(workspace.resolve(".chorus/trajectory.jsonl"))
            .enableSemanticRouting(false)
            .build();

        HarnessEngine engine = new HarnessEngine(config, llmClient, agentLoop, null, workspace);

        HarnessMetrics before = engine.metrics();
        assertThat(before.tasksStarted()).isEqualTo(0);
        assertThat(before.tasksCompleted()).isEqualTo(0);

        engine.executeSync("Say hello", "", "You are helpful.", ExecutionMode.PLAN);

        HarnessMetrics after = engine.metrics();
        assertThat(after.tasksStarted()).isEqualTo(1);
        assertThat(after.tasksCompleted()).isEqualTo(1);

        agentLoop.close();
    }

    @Test
    void runHistoryRecordsExecution(@TempDir Path workspace) {
        LlmClient llmClient = fakeLlmClient("Done.");
        AgentLoop agentLoop = fakeAgentLoop(llmClient);
        HarnessConfig config = HarnessConfig.builder()
            .projectMemoryPath(workspace.resolve(".chorus/project-memory.json"))
            .approvalLogPath(workspace.resolve(".chorus/approval-log.ndjson"))
            .trajectoryLogPath(workspace.resolve(".chorus/trajectory.jsonl"))
            .enableSemanticRouting(false)
            .build();

        HarnessEngine engine = new HarnessEngine(config, llmClient, agentLoop, null, workspace);

        engine.executeSync("Do a thing", "", "You are helpful.", ExecutionMode.PLAN);

        assertThat(engine.runHistory()).isNotEmpty();
        assertThat(engine.runHistory().values().iterator().next().completed().verification().ok()).isTrue();

        agentLoop.close();
    }

    // ------------------------------------------------------------------
    // HITL gate tests
    // ------------------------------------------------------------------

    @Test
    void hitlGateApproveAllowsTaskToComplete(@TempDir Path workspace) throws Exception {
        HitlGate gate = new HitlGate(Duration.ofSeconds(5));
        LlmClient llmClient = fakeLlmClient("Refactored.");
        AgentLoop agentLoop = fakeAgentLoop(llmClient);
        HarnessConfig config = HarnessConfig.builder()
            .projectMemoryPath(workspace.resolve(".chorus/project-memory.json"))
            .approvalLogPath(workspace.resolve(".chorus/approval-log.ndjson"))
            .trajectoryLogPath(workspace.resolve(".chorus/trajectory.jsonl"))
            .enableSemanticRouting(false)
            .hitlGate(gate)
            .build();

        HarnessEngine engine = new HarnessEngine(config, llmClient, agentLoop, null, workspace);

        // Capture the gateId from the ApprovalRequired event so we can approve it.
        CountDownLatch approved = new CountDownLatch(1);
        AtomicReference<String> capturedGateId = new AtomicReference<>();

        // "refactor authentication across the codebase" routes to PARALLEL_MULTI_WORKER_PATH
        var publisher = engine.execute(
            "refactor authentication across the codebase", "", "You are helpful.", ExecutionMode.BUILD);

        publisher.subscribe(new Flow.Subscriber<>() {
            Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) { this.sub = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(HarnessEvent event) {
                if (event instanceof HarnessEvent.ApprovalRequired req) {
                    String gateId = (String) req.args().get("gateId");
                    capturedGateId.set(gateId);
                    gate.approve(gateId);
                    approved.countDown();
                }
            }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        });

        assertThat(approved.await(6, TimeUnit.SECONDS))
            .as("ApprovalRequired event must be emitted and gate approved within timeout")
            .isTrue();
        assertThat(capturedGateId.get()).startsWith("hitl-task-");

        agentLoop.close();
        engine.close();
    }

    @Test
    void hitlGateTimeoutRejectsTask(@TempDir Path workspace) throws Exception {
        // Gate with a short timeout so the task fails without a human approver.
        HitlGate gate = new HitlGate(Duration.ofMillis(200));
        LlmClient llmClient = fakeLlmClient("Done.");
        AgentLoop agentLoop = fakeAgentLoop(llmClient);
        HarnessConfig config = HarnessConfig.builder()
            .projectMemoryPath(workspace.resolve(".chorus/project-memory.json"))
            .approvalLogPath(workspace.resolve(".chorus/approval-log.ndjson"))
            .trajectoryLogPath(workspace.resolve(".chorus/trajectory.jsonl"))
            .enableSemanticRouting(false)
            .hitlGate(gate)
            .build();

        HarnessEngine engine = new HarnessEngine(config, llmClient, agentLoop, null, workspace);

        // Nobody approves the gate → it times out → task must fail
        var result = engine.executeSync(
            "refactor authentication across the codebase", "", "You are helpful.", ExecutionMode.BUILD);

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().task().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.unwrap().verification().findings())
            .anyMatch(f -> f.contains("HITL") || f.contains("approval"));

        agentLoop.close();
        engine.close();
    }

    @Test
    void hitlGateNotTriggeredForAnswerOnlyTasks(@TempDir Path workspace) {
        HitlGate gate = new HitlGate(Duration.ofMillis(1)); // would time out instantly if triggered
        // Response must include a verification keyword to satisfy BUILD-mode criteria.
        LlmClient llmClient = fakeLlmClient("The answer is 42. No build or tests needed.");
        AgentLoop agentLoop = fakeAgentLoop(llmClient);
        HarnessConfig config = HarnessConfig.builder()
            .projectMemoryPath(workspace.resolve(".chorus/project-memory.json"))
            .approvalLogPath(workspace.resolve(".chorus/approval-log.ndjson"))
            .trajectoryLogPath(workspace.resolve(".chorus/trajectory.jsonl"))
            .enableSemanticRouting(false)
            .hitlGate(gate)
            .build();

        HarnessEngine engine = new HarnessEngine(config, llmClient, agentLoop, null, workspace);

        // Simple Q&A routes to ANSWER_ONLY + DIRECT_AGENT_PATH — no HITL required
        var result = engine.executeSync("What is 2+2?", "", "You are helpful.", ExecutionMode.BUILD);

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().task().status()).isEqualTo(TaskStatus.COMPLETED);

        agentLoop.close();
        engine.close();
    }

    @Test
    void closeAllDisposesHitlGate(@TempDir Path workspace) {
        HitlGate gate = new HitlGate();
        LlmClient llmClient = fakeLlmClient("ok");
        AgentLoop agentLoop = fakeAgentLoop(llmClient);
        HarnessConfig config = HarnessConfig.builder()
            .projectMemoryPath(workspace.resolve(".chorus/project-memory.json"))
            .approvalLogPath(workspace.resolve(".chorus/approval-log.ndjson"))
            .trajectoryLogPath(workspace.resolve(".chorus/trajectory.jsonl"))
            .enableSemanticRouting(false)
            .hitlGate(gate)
            .build();

        HarnessEngine engine = new HarnessEngine(config, llmClient, agentLoop, null, workspace);
        assertThat(gate.isDisposed()).isFalse();

        engine.closeAll();

        assertThat(gate.isDisposed()).isTrue();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static LlmClient fakeLlmClient(String responseText) {
        return new LlmClient() {
            @Override
            public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        for (int i = 0; i < responseText.length(); i++) {
                            subscriber.onNext(new StreamEvent.Token(
                                responseText.substring(i, i + 1), i, null));
                        }
                        subscriber.onNext(new StreamEvent.Finish("stop", 1, 1));
                        subscriber.onComplete();
                    }
                    @Override
                    public void cancel() {}
                });
            }

            @Override
            public com.chorus.engine.llm.ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
                return new com.chorus.engine.llm.ChatResponse(
                    "id", "model", "fake",
                    com.chorus.engine.core.context.Message.assistant(responseText),
                    new com.chorus.engine.core.context.TokenCount(1, 1, "fake"),
                    Duration.ZERO, "stop", null, null, Map.of()
                );
            }

            @Override
            public HealthStatus health() { return HealthStatus.HEALTHY; }

            @Override
            public String providerName() { return "fake"; }
        };
    }

    private static AgentLoop fakeAgentLoop(LlmClient llmClient) {
        return new AgentLoop(
            "test-agent",
            "You are a test agent.",
            llmClient,
            "fake-model",
            0.0,
            4096,
            5,
            List.of(),
            null,
            Executors.newSingleThreadExecutor()
        );
    }
}
