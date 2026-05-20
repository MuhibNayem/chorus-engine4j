package com.chorus.engine.springboot.testsupport;

import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import org.jspecify.annotations.NonNull;

import java.time.Duration;

/**
 * Hand-written fake guardrail for testing.
 */
public class FakeGuardrail implements Guardrail {

    private final @NonNull String name;
    private final int tier;
    private final boolean allow;

    public FakeGuardrail(@NonNull String name, int tier, boolean allow) {
        this.name = name;
        this.tier = tier;
        this.allow = allow;
    }

    @Override
    public @NonNull String name() {
        return name;
    }

    @Override
    public int tier() {
        return tier;
    }

    @Override
    public @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext context) {
        if (allow) {
            return GuardrailResult.allow(name, tier, Duration.ZERO);
        }
        return GuardrailResult.block(name, tier, input, 1.0, Duration.ZERO);
    }
}
