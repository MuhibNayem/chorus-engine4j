package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link ExecutionProtocol} for a task based on its route and repo context.
 */
public final class ExecutionProtocolBuilder {

    private ExecutionProtocolBuilder() {}

    public static @NonNull ExecutionProtocol build(
        @NonNull TaskRoute route,
        @NonNull RepoIntelligence repoIntelligence,
        @NonNull ExecutionMode mode
    ) {
        List<ExecutionStage> stages = new ArrayList<>();
        stages.add(ExecutionStage.CLASSIFIED);

        boolean requiresPlan = route.kind() != TaskKind.ANSWER_ONLY
            && route.kind() != TaskKind.INSPECT_ONLY
            && route.path() != TaskPath.CACHE_AMPLIFIED_PATH;
        boolean requiresPatchDiscipline = route.path() == TaskPath.PARALLEL_MULTI_WORKER_PATH
            || route.kind() == TaskKind.SINGLE_FILE_EDIT
            || route.kind() == TaskKind.MULTI_FILE_EDIT;
        boolean requiresVerification = mode == ExecutionMode.BUILD
            && route.kind() != TaskKind.ANSWER_ONLY
            && route.kind() != TaskKind.INSPECT_ONLY;
        boolean requiresSelfReview = route.canParallelize()
            || route.kind() == TaskKind.MULTI_FILE_EDIT;

        if (route.requiresResearch()) {
            stages.add(ExecutionStage.INSPECTED);
        }
        if (requiresPlan) {
            stages.add(ExecutionStage.PLANNED);
        }
        if (route.canParallelize()) {
            stages.add(ExecutionStage.ADVISED);
        }
        stages.add(ExecutionStage.EDITED);
        if (requiresVerification) {
            stages.add(ExecutionStage.VERIFIED);
        }
        if (requiresSelfReview) {
            stages.add(ExecutionStage.REVIEWED);
        }
        stages.add(ExecutionStage.FINALIZED);

        List<String> suggestedChecks = new ArrayList<>();
        if (!repoIntelligence.testSignals().isEmpty()) {
            suggestedChecks.add("Run tests: " + String.join(", ", repoIntelligence.testSignals()));
        }
        if (!repoIntelligence.commands().isEmpty()) {
            String buildCmd = repoIntelligence.commands().stream()
                .filter(c -> c.contains("build") || c.contains("compile"))
                .findFirst()
                .orElse(null);
            if (buildCmd != null) {
                suggestedChecks.add("Build: " + buildCmd);
            }
        }
        suggestedChecks.add("Verify no syntax errors");
        if (requiresPatchDiscipline) {
            suggestedChecks.add("Review git diff before finalizing");
        }

        String delegationPolicy = route.path() == TaskPath.DIRECT_AGENT_PATH
            ? "direct_execution"
            : route.canParallelize()
                ? "parallel_worker_pool"
                : "single_worker_delegation";

        List<String> finalResponseContract = new ArrayList<>();
        finalResponseContract.add("Summarize what was done");
        if (requiresVerification) {
            finalResponseContract.add("Report verification results");
        }
        if (requiresPatchDiscipline) {
            finalResponseContract.add("List changed files or state no changes");
        }
        if (route.requiresResearch()) {
            finalResponseContract.add("Cite sources or evidence");
        }
        finalResponseContract.add("Highlight any risks or next actions");

        return new ExecutionProtocol(
            mode,
            route.kind(),
            List.copyOf(stages),
            requiresPlan,
            requiresPatchDiscipline,
            requiresVerification,
            requiresSelfReview,
            List.copyOf(suggestedChecks),
            delegationPolicy,
            List.copyOf(finalResponseContract)
        );
    }
}
