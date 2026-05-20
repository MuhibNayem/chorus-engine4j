package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Spring bean as a swarm agent definition.
 *
 * <p>All {@code @SwarmAgent} beans are collected into a
 * {@code Map<String, AgentDefinition>} and wired into the swarm orchestrator.
 *
 * <p>Example:
 * <pre>{@code
 * @SwarmAgent(name = "planner", instructions = "You plan tasks...", handoffTargets = {"coder"})
 * @Component
 * public class PlannerAgent { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SwarmAgent {
    /** Agent name (used in handoff targets). */
    String name();

    /** System instructions for the agent. */
    String instructions();

    /** LLM model. Empty falls back to {@code ChorusProperties}. */
    String model() default "";

    /** Sampling temperature. {@code -1.0} falls back to properties. */
    double temperature() default -1.0;

    /** Names of agents this agent can hand off to. */
    String[] handoffTargets() default {};

    /** Names of tools available to this agent (by bean name or tool name). */
    String[] toolNames() default {};
}
