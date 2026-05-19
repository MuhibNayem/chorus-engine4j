package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRouterTest {

    TaskRouter router = new TaskRouter();

    @Test
    void routeResearchTasks() {
        TaskRoute route = router.route("What is the latest version of React?", "");
        assertThat(route.kind()).isEqualTo(TaskKind.RESEARCH);
        assertThat(route.path()).isEqualTo(TaskPath.RESEARCH_THEN_PLAN_PATH);
        assertThat(route.requiresResearch()).isTrue();
    }

    @Test
    void routeDebugTasks() {
        TaskRoute route = router.route("Fix the null pointer exception", "");
        assertThat(route.kind()).isEqualTo(TaskKind.DEBUG);
    }

    @Test
    void routeMultiFileEdit() {
        TaskRoute route = router.route("Refactor authentication across the codebase", "");
        assertThat(route.kind()).isEqualTo(TaskKind.MULTI_FILE_EDIT);
        assertThat(route.canParallelize()).isTrue();
    }

    @Test
    void routeSingleFileEdit() {
        TaskRoute route = router.route("Add validation to utils.js", "");
        assertThat(route.kind()).isEqualTo(TaskKind.SINGLE_FILE_EDIT);
    }

    @Test
    void routeInspectOnly() {
        TaskRoute route = router.route("Show me the contents of app.ts", "");
        assertThat(route.kind()).isEqualTo(TaskKind.INSPECT_ONLY);
        assertThat(route.usesCheapTriage()).isTrue();
    }

    @Test
    void routeAnswerOnly() {
        TaskRoute route = router.route("What is 2+2?", "");
        assertThat(route.kind()).isEqualTo(TaskKind.ANSWER_ONLY);
    }

    @Test
    void routeProjectPhase() {
        TaskRoute route = router.route("Audit the entire codebase for security", "");
        assertThat(route.kind()).isEqualTo(TaskKind.PROJECT_PHASE);
        assertThat(route.lane()).isEqualTo(ExecutionLane.BACKGROUND_ASYNC);
    }

    @Test
    void buildVerificationCriteriaForResearch() {
        TaskRoute route = new TaskRoute(TaskKind.RESEARCH, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.RESEARCH_THEN_PLAN_PATH, true, false, false);
        List<VerificationCriterion> criteria = router.buildCriteria(route, ExecutionMode.BUILD, false);
        assertThat(criteria).extracting(VerificationCriterion::id).contains("freshness");
    }

    @Test
    void buildVerificationCriteriaForBuild() {
        TaskRoute route = new TaskRoute(TaskKind.SINGLE_FILE_EDIT, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.TOOL_OR_SINGLE_WORKER_PATH, false, false, false);
        List<VerificationCriterion> criteria = router.buildCriteria(route, ExecutionMode.BUILD, false);
        assertThat(criteria).extracting(VerificationCriterion::id).contains("verification", "diff-review");
    }

    @Test
    void noCriteriaForPlanMode() {
        TaskRoute route = new TaskRoute(TaskKind.ANSWER_ONLY, ExecutionLane.CHEAP_TRIAGE,
            TaskPath.DIRECT_AGENT_PATH, false, false, true);
        List<VerificationCriterion> criteria = router.buildCriteria(route, ExecutionMode.PLAN, false);
        assertThat(criteria).isEmpty();
    }
}
