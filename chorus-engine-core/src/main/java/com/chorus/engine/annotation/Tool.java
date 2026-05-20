package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method as a tool callable by an LLM agent.
 *
 * <p>The method must be declared on a class annotated with {@link Agent}.
 * The framework generates a {@code Tool} bean for each {@code @Tool} method
 * and registers it automatically.
 *
 * <p>Supported return types:
 * <ul>
 *   <li>{@code Result<ToolOutput, ToolError>} — returned as-is</li>
 *   <li>{@code ToolOutput} — wrapped in {@code Result.ok()}</li>
 *   <li>{@code String} — wrapped as a text {@code ToolOutput}</li>
 *   <li>{@code void} — produces an empty {@code ToolOutput}</li>
 * </ul>
 *
 * <p>Supported parameter types:
 * <ul>
 *   <li>{@code @ToolParam} annotated parameters — bound from the JSON args map</li>
 *   <li>{@code CancellationToken} — passed through from the caller</li>
 *   <li>{@code Map<String, Object>} — receives the raw args map</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * @Tool(description = "Search the web for information")
 * public String searchWeb(
 *         @ToolParam(description = "The search query") String query) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    /** Tool name (snake_case). Defaults to the method name. */
    String value() default "";

    /** Natural-language description for LLM tool selection. */
    String description() default "";
}
