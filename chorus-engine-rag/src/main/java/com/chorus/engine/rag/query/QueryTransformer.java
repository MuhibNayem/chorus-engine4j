package com.chorus.engine.rag.query;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Transforms a user query before retrieval to improve recall.
 *
 * <p>2026 strategies:
 * <ul>
 *   <li>HyDE — generate hypothetical answer, embed that instead of question</li>
 *   <li>MultiQuery — generate 3-5 reformulations, merge results</li>
 *   <li>StepBack — reformulate specific → general principle</li>
 *   <li>QueryRewriting — conversational context → standalone query</li>
 * </ul>
 */
public interface QueryTransformer {

    @NonNull List<String> transform(@NonNull String query, @NonNull TransformContext context);

    record TransformContext(
        @NonNull List<String> conversationHistory,
        @NonNull String domain
    ) {}
}
