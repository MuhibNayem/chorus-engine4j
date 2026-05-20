package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a Chorus agent. The framework will generate an
 * {@code AgentLoop} bean wired with any {@link Tool}-annotated methods
 * discovered on this class.
 *
 * <p>The annotated class must also be a Spring bean (e.g. annotated with
 * {@code @Component}) so that tool methods have a target instance.
 *
 * <p>Example:
 * <pre>{@code
 * @Agent(name = "researcher", systemPrompt = "You are a research assistant.")
 * @Component
 * public class ResearcherAgent {
 *     @Tool(description = "Search the web")
 *     public String search(@ToolParam(description = "query") String query) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Agent {

    /** Agent identifier (used as {@code agentId} in {@code AgentLoop}). */
    String name();

    /** System prompt sent to the LLM on every run. */
    String systemPrompt() default "";

    /**
     * LLM model name. Empty string falls back to
     * {@code ChorusProperties.Llm#getModel()}.
     */
    String model() default "";

    /**
     * Sampling temperature. {@code -1.0} falls back to
     * {@code ChorusProperties.Llm#getTemperature()}.
     */
    double temperature() default -1.0;

    /**
     * Maximum tokens per completion. {@code -1} falls back to
     * {@code ChorusProperties.Llm#getMaxTokens()}.
     */
    int maxTokens() default -1;

    /**
     * Maximum ReAct rounds. {@code -1} falls back to
     * {@code ChorusProperties.Agent#getMaxRounds()}.
     */
    int maxRounds() default -1;
}
