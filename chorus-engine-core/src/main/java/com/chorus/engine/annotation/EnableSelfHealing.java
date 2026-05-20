package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * When placed on a class annotated with {@link Agent}, the framework wraps
 * the generated {@code AgentLoop} with {@code SelfHealingAgentLoop}.
 *
 * <p>Self-healing detects failure patterns (repeated tool failures, timeouts,
 * hallucinations, cascading errors) and applies corrective actions.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableSelfHealing {
    /** Maximum number of healing retries per run. */
    int maxRetries() default 3;
}
