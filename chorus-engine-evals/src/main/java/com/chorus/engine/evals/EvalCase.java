package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Objects;

/**
 * Single evaluation test case.
 *
 * @param id             Unique identifier for this case
 * @param input          Input prompt or question
 * @param expectedOutput Expected correct output
 * @param metadata       Arbitrary metadata for filtering and reporting
 */
public record EvalCase(
    @NonNull String id,
    @NonNull String input,
    @NonNull String expectedOutput,
    @NonNull Map<String, Object> metadata
) {
    public EvalCase {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(expectedOutput, "expectedOutput cannot be null");
        metadata = Map.copyOf(metadata);
    }
}
