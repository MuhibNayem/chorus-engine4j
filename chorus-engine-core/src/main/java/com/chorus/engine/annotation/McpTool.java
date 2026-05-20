package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method as an MCP server tool handler.
 *
 * <p>The method is automatically registered with the {@code McpServer}
 * at startup. It should accept a {@code Map<String, Object>} (args) and
 * return a serializable result.
 *
 * <p>Example:
 * <pre>{@code
 * @McpTool(name = "search", description = "Search the web")
 * public List<TextContent> search(Map<String, Object> args) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpTool {
    String name() default "";
    String description() default "";
}
