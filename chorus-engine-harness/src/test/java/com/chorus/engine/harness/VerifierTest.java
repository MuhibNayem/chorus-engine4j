package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifierTest {

    Verifier verifier = new Verifier();

    @Test
    void verifyEmptyResponse() {
        TaskRecord task = taskWithCriteria(List.of());
        Verifier.VerifyInput input = new Verifier.VerifyInput(task, "", 0, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isFalse();
        assertThat(result.verification().findings()).anyMatch(f -> f.contains("without producing"));
        assertThat(result.task().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void verifyFreshnessWithoutEvidence() {
        TaskRecord task = taskWithCriteria(List.of(
            new VerificationCriterion("freshness", "Must cite sources")
        ));
        Verifier.VerifyInput input = new Verifier.VerifyInput(task, "Here is the answer", 0, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isFalse();
        assertThat(result.verification().findings()).anyMatch(f -> f.contains("research evidence"));
    }

    @Test
    void verifyFreshnessWithToolCalls() {
        TaskRecord task = taskWithCriteria(List.of(
            new VerificationCriterion("freshness", "Must cite sources")
        ));
        Verifier.VerifyInput input = new Verifier.VerifyInput(task, "Here is the answer", 1, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isTrue();
    }

    @Test
    void verifyMissingVerificationMention() {
        TaskRecord task = taskWithCriteria(List.of(
            new VerificationCriterion("verification", "Must report checks")
        ));
        Verifier.VerifyInput input = new Verifier.VerifyInput(task, "Done", 0, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isFalse();
        assertThat(result.verification().findings()).anyMatch(f -> f.contains("verification"));
    }

    @Test
    void verifyWithTestsMentioned() {
        TaskRecord task = taskWithCriteria(List.of(
            new VerificationCriterion("verification", "Must report checks")
        ));
        Verifier.VerifyInput input = new Verifier.VerifyInput(
            task, "All tests passed successfully", 0, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isTrue();
    }

    @Test
    void verifyDiffReviewWithNoTools() {
        TaskRecord task = taskWithCriteria(List.of(
            new VerificationCriterion("diff-review", "Must report changes")
        ));
        Verifier.VerifyInput input = new Verifier.VerifyInput(task, "Analysis complete", 0, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isTrue();
    }

    @Test
    void verifyDiffReviewWithToolsButNoMention() {
        TaskRecord task = taskWithCriteria(List.of(
            new VerificationCriterion("diff-review", "Must report changes")
        ));
        Verifier.VerifyInput input = new Verifier.VerifyInput(task, "Done", 1, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isFalse();
        assertThat(result.verification().findings()).anyMatch(f -> f.contains("diff review"));
    }

    @Test
    void verifyDiffReviewWithGitMention() {
        TaskRecord task = taskWithCriteria(List.of(
            new VerificationCriterion("diff-review", "Must report changes")
        ));
        Verifier.VerifyInput input = new Verifier.VerifyInput(
            task, "Changed files: app.java, utils.java", 1, false, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isTrue();
    }

    @Test
    void verifyWithError() {
        TaskRecord task = taskWithCriteria(List.of());
        Verifier.VerifyInput input = new Verifier.VerifyInput(task, "Result", 0, true, 1000, 1);
        CompletedTaskExecution result = verifier.verify(input);
        assertThat(result.verification().ok()).isFalse();
        assertThat(result.task().status()).isEqualTo(TaskStatus.FAILED);
    }

    private TaskRecord taskWithCriteria(List<VerificationCriterion> criteria) {
        return new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), criteria);
    }
}
