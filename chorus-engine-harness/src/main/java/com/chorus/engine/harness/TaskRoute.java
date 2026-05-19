package com.chorus.engine.harness;

/**
 * The computed route for a task — kind, lane, path, and derived flags.
 */
public record TaskRoute(
    TaskKind kind,
    ExecutionLane lane,
    TaskPath path,
    boolean requiresResearch,
    boolean canParallelize,
    boolean usesCheapTriage
) {}
