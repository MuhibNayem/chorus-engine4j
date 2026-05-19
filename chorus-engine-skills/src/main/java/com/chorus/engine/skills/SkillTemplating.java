package com.chorus.engine.skills;

import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Variable substitution for skill prompts and configuration.
 * Supports {{variable}} syntax with fallback defaults.
 */
public final class SkillTemplating {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\Q{{\\E\\s*([\\w.]+)(?::([^\\Q}}\\E]*))?\\s*\\Q}}\\E");

    private SkillTemplating() {}

    /**
     * Substitute variables in a template string.
     */
    public static @NonNull String render(@NonNull String template, @NonNull Map<String, Object> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2);
            Object value = variables.get(varName);
            String replacement = value != null ? value.toString()
                : defaultValue != null ? defaultValue
                : matcher.group(0);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Apply templating to a skill's system prompt with variables.
     */
    public static @NonNull Skill renderSkill(@NonNull Skill skill, @NonNull Map<String, Object> variables) {
        String renderedPrompt = render(skill.systemPrompt(), variables);
        return new Skill(
            skill.id(), skill.name(), skill.description(),
            renderedPrompt, skill.toolNames(), skill.config(), skill.tags()
        );
    }

    /**
     * Check if a template contains any unresolved variables.
     */
    public static boolean hasUnresolvedVariables(@NonNull String template, @NonNull Map<String, Object> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (!variables.containsKey(varName) && matcher.group(2) == null) {
                return true;
            }
        }
        return false;
    }
}
