package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Assembles context bundles for worker execution.
 * Computes hashes, builds task deltas, and constructs runtime prompts.
 */
public final class ContextAssembler {

    private static final String TOOL_SCHEMA_VERSION = "v1";

    private ContextAssembler() {}

    public static @NonNull ContextBundle createContextBundle(
        @NonNull String basePrompt,
        @NonNull TaskRecord task,
        @NonNull List<? extends com.chorus.engine.core.context.Message> messages,
        @NonNull List<String> toolNames,
        @NonNull List<String> subagentNames,
        @NonNull List<WorkerAssignment> workerAssignments,
        @NonNull RepoIntelligence repoIntelligence,
        @NonNull ProjectMemory projectMemory
    ) {
        String taskDelta = buildTaskDelta(task, messages);
        String prefixHash = sha256(basePrompt + taskDelta);
        String repoFactsVersion = repoIntelligence.version();

        return new ContextBundle(
            "ctx-" + task.taskId(),
            prefixHash,
            taskDelta,
            repoFactsVersion,
            null,
            TOOL_SCHEMA_VERSION
        );
    }

    public static @NonNull String buildRuntimePrompt(
        @NonNull String basePrompt,
        @NonNull TaskRecord task,
        @NonNull String routeDescription,
        @NonNull ContextBundle contextBundle,
        @NonNull List<WorkerAssignment> workerAssignments,
        @NonNull ExecutionProtocol protocol,
        @NonNull RepoIntelligence repoIntelligence,
        @NonNull ProjectMemory projectMemory
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt).append("\n\n");

        sb.append("=== TASK ===\n");
        sb.append("ID: ").append(task.taskId()).append("\n");
        sb.append("Kind: ").append(task.path()).append("\n");
        sb.append("Route: ").append(routeDescription).append("\n");
        sb.append("Mode: ").append(protocol.mode()).append("\n\n");

        sb.append("=== PROTOCOL ===\n");
        sb.append("Stages: ").append(protocol.stages()).append("\n");
        sb.append("Requires plan: ").append(protocol.requiresPlan()).append("\n");
        sb.append("Requires verification: ").append(protocol.requiresVerification()).append("\n");
        sb.append("Delegation: ").append(protocol.delegationPolicy()).append("\n");
        sb.append("Checks: ").append(protocol.suggestedChecks()).append("\n\n");

        sb.append("=== REPO ===\n");
        sb.append(repoIntelligence.summary()).append("\n");
        sb.append("Languages: ").append(String.join(", ", repoIntelligence.languages())).append("\n");
        sb.append("Package manager: ").append(repoIntelligence.packageManager() != null ? repoIntelligence.packageManager() : "unknown").append("\n");
        sb.append("Test signals: ").append(String.join(", ", repoIntelligence.testSignals())).append("\n");
        sb.append("Important files: ").append(String.join(", ", repoIntelligence.importantFiles())).append("\n\n");

        if (!projectMemory.decisions().isEmpty()) {
            sb.append("=== PREVIOUS DECISIONS ===\n");
            for (String decision : projectMemory.decisions()) {
                sb.append("- ").append(decision).append("\n");
            }
            sb.append("\n");
        }

        if (!projectMemory.knownIssues().isEmpty()) {
            sb.append("=== KNOWN ISSUES ===\n");
            for (String issue : projectMemory.knownIssues()) {
                sb.append("- ").append(issue).append("\n");
            }
            sb.append("\n");
        }

        if (!workerAssignments.isEmpty()) {
            sb.append("=== WORKERS ===\n");
            for (WorkerAssignment wa : workerAssignments) {
                sb.append("- ").append(wa.role()).append(" [").append(wa.status()).append("]\n");
            }
            sb.append("\n");
        }

        sb.append("=== CONTEXT BUNDLE ===\n");
        sb.append("ID: ").append(contextBundle.id()).append("\n");
        sb.append("Prefix hash: ").append(contextBundle.prefixHash()).append("\n");
        sb.append("Repo facts: ").append(contextBundle.repoFactsVersion()).append("\n\n");

        sb.append("=== INSTRUCTION ===\n");
        sb.append("Follow the protocol stages. Produce a response that satisfies the final contract:\n");
        for (String contract : protocol.finalResponseContract()) {
            sb.append("- ").append(contract).append("\n");
        }

        return sb.toString();
    }

    private static @NonNull String buildTaskDelta(
        @NonNull TaskRecord task,
        @NonNull List<? extends com.chorus.engine.core.context.Message> messages
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(task.taskId()).append("|").append(task.path()).append("|");
        for (com.chorus.engine.core.context.Message m : messages) {
            sb.append(m.role()).append(":").append(m.content().hashCode()).append(";");
        }
        return sb.toString();
    }

    private static @NonNull String sha256(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Base64.getEncoder().encodeToString(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
        }
    }
}
