package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a skill definition.
 *
 * <p>The framework creates a {@code Skill} record from the annotation
 * attributes and registers it in {@code SkillRegistry}.
 *
 * <p>Example:
 * <pre>{@code
 * @Skill(id = "web-researcher", name = "Web Researcher",
 *        systemPrompt = "You research topics on the web.",
 *        toolNames = {"search_web", "read_page"},
 *        tags = {"research", "web"})
 * @Component
 * public class WebResearcherSkill { }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Skill {
    /** Unique skill identifier. */
    String id();

    /** Human-readable name. */
    String name() default "";

    /** Description. */
    String description() default "";

    /** System prompt used when this skill is executed. */
    String systemPrompt() default "";

    /** Tool names available to this skill. */
    String[] toolNames() default {};

    /** Tags for categorization and search. */
    String[] tags() default {};
}
