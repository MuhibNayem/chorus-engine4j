package com.chorus.engine.skills;

import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Routes user input to the best matching skill.
 * Uses simple keyword matching on skill names and tags.
 */
public final class SkillRouter {

    /**
     * Route user input to the best skill from the candidates.
     *
     * @param userInput  the user's input
     * @param candidates list of candidate skills
     * @return Ok(skill) if a match is found, Err(reason) otherwise
     */
    public @NonNull Result<Skill, String> route(@NonNull String userInput, @NonNull List<Skill> candidates) {
        Objects.requireNonNull(userInput);
        Objects.requireNonNull(candidates);

        if (candidates.isEmpty()) {
            return Result.err("No candidate skills available");
        }

        String lowerInput = userInput.toLowerCase(Locale.ROOT);
        Skill bestMatch = null;
        int bestScore = 0;

        for (Skill skill : candidates) {
            int score = 0;
            String lowerName = skill.name().toLowerCase(Locale.ROOT);
            String lowerDesc = skill.description().toLowerCase(Locale.ROOT);

            // Exact name match
            if (lowerName.equals(lowerInput)) {
                score += 100;
            }
            // Name contains input
            else if (lowerName.contains(lowerInput)) {
                score += 50;
            }
            // Input contains name (only if name is meaningful, > 1 char)
            else if (lowerName.length() > 1 && lowerInput.contains(lowerName)) {
                score += 40;
            }

            // Description contains input words
            String[] words = lowerInput.split("\\s+");
            for (String word : words) {
                if (word.length() > 2 && lowerDesc.contains(word)) {
                    score += 10;
                }
            }

            // Tag match (only if tag is meaningful, > 1 char)
            for (String tag : skill.tags()) {
                String lowerTag = tag.toLowerCase(Locale.ROOT);
                if (lowerTag.length() > 1 && lowerInput.contains(lowerTag)) {
                    score += 30;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestMatch = skill;
            }
        }

        if (bestMatch == null || bestScore == 0) {
            return Result.err("No skill matched the input: " + userInput);
        }

        return Result.ok(bestMatch);
    }
}
