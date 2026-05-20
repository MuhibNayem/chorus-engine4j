package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a parameter of a {@link Tool}-annotated method.
 *
 * <p>The parameter name is used as the key in the JSON args map that the
 * LLM populates. The description appears in the JSON Schema sent to the
 * LLM for function calling.
 *
 * <p>Example:
 * <pre>{@code
 * @Tool(description = "Search the web")
 * public String search(
 *         @ToolParam(description = "The search query") String query,
 *         @ToolParam(description = "Max results", required = false) int limit) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    /** Natural-language description of the parameter. */
    String description() default "";

    /** Whether the LLM must provide this parameter. */
    boolean required() default true;
}
