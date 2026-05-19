package com.chorus.engine.rag.streaming;

/**
 * Strategy controlling when LLM generation starts relative to retrieval waves.
 *
 * <p>All strategies use the same concurrent retrieval infrastructure.
 * The difference is <em>when</em> generation begins and <em>what happens</em>
 * to late-arriving waves.
 */
public enum GenerationStrategy {

    /**
     * Wait for all retrieval waves to complete before starting generation.
     *
     * <p>Guarantees the richest possible context. Latency is the sum of all
     * retrieval latencies plus generation time. Best for use cases where
     * answer quality is more important than time-to-first-token (TTFT).
     */
    WAIT_FOR_ALL,

    /**
     * Start generation as soon as the first retrieval wave completes.
     *
     * <p>Later waves contribute {@link RagStreamEvent.SupplementalContext}
     * events but do <strong>not</strong> modify the in-flight generation.
     * Best for chat interfaces where users want immediate feedback.
     */
    PIPELINE,

    /**
     * Start generation after an adaptive confidence threshold is met.
     *
     * <p>Waits for enough waves / high enough cumulative relevance before
     * starting. Balances speed and quality dynamically. Best for
     * cost-sensitive deployments where low-relevance early waves are common.
     */
    ADAPTIVE
}
