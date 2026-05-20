package com.chorus.engine.guardrails.tier;

import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
    private final Map<String, GuardrailResult> cache;

    public interface LlmJudgeClient {
        @NonNull JudgeResult judge(@NonNull String input, @NonNull String policy);

        record JudgeResult(boolean violatesPolicy, double confidence, @NonNull String reasoning) {}

        /**
         * Parses a verdict from a raw LLM response string using exact token matching.
         * Returns {@code true} only when the explicit verdict token "UNSAFE" appears as a
         * standalone word, preventing false positives from phrases like "not unsafe".
         */
        static boolean parseVerdictFromResponse(@NonNull String response) {
            String normalized = response.trim().toUpperCase();
            return normalized.matches("(?s).*\\bUNSAFE\\b.*") && !normalized.matches("(?s).*\\bNOT\\s+UNSAFE\\b.*");
        }
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
        final int maxSize = Math.max(1, cacheSize);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, GuardrailResult> eldest) {
                return size() > maxSize;
            }
        });
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
        long h = input.hashCode() ^ ((long) input.length() << 32);
        return Long.toHexString(h);
    }
}
