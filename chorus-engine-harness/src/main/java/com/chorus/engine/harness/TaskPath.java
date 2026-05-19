package com.chorus.engine.harness;

/**
 * The execution path a task takes through the harness pipeline.
 */
public enum TaskPath {
    DIRECT_AGENT_PATH,
    TOOL_OR_SINGLE_WORKER_PATH,
    PARALLEL_MULTI_WORKER_PATH,
    RESEARCH_THEN_PLAN_PATH,
    BACKGROUND_OR_BATCH_PATH,
    CACHE_AMPLIFIED_PATH
}
