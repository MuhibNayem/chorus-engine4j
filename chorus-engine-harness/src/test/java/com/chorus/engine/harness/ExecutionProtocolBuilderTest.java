package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionProtocolBuilderTest {

    RepoIntelligence repo = new RepoIntelligence("v1", "Java project", "gradle",
        List.of("Java"), List.of("README.md"), List.of("./gradlew test"),
        List.of("junit"), 10, Instant.now());

    @Test
    void answerOnlyProtocol() {
        TaskRoute route = new TaskRoute(TaskKind.ANSWER_ONLY, ExecutionLane.CHEAP_TRIAGE,
            TaskPath.DIRECT_AGENT_PATH, false, false, true);
        ExecutionProtocol protocol = ExecutionProtocolBuilder.build(route, repo, ExecutionMode.BUILD);

        assertThat(protocol.requiresPlan()).isFalse();
        assertThat(protocol.requiresVerification()).isFalse();
        assertThat(protocol.stages()).containsExactly(
            ExecutionStage.CLASSIFIED, ExecutionStage.EDITED, ExecutionStage.FINALIZED);
    }

    @Test
    void researchProtocol() {
        TaskRoute route = new TaskRoute(TaskKind.RESEARCH, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.RESEARCH_THEN_PLAN_PATH, true, false, false);
        ExecutionProtocol protocol = ExecutionProtocolBuilder.build(route, repo, ExecutionMode.BUILD);

        assertThat(protocol.requiresPlan()).isTrue();
        assertThat(protocol.stages()).contains(ExecutionStage.CLASSIFIED, ExecutionStage.INSPECTED,
            ExecutionStage.PLANNED, ExecutionStage.EDITED, ExecutionStage.FINALIZED);
        assertThat(protocol.finalResponseContract()).anyMatch(c -> c.contains("sources"));
    }

    @Test
    void multiFileEditProtocol() {
        TaskRoute route = new TaskRoute(TaskKind.MULTI_FILE_EDIT, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.PARALLEL_MULTI_WORKER_PATH, false, true, false);
        ExecutionProtocol protocol = ExecutionProtocolBuilder.build(route, repo, ExecutionMode.BUILD);

        assertThat(protocol.requiresPatchDiscipline()).isTrue();
        assertThat(protocol.requiresSelfReview()).isTrue();
        assertThat(protocol.stages()).contains(ExecutionStage.ADVISED, ExecutionStage.REVIEWED);
        assertThat(protocol.delegationPolicy()).isEqualTo("parallel_worker_pool");
    }

    @Test
    void planModeHasNoVerification() {
        TaskRoute route = new TaskRoute(TaskKind.SINGLE_FILE_EDIT, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.TOOL_OR_SINGLE_WORKER_PATH, false, false, false);
        ExecutionProtocol protocol = ExecutionProtocolBuilder.build(route, repo, ExecutionMode.PLAN);

        assertThat(protocol.requiresVerification()).isFalse();
    }

    @Test
    void directAgentDelegationPolicy() {
        TaskRoute route = new TaskRoute(TaskKind.ANSWER_ONLY, ExecutionLane.CHEAP_TRIAGE,
            TaskPath.DIRECT_AGENT_PATH, false, false, true);
        ExecutionProtocol protocol = ExecutionProtocolBuilder.build(route, repo, ExecutionMode.BUILD);
        assertThat(protocol.delegationPolicy()).isEqualTo("direct_execution");
    }
}
