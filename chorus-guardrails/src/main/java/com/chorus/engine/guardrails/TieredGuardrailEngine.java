package com.chorus.engine.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Three-tier guardrail engine: fast (regex/keywords), ML (embeddings/NER), LLM (judge).
 * Supports latency budgeting and halt-on-severity behavior.
 */
public class TieredGuardrailEngine {

    private static final Logger log = LoggerFactory.getLogger(TieredGuardrailEngine.class);

    private final List<GuardrailCheck> fastChecks = new ArrayList<>();
    private final List<GuardrailCheck> mlChecks = new ArrayList<>();
    private final List<GuardrailCheck> llmChecks = new ArrayList<>();
    private final Duration latencyBudget;
    private final String haltOnSeverity;
    private final boolean collectAll;

    public TieredGuardrailEngine(Duration latencyBudget, String haltOnSeverity, boolean collectAll) {
        this.latencyBudget = latencyBudget;
        this.haltOnSeverity = haltOnSeverity;
        this.collectAll = collectAll;
    }

    public void addFastCheck(String name, String severity, Function<Object, Boolean> check) {
        fastChecks.add(new GuardrailCheck(name, severity, 1, check));
    }

    public void addMlCheck(String name, String severity, Function<Object, Boolean> check) {
        mlChecks.add(new GuardrailCheck(name, severity, 2, check));
    }

    public void addLlmCheck(String name, String severity, Function<Object, Boolean> check) {
        llmChecks.add(new GuardrailCheck(name, severity, 3, check));
    }

    public CompletableFuture<GuardrailResult> run(Object context) {
        return CompletableFuture.supplyAsync(() -> {
            Instant start = Instant.now();
            List<GuardrailViolation> violations = new ArrayList<>();
            boolean halted = false;

            // Tier 1: Fast checks
            for (GuardrailCheck check : fastChecks) {
                if (halted && !collectAll) break;
                try {
                    if (Boolean.TRUE.equals(check.check().apply(context))) {
                        violations.add(new GuardrailViolation(check.name(), check.severity(), "Fast guardrail triggered"));
                        if (shouldHalt(check.severity())) {
                            halted = true;
                            if (!collectAll) break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Fast guardrail {} failed", check.name(), e);
                }
            }

            // Tier 2: ML checks (if latency budget allows)
            long elapsedMs = Duration.between(start, Instant.now()).toMillis();
            if ((!halted || collectAll) && elapsedMs < latencyBudget.toMillis() * 0.5) {
                for (GuardrailCheck check : mlChecks) {
                    if (halted && !collectAll) break;
                    try {
                        if (Boolean.TRUE.equals(check.check().apply(context))) {
                            violations.add(new GuardrailViolation(check.name(), check.severity(), "ML guardrail triggered"));
                            if (shouldHalt(check.severity())) {
                                halted = true;
                                if (!collectAll) break;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("ML guardrail {} failed", check.name(), e);
                    }
                }
            }

            // Tier 3: LLM checks (if significant latency budget remains)
            elapsedMs = Duration.between(start, Instant.now()).toMillis();
            if ((!halted || collectAll) && elapsedMs < latencyBudget.toMillis() * 0.2) {
                for (GuardrailCheck check : llmChecks) {
                    if (halted && !collectAll) break;
                    try {
                        if (Boolean.TRUE.equals(check.check().apply(context))) {
                            violations.add(new GuardrailViolation(check.name(), check.severity(), "LLM guardrail triggered"));
                            if (shouldHalt(check.severity())) {
                                halted = true;
                                if (!collectAll) break;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("LLM guardrail {} failed", check.name(), e);
                    }
                }
            }

            return new GuardrailResult(violations, halted);
        });
    }

    private boolean shouldHalt(String severity) {
        return switch (haltOnSeverity) {
            case "critical" -> "critical".equals(severity);
            case "warning" -> "critical".equals(severity) || "warning".equals(severity);
            default -> true; // "info" halts on everything
        };
    }

    public record GuardrailCheck(String name, String severity, int tier, Function<Object, Boolean> check) {}
    public record GuardrailViolation(String guardrail, String severity, String message) {}
    public record GuardrailResult(List<GuardrailViolation> violations, boolean halted) {}
}
