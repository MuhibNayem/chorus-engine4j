package com.chorus.engine.harness;

/**
 * Execution lanes — control how a task is scheduled and prioritized.
 */
public enum ExecutionLane {
    FOREGROUND_SYNC,
    BACKGROUND_ASYNC,
    BATCH_OFFLINE,
    CHEAP_TRIAGE
}
