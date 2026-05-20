package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the document chunking strategy.
 *
 * <p>Place on any {@code @Configuration} class or the main application class.
 *
 * <p>Example:
 * <pre>{@code
 * @ChunkingStrategy(type = "fixed-size", size = 512, overlap = 50)
 * @Configuration
 * public class RagConfig { }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ChunkingStrategy {
    /** Strategy type: fixed-size | recursive | semantic. */
    String type() default "fixed-size";

    /** Chunk size in tokens or characters. */
    int size() default 512;

    /** Chunk overlap. */
    int overlap() default 50;
}
