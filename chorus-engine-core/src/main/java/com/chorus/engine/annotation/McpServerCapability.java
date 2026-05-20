package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures MCP server capabilities.
 *
 * <p>Place on any {@code @Configuration} class or the main application class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpServerCapability {
    boolean tools() default true;
    boolean resources() default false;
    boolean prompts() default false;
    boolean completions() default false;
    boolean logging() default false;
}
