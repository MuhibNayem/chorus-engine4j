package com.chorus.engine.llm;

import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.Flow;

/**
 * Provider-agnostic LLM client interface.
 *
 * <p>Uses JDK {@link java.util.concurrent.Flow.Publisher} for streaming — zero
 * external reactive library dependencies. Every implementation must:
 * <ul>
 *   <li>Parse SSE streams incrementally without blocking</li>
 *   <li>Apply per-chunk timeout and retry policy</li>
 *   <li>Report token counts accurately</li>
 *   <li>Support tool calling via unified {@link ToolDefinition}</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * Stream tokens as a reactive publisher. Backpressure-aware.
     */
    Flow.@NonNull Publisher<StreamEvent> stream(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken);

    /**
     * Blocking completion. Convenience wrapper over stream().
     */
    @NonNull ChatResponse complete(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken);

    /**
     * Health check: can we reach this provider right now?
     */
    @NonNull HealthStatus health();

    /**
     * Provider name, e.g. "openai", "anthropic", "gemini".
     */
    @NonNull String providerName();

    enum HealthStatus { HEALTHY, DEGRADED, UNAVAILABLE }
}
