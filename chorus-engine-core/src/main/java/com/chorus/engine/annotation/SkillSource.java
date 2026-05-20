package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares directories or classpath locations to load skill JSON definitions from.
 *
 * <p>Place on any {@code @Configuration} class or the main application class.
 *
 * <p>Example:
 * <pre>{@code
 * @SkillSource({"classpath:/skills/", "file:/app/skills/"})
 * @SpringBootApplication
 * public class MyApp { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkillSource {
    /** Locations to scan for skill JSON files. */
    String[] value();
}
