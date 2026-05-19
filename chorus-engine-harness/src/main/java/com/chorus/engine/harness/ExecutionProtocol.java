package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Protocol defining how a task of a given kind must be executed.
 */
public record ExecutionProtocol(
    @NonNull ExecutionMode mode,
    @NonNull TaskKind kind,
    @NonNull List<ExecutionStage> stages,
    boolean requiresPlan,
    boolean requiresPatchDiscipline,
    boolean requiresVerification,
    boolean requiresSelfReview,
    @NonNull List<String> suggestedChecks,
    @NonNull String delegationPolicy,
    @NonNull List<String> finalResponseContract
) {}
