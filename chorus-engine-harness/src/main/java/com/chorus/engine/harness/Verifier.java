package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Verifies that a completed task satisfies its criteria.
 * Checks for empty responses, missing research evidence, missing verification,
 * and missing diff reviews.
 */
public final class Verifier {

    private static final Pattern FRESHNESS_EVIDENCE = Pattern.compile(
        "\\bsource|official|docs?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERIFICATION_MENTION = Pattern.compile(
        "\\b(test|tests|tested|build|built|check|checked|lint|verify|verification|not run|could not run|failed|passed)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DIFF_REVIEW_MENTION = Pattern.compile(
        "\\b(diff|git status|git diff|reviewed changes|changed files|no files changed|no file changes)\\b",
        Pattern.CASE_INSENSITIVE);

    public record VerifyInput(
        @NonNull TaskRecord task,
        @NonNull String responseText,
        int toolCallsObserved,
        boolean hadError,
        long durationMs,
        int modelCalls
    ) {}

    /**
     * Verify a task completion against its declared criteria.
     */
    public @NonNull CompletedTaskExecution verify(@NonNull VerifyInput input) {
        List<String> findings = new ArrayList<>();
        TaskRecord task = input.task();

        if (input.responseText().isBlank() && !input.hadError()) {
            findings.add("The assistant completed without producing a final response.");
        }

        if (hasCriterion(task, "freshness")) {
            boolean hasEvidence = input.toolCallsObserved() > 0
                || FRESHNESS_EVIDENCE.matcher(input.responseText()).find();
            if (!hasEvidence) {
                findings.add(
                    "The task was routed as freshness-sensitive but no research evidence was observed.");
            }
        }

        if (hasCriterion(task, "verification")) {
            boolean mentionsVerification = VERIFICATION_MENTION.matcher(input.responseText()).find();
            if (!mentionsVerification) {
                findings.add(
                    "The task required verification, but the final response did not report checks run or explicitly state why checks were not run.");
            }
        }

        if (hasCriterion(task, "diff-review")) {
            if (input.toolCallsObserved() == 0) {
                // No tools called — nothing to diff. Not a failure.
            } else {
                boolean mentionsDiff = DIFF_REVIEW_MENTION.matcher(input.responseText()).find();
                if (!mentionsDiff) {
                    findings.add(
                        "The task required diff review, but the final response did not report changed files or explicitly state that no files changed.");
                }
            }
        }

        boolean ok = findings.isEmpty() && !input.hadError();
        TaskStatus finalStatus = ok ? TaskStatus.COMPLETED : TaskStatus.FAILED;

        TaskRecord updatedTask = new TaskRecord(
            task.taskId(), task.owner(), task.lane(), task.path(),
            finalStatus, task.createdAt(), java.time.Instant.now(),
            task.verificationCriteria()
        );

        return new CompletedTaskExecution(
            updatedTask,
            new VerificationResult(ok, List.copyOf(findings)),
            input.modelCalls(),
            input.durationMs()
        );
    }

    private boolean hasCriterion(@NonNull TaskRecord task, @NonNull String id) {
        return task.verificationCriteria().stream()
            .anyMatch(c -> c.id().equals(id));
    }
}
