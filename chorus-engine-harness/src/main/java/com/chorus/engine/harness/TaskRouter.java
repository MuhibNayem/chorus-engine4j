package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regex-based task router — fast fallback when semantic routing is unavailable
 * or confidence is too low.
 *
 * <p>Uses keyword heuristics to classify tasks into kinds, lanes, and paths.
 */
public final class TaskRouter {

    private static final Pattern RESEARCH_PATTERN = Pattern.compile(
        "\\b(search|look up|find|research|verify|check|latest|current|compare|documentation|docs|release notes)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBUG_PATTERN = Pattern.compile(
        "\\b(fix|debug|investigate|root cause|trace|memory leak|race condition|error|exception|failing|break)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_FILE_PATTERN = Pattern.compile(
        "\\b(refactor|migrate|rename all|restructure|across|every occurrence|all files|batch update|whole project)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_FILE_PATTERN = Pattern.compile(
        "\\b(add|update|remove|implement|fix.*in|change.*file)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern INSPECT_PATTERN = Pattern.compile(
        "\\b(show|list|read|display|contents of|where is|what does)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PROJECT_PHASE_PATTERN = Pattern.compile(
        "\\b(audit|analyze entire|full test suite|index all|batch|generate docs|comprehensive|performance review)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * Route a task using regex-based heuristics.
     */
    public @NonNull TaskRoute route(@NonNull String text, @NonNull String expandedText) {
        String combined = (text + " " + expandedText).toLowerCase(Locale.ROOT);

        if (PROJECT_PHASE_PATTERN.matcher(combined).find()) {
            return new TaskRoute(TaskKind.PROJECT_PHASE, ExecutionLane.BACKGROUND_ASYNC,
                TaskPath.BACKGROUND_OR_BATCH_PATH, false, false, false);
        }
        if (RESEARCH_PATTERN.matcher(combined).find()) {
            return new TaskRoute(TaskKind.RESEARCH, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.RESEARCH_THEN_PLAN_PATH, true, false, false);
        }
        if (DEBUG_PATTERN.matcher(combined).find()) {
            return new TaskRoute(TaskKind.DEBUG, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.TOOL_OR_SINGLE_WORKER_PATH, false, false, false);
        }
        if (MULTI_FILE_PATTERN.matcher(combined).find()) {
            return new TaskRoute(TaskKind.MULTI_FILE_EDIT, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.PARALLEL_MULTI_WORKER_PATH, false, true, false);
        }
        if (SINGLE_FILE_PATTERN.matcher(combined).find()) {
            return new TaskRoute(TaskKind.SINGLE_FILE_EDIT, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.TOOL_OR_SINGLE_WORKER_PATH, false, false, false);
        }
        if (INSPECT_PATTERN.matcher(combined).find()) {
            return new TaskRoute(TaskKind.INSPECT_ONLY, ExecutionLane.CHEAP_TRIAGE,
                TaskPath.DIRECT_AGENT_PATH, false, false, true);
        }

        // Default: simple Q&A
        return new TaskRoute(TaskKind.ANSWER_ONLY, ExecutionLane.CHEAP_TRIAGE,
            TaskPath.DIRECT_AGENT_PATH, false, false, true);
    }

    /**
     * Build verification criteria for a task based on its route and mode.
     */
    public @NonNull List<VerificationCriterion> buildCriteria(
        @NonNull TaskRoute route,
        @NonNull ExecutionMode mode,
        boolean isAgentInvocation
    ) {
        List<VerificationCriterion> criteria = new java.util.ArrayList<>();

        if (route.kind() == TaskKind.RESEARCH || route.requiresResearch()) {
            criteria.add(new VerificationCriterion("freshness",
                "Response must cite sources or show evidence of research."));
        }

        if (mode == ExecutionMode.BUILD && !isAgentInvocation) {
            criteria.add(new VerificationCriterion("verification",
                "Response must report checks run (tests, build, lint) or explain why not."));
        }

        if (route.kind() == TaskKind.SINGLE_FILE_EDIT
            || route.kind() == TaskKind.MULTI_FILE_EDIT
            || route.path() == TaskPath.PARALLEL_MULTI_WORKER_PATH) {
            criteria.add(new VerificationCriterion("diff-review",
                "Response must report changed files or state no files changed."));
        }

        return List.copyOf(criteria);
    }
}
