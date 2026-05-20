package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method as an MCP server prompt handler.
 *
 * <p>The method is automatically registered with the {@code McpServer}
 * at startup.
 *
 * <p>Example:
 * <pre>{@code
 * @McpPrompt(name = "greeting", description = "A friendly greeting prompt")
 * public PromptMessage greeting() { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpPrompt {
    String name() default "";
    String description() default "";
}
