package com.chorus.engine.eval;

import java.util.List;

/**
 * A single evaluation test case.
 */
public record EvalCase(
    String id,
    String input,
    String expectedOutput,
    String rubric,
    List<String> tags
) {
    public EvalCase {
        if (tags == null) {
            tags = List.of();
        }
    }
}
