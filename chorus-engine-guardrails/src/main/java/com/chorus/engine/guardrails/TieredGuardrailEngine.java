package com.chorus.engine.guardrails;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 3-tier defense-in-depth guardrail engine.
 *
 * <p>Tier 1 (Fast): Regex, keyword matching, bloom filters — {@code <1ms}
 * <p>Tier 2 (ML): Embedding similarity, ONNX classifiers — {@code 20-100ms}
 * <p>Tier 3 (LLM): LLM-as-judge policy evaluation — {@code 500ms-8s}
 *
 * <p>Execution: Tier 1 runs first. If any Tier 1 blocks, short-circuit.
 * Tier 2 runs in parallel via executor. Tier 3 runs only if Tier 2 is ambiguous.
 */
public final class TieredGuardrailEngine {

    private final List<Guardrail> tier1;
    private final List<Guardrail> tier2;
    private final List<Guardrail> tier3;
    private final ExecutorService executor;
    private final Duration tier2Timeout;
    private final Duration tier3Timeout;

    public TieredGuardrailEngine(
        @NonNull List<Guardrail> guardrails,
        @NonNull ExecutorService executor,
        @NonNull Duration tier2Timeout,
        @NonNull Duration tier3Timeout
    ) {
        this.tier1 = guardrails.stream().filter(g -> g.tier() == 1).toList();
        this.tier2 = guardrails.stream().filter(g -> g.tier() == 2).toList();
        this.tier3 = guardrails.stream().filter(g -> g.tier() == 3).toList();
        this.executor = executor;
        this.tier2Timeout = tier2Timeout;
        this.tier3Timeout = tier3Timeout;
    }

    public @NonNull EvaluationResult evaluateInput(@NonNull String input, Guardrail.@NonNull GuardrailContext context) {
        Instant start = Instant.now();
        List<GuardrailResult> results = new ArrayList<>();

        // Tier 1: Sequential, fast short-circuit
        for (Guardrail g : tier1) {
            GuardrailResult r = g.evaluate(input, context);
            results.add(r);
            if (!r.allowed() && r.action() == GuardrailResult.Action.BLOCK) {
                return new EvaluationResult(false, List.copyOf(results), input, Duration.between(start, Instant.now()));
            }
        }

        // Tier 2: Parallel with timeout
        if (!tier2.isEmpty()) {
            List<Future<GuardrailResult>> futures = new ArrayList<>();
            for (Guardrail g : tier2) {
                futures.add(executor.submit(() -> g.evaluate(input, context)));
            }

            for (Future<GuardrailResult> f : futures) {
                try {
                    GuardrailResult r = f.get(tier2Timeout.toMillis(), TimeUnit.MILLISECONDS);
                    results.add(r);
                    if (!r.allowed() && r.action() == GuardrailResult.Action.BLOCK) {
                        futures.forEach(fut -> fut.cancel(true));
                        return new EvaluationResult(false, List.copyOf(results), input, Duration.between(start, Instant.now()));
                    }
                } catch (TimeoutException e) {
                    f.cancel(true);
                    results.add(GuardrailResult.allow(guardrailName(f), 2, tier2Timeout));
                } catch (Exception e) {
                    f.cancel(true);
                    results.add(GuardrailResult.allow(guardrailName(f), 2, Duration.ZERO));
                }
            }
        }

        // Tier 3: Sequential, only if ambiguous (confidence in middle range)
        boolean ambiguous = results.stream()
            .filter(r -> r.tier() == 2)
            .anyMatch(r -> r.confidence() > 0.3 && r.confidence() < 0.7);

        if (ambiguous && !tier3.isEmpty()) {
            for (Guardrail g : tier3) {
                Future<GuardrailResult> future = executor.submit(() -> g.evaluate(input, context));
                try {
                    GuardrailResult r = future.get(tier3Timeout.toMillis(), TimeUnit.MILLISECONDS);
                    results.add(r);
                    if (!r.allowed() && r.action() == GuardrailResult.Action.BLOCK) {
                        return new EvaluationResult(false, List.copyOf(results), input, Duration.between(start, Instant.now()));
                    }
                } catch (TimeoutException e) {
                    results.add(GuardrailResult.allow(g.name(), 3, tier3Timeout));
                } catch (Exception e) {
                    results.add(GuardrailResult.allow(g.name(), 3, Duration.ZERO));
                }
            }
        }

        // Apply redaction if any guardrail requested it
        String finalOutput = input;
        for (GuardrailResult r : results) {
            if (r.action() == GuardrailResult.Action.REDACT && r.matchedContent() != null) {
                finalOutput = finalOutput.replace(r.matchedContent(), "[REDACTED]");
            }
        }

        boolean allowed = results.stream().allMatch(GuardrailResult::allowed);
        return new EvaluationResult(allowed, List.copyOf(results), finalOutput, Duration.between(start, Instant.now()));
    }

    private @NonNull String guardrailName(@NonNull Future<GuardrailResult> f) {
        return "unknown";
    }

    public record EvaluationResult(
        boolean allowed,
        @NonNull List<GuardrailResult> details,
        @NonNull String output,
        @NonNull Duration totalLatency
    ) {
        public boolean wasRedacted() {
            return details.stream().anyMatch(r -> r.action() == GuardrailResult.Action.REDACT);
        }

        public @NonNull List<String> triggeredNames() {
            return details.stream()
                .filter(r -> !r.allowed() || r.action() != GuardrailResult.Action.ALLOW)
                .map(GuardrailResult::guardrailName)
                .toList();
        }
    }
}
