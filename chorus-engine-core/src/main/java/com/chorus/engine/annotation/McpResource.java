package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method as an MCP server resource handler.
 *
 * <p>The method is automatically registered with the {@code McpServer}
 * at startup. It should accept a URI string and return resource content.
 *
 * <p>Example:
 * <pre>{@code
 * @McpResource(uri = "docs://api", name = "API Documentation")
 * public String getDocs(String uri) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpResource {
    String uri();
    String name() default "";
    String description() default "";
    String mimeType() default "text/plain";
}
