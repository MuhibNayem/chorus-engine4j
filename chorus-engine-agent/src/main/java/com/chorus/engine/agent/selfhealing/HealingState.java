package com.chorus.engine.agent.selfhealing;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mutable state between {@link SelfHealingAgentLoop} (writer) and the
 * {@link HealingMiddleware} / {@link HealingLlmClientProxy} (readers).
 *
 * <p>All fields are individually atomic so readers never observe a partial update.
 * The {@link #takeBackoff()} method atomically consumes the pending delay so it
 * applies exactly once per heal event.
 */
final class HealingState {

    private final AtomicReference<String>  pendingInstruction  = new AtomicReference<>();
    private final AtomicReference<Duration> pendingBackoff     = new AtomicReference<>();
    private final AtomicReference<Double>  temperatureOverride = new AtomicReference<>();
    private final AtomicReference<Integer> maxTokensOverride   = new AtomicReference<>();
    private final AtomicReference<String>  modelOverride       = new AtomicReference<>();

    void apply(SelfHealingAgentLoop.HealAction action) {
        if (action.extraInstruction() != null) pendingInstruction .set(action.extraInstruction());
        if (action.backoffDelay()     != null) pendingBackoff     .set(action.backoffDelay());
        if (action.newTemperature()   != null) temperatureOverride.set(action.newTemperature());
        if (action.newMaxTokens()     != null) maxTokensOverride  .set(action.newMaxTokens());
        if (action.newModel()         != null) modelOverride      .set(action.newModel());
    }

    @Nullable String instruction()          { return pendingInstruction.get(); }
    @Nullable Double temperatureOverride()  { return temperatureOverride.get(); }
    @Nullable Integer maxTokensOverride()   { return maxTokensOverride.get(); }
    @Nullable String modelOverride()        { return modelOverride.get(); }

    /** Atomically retrieves and clears the backoff delay so it fires exactly once. */
    @Nullable Duration takeBackoff() { return pendingBackoff.getAndSet(null); }
}
