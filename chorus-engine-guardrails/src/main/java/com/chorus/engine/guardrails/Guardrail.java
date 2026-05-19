package com.chorus.engine.guardrails;

import org.jspecify.annotations.NonNull;

/**
 * A single guardrail evaluator.
 */
public interface Guardrail {

    @NonNull String name();

    int tier(); // 1 = fast, 2 = ML, 3 = LLM

    /**
     * Evaluate the input. Must be thread-safe.
     */
    @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext context);

    /**
     * Whether this guardrail supports output validation (post-LLM).
     */
    default boolean supportsOutputValidation() { return false; }

    record GuardrailContext(
        @NonNull String runId,
        @NonNull String agentId,
        @NonNull String stage // "input", "output", "tool_call"
    ) {}
}
