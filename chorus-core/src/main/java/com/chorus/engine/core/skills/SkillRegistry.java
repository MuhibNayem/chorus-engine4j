package com.chorus.engine.core.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dynamic skill registry with keyword-based relevance scoring.
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public void register(Skill skill) {
        skills.put(skill.name(), skill);
        log.info("Registered skill: {}", skill.name());
    }

    public Skill get(String name) {
        return skills.get(name);
    }

    public List<Skill> list() {
        return List.copyOf(skills.values());
    }

    public List<Skill> findRelevant(String query, int topK) {
        String lowerQuery = query.toLowerCase();
        return skills.values().stream()
            .map(skill -> new ScoredSkill(skill, relevanceScore(skill, lowerQuery)))
            .sorted(Comparator.comparingDouble(ScoredSkill::score).reversed())
            .limit(topK)
            .map(ScoredSkill::skill)
            .collect(Collectors.toList());
    }

    private double relevanceScore(Skill skill, String query) {
        double score = 0.0;
        String desc = skill.description().toLowerCase();
        String template = skill.promptTemplate().toLowerCase();

        if (desc.contains(query)) score += 2.0;
        if (template.contains(query)) score += 1.0;

        for (String example : skill.exampleUtterances()) {
            if (example.toLowerCase().contains(query)) score += 1.5;
        }

        // Keyword overlap
        String[] queryWords = query.split("\\s+");
        for (String word : queryWords) {
            if (word.length() > 2) {
                if (desc.contains(word)) score += 0.5;
                if (template.contains(word)) score += 0.3;
            }
        }

        return score;
    }

    private record ScoredSkill(Skill skill, double score) {}
}
