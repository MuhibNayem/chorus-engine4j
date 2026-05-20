package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class implementing {@code com.chorus.engine.tools.Tool} for
 * automatic registration in {@code ToolRegistry}.
 *
 * <p>This is the class-level counterpart to the method-level {@link Tool}
 * annotation (which is used inside {@code @Agent} classes).
 *
 * <p>Example:
 * <pre>{@code
 * @ChorusTool("web_search")
 * @Component
 * public class WebSearchTool implements Tool {
 *     public String name() { return "web_search"; }
 *     // ...
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ChorusTool {
    /** Overrides {@code Tool.name()} if non-empty. */
    String value() default "";
}
