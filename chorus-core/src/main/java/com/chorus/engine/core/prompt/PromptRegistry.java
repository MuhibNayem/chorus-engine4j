package com.chorus.engine.core.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Production-grade prompt registry with versioning, A/B testing, and caching.
 *
 * <p>2026 SOTA practices:</p>
 * <ul>
 *   <li>Versioned prompts decoupled from code deployments</li>
 *   <li>A/B testing with consistent hashing on session ID</li>
 *   <li>Environment aliases (production, staging, canary)</li>
 *   <li>Cache TTL for registry lookups (~60s default)</li>
 *   <li>Three-tier rollback: registry (&lt;60s), feature flags (&lt;2min), deploy (&lt;15min)</li>
 * </ul>
 */
public class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);

    private final Map<String, List<PromptTemplate>> versions = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();
    private final Map<String, CachedPrompt> cache = new ConcurrentHashMap<>();
    private long cacheTtlMs = 60_000;

    public void setCacheTtl(long ttlMs) {
        this.cacheTtlMs = ttlMs;
    }

    /**
     * Publish a new version of a prompt.
     */
    public PromptTemplate publish(PromptTemplate template) {
        versions.computeIfAbsent(template.id(), k -> new ArrayList<>()).add(template);
        log.info("Published prompt {} version {}", template.id(), template.version());
        return template;
    }

    /**
     * Set an alias (e.g., "production" → "2.1.0").
     */
    public void setAlias(String alias, String promptId, String version) {
        aliases.put(alias + ":" + promptId, version);
        invalidateCache(promptId);
        log.info("Set alias {} for {} → version {}", alias, promptId, version);
    }

    /**
     * Get a prompt by ID and optional version/alias.
     */
    public PromptTemplate get(String promptId, String versionOrAlias) {
        String cacheKey = promptId + ":" + versionOrAlias;
        CachedPrompt cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.template();
        }

        PromptTemplate result = resolve(promptId, versionOrAlias);
        if (result != null) {
            cache.put(cacheKey, new CachedPrompt(result, System.currentTimeMillis() + cacheTtlMs));
        }
        return result;
    }

    /**
     * Get the active (aliased as "production") version of a prompt.
     */
    public PromptTemplate get(String promptId) {
        return get(promptId, "production");
    }

    /**
     * Get prompt with A/B experiment routing by session ID.
     */
    public PromptTemplate getForSession(String promptId, String sessionId) {
        Experiment exp = experiments.get(promptId);
        if (exp != null && exp.isActive()) {
            String variant = exp.assignVariant(sessionId);
            log.debug("A/B experiment for {}: session {} → variant {}", promptId, sessionId, variant);
            return get(promptId, variant);
        }
        return get(promptId);
    }

    private PromptTemplate resolve(String promptId, String versionOrAlias) {
        if (versionOrAlias == null) {
            versionOrAlias = "production";
        }

        // Check if it's an alias
        String aliasedVersion = aliases.get(versionOrAlias + ":" + promptId);
        if (aliasedVersion != null) {
            versionOrAlias = aliasedVersion;
        }

        List<PromptTemplate> promptVersions = versions.get(promptId);
        if (promptVersions == null || promptVersions.isEmpty()) {
            return null;
        }

        String finalVersion = versionOrAlias;
        return promptVersions.stream()
            .filter(v -> v.version().equals(finalVersion))
            .findFirst()
            .orElse(null);
    }

    /**
     * Rollback to the previous version (Tier 1: &lt;60s).
     */
    public boolean rollback(String promptId) {
        List<PromptTemplate> promptVersions = versions.get(promptId);
        if (promptVersions == null || promptVersions.size() < 2) {
            log.warn("Cannot rollback {}: only {} version(s) available", promptId,
                promptVersions != null ? promptVersions.size() : 0);
            return false;
        }

        String currentVersion = aliases.get("production:" + promptId);
        if (currentVersion == null) {
            currentVersion = promptVersions.get(promptVersions.size() - 1).version();
        }

        String prevVersion = null;
        for (int i = 0; i < promptVersions.size(); i++) {
            if (promptVersions.get(i).version().equals(currentVersion) && i > 0) {
                prevVersion = promptVersions.get(i - 1).version();
                break;
            }
        }

        if (prevVersion != null) {
            setAlias("production", promptId, prevVersion);
            log.info("Rolled back {} from {} to {}", promptId, currentVersion, prevVersion);
            return true;
        }
        return false;
    }

    /**
     * List all versions of a prompt.
     */
    public List<PromptTemplate> listVersions(String promptId) {
        List<PromptTemplate> list = versions.get(promptId);
        return list != null ? List.copyOf(list) : List.of();
    }

    /**
     * Start an A/B experiment.
     */
    public void startExperiment(String promptId, String controlVersion, String treatmentVersion,
                                 double trafficFraction) {
        experiments.put(promptId, new Experiment(promptId, controlVersion, treatmentVersion, trafficFraction));
        log.info("Started A/B experiment for {}: {} vs {} ({}% traffic)",
            promptId, controlVersion, treatmentVersion, trafficFraction * 100);
    }

    /**
     * Stop an experiment and pick a winner.
     */
    public void stopExperiment(String promptId, String winnerVersion) {
        Experiment exp = experiments.remove(promptId);
        if (exp != null) {
            setAlias("production", promptId, winnerVersion);
            log.info("Stopped experiment for {}. Winner: {}", promptId, winnerVersion);
        }
    }

    private void invalidateCache(String promptId) {
        cache.entrySet().removeIf(e -> e.getKey().startsWith(promptId + ":"));
    }

    private record CachedPrompt(PromptTemplate template, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /**
     * A/B experiment with consistent hashing.
     */
    private record Experiment(String promptId, String controlVersion, String treatmentVersion,
                               double trafficFraction) {

        boolean isActive() {
            return trafficFraction > 0;
        }

        String assignVariant(String sessionId) {
            int hash = Objects.hash(sessionId, promptId);
            double bucket = (Math.abs(hash) % 10_000) / 10_000.0;
            return bucket < trafficFraction ? treatmentVersion : controlVersion;
        }
    }
}
