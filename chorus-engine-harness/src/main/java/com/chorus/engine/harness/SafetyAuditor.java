package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Safety auditor for harness executions.
 * Checks for boundary violations, suspicious patterns, and policy compliance.
 *
 * <p>Integrates with the guardrails module — safety checks are run before
 * and after each execution stage.
 */
public final class SafetyAuditor {

    private static final Pattern SENSITIVE_PATH = Pattern.compile(
        "(\\.env|\\.ssh|id_rsa|password|secret|token|api[_-]?key)",
        Pattern.CASE_INSENSITIVE);

    private static final Set<String> DANGEROUS_TOOLS = Set.of(
        "rm", "dd", "mkfs", "fdisk", "format", "del", "rmdir"
    );

    private static final int MAX_RESPONSE_LENGTH = 500_000;
    private static final int MAX_TOOL_CALLS = 100;

    public record SafetyCheck(@NonNull String id, boolean passed, @NonNull String message) {}

    public record SafetyReport(
        @NonNull String taskId,
        boolean safe,
        @NonNull List<SafetyCheck> checks
    ) {}

    /**
     * Audit a task before execution.
     */
    public @NonNull SafetyReport auditPreExecution(@NonNull TaskRecord task, @NonNull String userInput) {
        List<SafetyCheck> checks = new ArrayList<>();

        checks.add(checkInputLength(userInput));
        checks.add(checkSensitivePaths(userInput));
        checks.add(checkTaskComplexity(task));

        boolean safe = checks.stream().allMatch(SafetyCheck::passed);
        return new SafetyReport(task.taskId(), safe, List.copyOf(checks));
    }

    /**
     * Audit a task after execution.
     */
    public @NonNull SafetyReport auditPostExecution(
        @NonNull TaskRecord task,
        @NonNull String responseText,
        int toolCallsObserved,
        @NonNull List<WorkerResult> workerResults
    ) {
        List<SafetyCheck> checks = new ArrayList<>();

        checks.add(checkResponseLength(responseText));
        checks.add(checkToolCallLimit(toolCallsObserved));
        checks.add(checkWorkerFailures(workerResults));
        checks.add(checkSensitivePaths(responseText));

        boolean safe = checks.stream().allMatch(SafetyCheck::passed);
        return new SafetyReport(task.taskId(), safe, List.copyOf(checks));
    }

    private SafetyCheck checkInputLength(@NonNull String input) {
        boolean ok = input.length() < 100_000;
        return new SafetyCheck("input-length", ok,
            ok ? "Input length acceptable" : "Input too long: " + input.length() + " chars");
    }

    private SafetyCheck checkResponseLength(@NonNull String response) {
        boolean ok = response.length() < MAX_RESPONSE_LENGTH;
        return new SafetyCheck("response-length", ok,
            ok ? "Response length acceptable" : "Response too long: " + response.length() + " chars");
    }

    private SafetyCheck checkSensitivePaths(@NonNull String text) {
        boolean found = SENSITIVE_PATH.matcher(text).find();
        return new SafetyCheck("sensitive-paths", !found,
            found ? "Sensitive path or credential pattern detected" : "No sensitive patterns detected");
    }

    private SafetyCheck checkTaskComplexity(@NonNull TaskRecord task) {
        boolean ok = task.verificationCriteria().size() <= 10;
        return new SafetyCheck("task-complexity", ok,
            ok ? "Task complexity acceptable" : "Too many verification criteria: " + task.verificationCriteria().size());
    }

    private SafetyCheck checkToolCallLimit(int count) {
        boolean ok = count <= MAX_TOOL_CALLS;
        return new SafetyCheck("tool-call-limit", ok,
            ok ? "Tool calls within limit" : "Too many tool calls: " + count);
    }

    private SafetyCheck checkWorkerFailures(@NonNull List<WorkerResult> results) {
        int failures = (int) results.stream().filter(r -> r.status() == TaskStatus.FAILED).count();
        boolean ok = failures == 0 || failures < results.size() / 2;
        return new SafetyCheck("worker-failures", ok,
            ok ? "Worker failures acceptable: " + failures + "/" + results.size()
               : "Too many worker failures: " + failures + "/" + results.size());
    }
}
