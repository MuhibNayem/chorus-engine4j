package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Chorus middleware component.
 *
 * <p>Middleware beans are auto-discovered and injected into
 * {@code AgentLoop} instances. They are sorted by {@code priority()}
 * (lower values = earlier execution).
 *
 * <p>The annotated class must implement
 * {@code com.chorus.engine.agent.middleware.Middleware}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Middleware {
    /** Execution priority — lower runs first. */
    int priority() default 0;
}
