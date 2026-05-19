package com.chorus.engine.harness;

/**
 * Status of a task or worker assignment.
 */
public enum TaskStatus {
    QUEUED,
    RUNNING,
    BLOCKED,
    VERIFYING,
    COMPLETED,
    FAILED,
    BACKGROUNDED
}
