package com.chorus.engine.guardrails.tier;

import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 3 guardrail using LLM-as-judge.
 * Evaluates input against a policy description using an LLM.
 *
 * <p>Results are cached by input hash to avoid redundant LLM calls.
 * Policy evaluations default to ALLOW on LLM timeout to prevent cascade failures.
 */
public final class LlmJudgeGuardrail implements Guardrail {

    private final String name;
    private final String policyDescription;
    private final double blockThreshold;
    private final LlmJudgeClient client;
    private final ConcurrentHashMap<String, GuardrailResult> cache;

    public interface LlmJudgeClient {
        @NonNull JudgeResult judge(@NonNull String input, @NonNull String policy);

        record JudgeResult(boolean violatesPolicy, double confidence, @NonNull String reasoning) {}
    }

    public LlmJudgeGuardrail(
        @NonNull String name,
        @NonNull String policyDescription,
        double blockThreshold,
        @NonNull LlmJudgeClient client,
        int cacheSize
    ) {
        this.name = name;
        this.policyDescription = policyDescription;
        this.blockThreshold = blockThreshold;
        this.client = client;
        this.cache = new ConcurrentHashMap<>(cacheSize);
    }

    @Override
    public @NonNull String name() { return name; }

    @Override
    public int tier() { return 3; }

    @Override
    public @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        String cacheKey = hash(input);
        GuardrailResult cached = cache.get(cacheKey);
        if (cached != null) return cached;

        Instant start = Instant.now();
        try {
            LlmJudgeClient.JudgeResult judge = client.judge(input, policyDescription);
            Duration latency = Duration.between(start, Instant.now());

            GuardrailResult result;
            if (judge.violatesPolicy() && judge.confidence() >= blockThreshold) {
                result = GuardrailResult.block(name, 3, judge.reasoning(), judge.confidence(), latency);
            } else {
                result = GuardrailResult.allow(name, 3, latency);
            }

            cache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            // Fail open on LLM judge failure — availability over safety for Tier 3
            return GuardrailResult.allow(name, 3, Duration.between(start, Instant.now()));
        }
    }

    private @NonNull String hash(@NonNull String input) {
        return Integer.toHexString(input.hashCode());
    }
}
