package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects the default LLM provider by name.
 *
 * <p>Place on any {@code @Configuration} class or the main application class.
 * The named provider is set as the primary in {@code ProviderRegistry}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PrimaryLlmProvider {
    /** Provider name (must match a registered provider). */
    String value();
}
