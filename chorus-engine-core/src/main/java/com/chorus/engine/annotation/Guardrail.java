package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a guardrail implementation.
 *
 * <p>The annotated class must implement
 * {@code com.chorus.engine.guardrails.Guardrail}.
 * Guardrails are auto-collected, sorted by {@code tier()}, and injected into
 * {@code TieredGuardrailEngine}.
 *
 * <p>Tiers:
 * <ul>
 *   <li>1 — Fast regex/keyword checks (sub-millisecond)</li>
 *   <li>2 — LLM-based judge (tens to hundreds of ms)</li>
 *   <li>3 — Human review queue (async, minutes to hours)</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Guardrail {
    /** Guardrail tier (1, 2, or 3). */
    int tier() default 1;

    /** Guardrail name. Empty defaults to bean name. */
    String name() default "";
}
