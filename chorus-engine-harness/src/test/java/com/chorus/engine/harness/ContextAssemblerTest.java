package com.chorus.engine.harness;

import com.chorus.engine.core.context.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    @Test
    void createContextBundle() {
        TaskRecord task = new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), List.of());
        RepoIntelligence repo = new RepoIntelligence("v1-123", "Java project", "gradle",
            List.of("Java"), List.of(), List.of(), List.of(), 5, Instant.now());
        ProjectMemory memory = new ProjectMemory(0, "/tmp", List.of(), List.of(), List.of(), Instant.now());

        ContextBundle bundle = ContextAssembler.createContextBundle(
            "base prompt", task, List.of(), List.of(), List.of(), List.of(), repo, memory);

        assertThat(bundle.id()).isEqualTo("ctx-t1");
        assertThat(bundle.prefixHash()).isNotBlank();
        assertThat(bundle.repoFactsVersion()).isEqualTo("v1-123");
        assertThat(bundle.toolSchemaVersion()).isEqualTo("v1");
    }

    @Test
    void buildRuntimePromptContainsAllSections() {
        TaskRecord task = new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), List.of());
        RepoIntelligence repo = new RepoIntelligence("v1", "Java", "gradle",
            List.of("Java"), List.of("README.md"), List.of("./gradlew test"),
            List.of("junit"), 10, Instant.now());
        ProjectMemory memory = new ProjectMemory(0, "/tmp",
            List.of("Use Java 21"), List.of("Issue #1"), List.of(), Instant.now());
        ExecutionProtocol protocol = new ExecutionProtocol(ExecutionMode.BUILD, TaskKind.ANSWER_ONLY,
            List.of(ExecutionStage.CLASSIFIED, ExecutionStage.EDITED, ExecutionStage.FINALIZED),
            false, false, false, false, List.of(), "direct_execution",
            List.of("Summarize"));
        ContextBundle bundle = new ContextBundle("ctx-1", "hash", "delta", "v1", null, "v1");

        String prompt = ContextAssembler.buildRuntimePrompt(
            "You are helpful.", task, "FOREGROUND_SYNC / DIRECT_AGENT_PATH",
            bundle, List.of(), protocol, repo, memory);

        assertThat(prompt).contains("You are helpful.");
        assertThat(prompt).contains("TASK");
        assertThat(prompt).contains("t1");
        assertThat(prompt).contains("PROTOCOL");
        assertThat(prompt).contains("REPO");
        assertThat(prompt).contains("Java");
        assertThat(prompt).contains("PREVIOUS DECISIONS");
        assertThat(prompt).contains("Use Java 21");
        assertThat(prompt).contains("KNOWN ISSUES");
        assertThat(prompt).contains("Issue #1");
        assertThat(prompt).contains("INSTRUCTION");
        assertThat(prompt).contains("Summarize");
    }
}
