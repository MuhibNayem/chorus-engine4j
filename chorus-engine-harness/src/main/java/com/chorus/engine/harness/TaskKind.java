package com.chorus.engine.harness;

/**
 * The kind of task being executed — drives routing and worker selection.
 */
public enum TaskKind {
    ANSWER_ONLY,
    INSPECT_ONLY,
    SINGLE_FILE_EDIT,
    MULTI_FILE_EDIT,
    DEBUG,
    RESEARCH,
    PROJECT_PHASE
}
