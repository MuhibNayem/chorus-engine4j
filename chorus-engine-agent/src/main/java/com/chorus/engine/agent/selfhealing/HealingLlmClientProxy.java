package com.chorus.engine.agent.selfhealing;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.Flow;

/**
 * Transparent proxy that intercepts every {@link ChatRequest} and overlays
 * temperature, maxTokens, and model from the active {@link HealingState} before
 * forwarding to the real {@link LlmClient}.
 *
 * <p>Overrides persist across rounds until a new heal decision overwrites them,
 * allowing the agent to operate at reduced temperature for the remainder of a run
 * after a hallucination burst, for example.
 */
final class HealingLlmClientProxy implements LlmClient {

    private final LlmClient delegate;
    private final HealingState state;

    HealingLlmClientProxy(@NonNull LlmClient delegate, @NonNull HealingState state) {
        this.delegate = delegate;
        this.state    = state;
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(
            @NonNull ChatRequest request,
            @NonNull CancellationToken token) {
        return delegate.stream(applyOverrides(request), token);
    }

    @Override
    public @NonNull ChatResponse complete(
            @NonNull ChatRequest request,
            @NonNull CancellationToken token) {
        return delegate.complete(applyOverrides(request), token);
    }

    @Override
    public @NonNull HealthStatus health() { return delegate.health(); }

    @Override
    public @NonNull String providerName() { return delegate.providerName(); }

    private @NonNull ChatRequest applyOverrides(@NonNull ChatRequest request) {
        String  model       = state.modelOverride()        != null ? state.modelOverride()        : request.model();
        double  temperature = state.temperatureOverride()  != null ? state.temperatureOverride()  : request.temperature();
        int     maxTokens   = state.maxTokensOverride()    != null ? state.maxTokensOverride()    : request.maxTokens();

        if (model.equals(request.model())
                && temperature == request.temperature()
                && maxTokens   == request.maxTokens()) {
            return request;
        }
        return new ChatRequest(model, request.messages(), request.tools(),
                temperature, maxTokens,
                request.responseFormat(), request.stopSequence(), request.providerExtras());
    }
}
