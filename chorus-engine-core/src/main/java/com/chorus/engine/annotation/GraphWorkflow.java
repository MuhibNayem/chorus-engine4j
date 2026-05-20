package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a graph workflow definition.
 *
 * <p>The framework scans the class for {@link GraphNode} methods and
 * {@link GraphEdge} annotations, builds a {@code StateGraph}, compiles it,
 * and registers the resulting {@code CompiledGraph} as a Spring bean.
 *
 * <p>Example:
 * <pre>{@code
 * @GraphWorkflow(entryPoint = "extract", finishPoints = {"review"})
 * @Component
 * public class MyWorkflow {
 *     @GraphNode("extract")
 *     public Map<String, Object> extract(Map<String, Object> state, CancellationToken token) { ... }
 *
 *     @GraphNode("generate")
 *     public Map<String, Object> generate(Map<String, Object> state, CancellationToken token) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphWorkflow {
    /** Name of the entry-point node. */
    String entryPoint();

    /** Names of terminal nodes. */
    String[] finishPoints() default {};

    /** State type (defaults to {@code Map<String, Object>}). */
    Class<?> stateType() default java.util.Map.class;
}
