package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a directed edge between two graph nodes.
 *
 * <p>Place on a class annotated with {@link GraphWorkflow}.
 * Multiple {@code @GraphEdge} annotations can be combined using
 * {@link GraphEdges}.
 *
 * <p>Example:
 * <pre>{@code
 * @GraphEdge(from = "extract", to = "generate")
 * @GraphEdge(from = "generate", to = "review")
 * public class MyWorkflow { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphEdge {
    String from();
    String to();
}
