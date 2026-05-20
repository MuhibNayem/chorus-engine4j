package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method as a node in a {@link GraphWorkflow}.
 *
 * <p>The method signature must be compatible with the framework's node
 * functional interface (typically taking state + {@code CancellationToken}
 * and returning updated state).
 *
 * <p>Example:
 * <pre>{@code
 * @GraphNode("extract")
 * public Map<String, Object> extract(Map<String, Object> state, CancellationToken token) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphNode {
    /** Node name (used in edges and entry/finish points). */
    String value();
}
