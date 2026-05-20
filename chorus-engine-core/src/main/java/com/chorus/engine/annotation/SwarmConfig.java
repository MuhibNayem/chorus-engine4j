package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the swarm orchestration strategy.
 *
 * <p>Place on any {@code @Configuration} class or the main application class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SwarmConfig {
    /** Maximum conversation turns across all agents. */
    int maxTurns() default 10;

    /** Enable circuit breakers per agent. */
    boolean enableCircuitBreakers() default true;

    /** Orchestrator type: handoff | supervisor | planner-executor. */
    String orchestrator() default "handoff";
}
