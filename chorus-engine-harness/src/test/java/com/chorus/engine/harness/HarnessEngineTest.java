package com.chorus.engine.harness;

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
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;

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
