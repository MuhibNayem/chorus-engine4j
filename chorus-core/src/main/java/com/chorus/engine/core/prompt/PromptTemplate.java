package com.chorus.engine.core.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A versioned prompt template with variable substitution.
 * Supports Jinja2-style {@code {{ variable }}} syntax.
 */
public record PromptTemplate(
    String id,
    String name,
    String version,
    String content,
    String description,
    Map<String, String> variables,
    long createdAt,
    String author
) {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\Q{{\\E\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\Q}}\\E");

    public PromptTemplate {
        variables = variables != null ? Map.copyOf(variables) : Map.of();
    }

    /**
     * Render the template with variable substitutions.
     */
    public String render(Map<String, String> values) {
        Matcher matcher = VAR_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = values.get(varName);
            if (value == null) {
                value = variables.getOrDefault(varName, "");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Render with no substitutions (returns raw template).
     */
    public String render() {
        return content;
    }

    /**
     * Create a new version of this template with updated content.
     */
    public PromptTemplate newVersion(String newVersion, String newContent) {
        return new PromptTemplate(
            id, name, newVersion, newContent, description,
            variables, System.currentTimeMillis(), author
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private String version = "1.0.0";
        private String content;
        private String description;
        private Map<String, String> variables = Map.of();
        private long createdAt = System.currentTimeMillis();
        private String author;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder variables(Map<String, String> variables) { this.variables = variables; return this; }
        public Builder author(String author) { this.author = author; return this; }

        public PromptTemplate build() {
            if (id == null) id = name != null ? name.toLowerCase().replaceAll("\\s+", "-") : "unknown";
            return new PromptTemplate(id, name, version, content, description, variables, createdAt, author);
        }
    }
}
