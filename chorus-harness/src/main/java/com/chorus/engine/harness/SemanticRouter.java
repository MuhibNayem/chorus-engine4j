package com.chorus.engine.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Semantic router that matches user queries against registered routes using cosine similarity
 * over embedding vectors produced by a Spring AI {@link EmbeddingModel}.
 *
 * <p>Embeddings for route example utterances are computed lazily and cached for performance.
 * If no route exceeds its configured threshold, a configurable default route is returned.</p>
 */
public class SemanticRouter {

    private static final Logger log = LoggerFactory.getLogger(SemanticRouter.class);

    private final EmbeddingModel embeddingModel;
    private final double defaultThreshold;
    private final String defaultRouteName;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<String, List<List<Double>>> embeddingCache = new ConcurrentHashMap<>();
    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    public SemanticRouter(EmbeddingModel embeddingModel, double defaultThreshold, String defaultRouteName) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "EmbeddingModel must not be null");
        this.defaultThreshold = defaultThreshold;
        this.defaultRouteName = Objects.requireNonNull(defaultRouteName, "Default route name must not be null");
    }

    public SemanticRouter(EmbeddingModel embeddingModel) {
        this(embeddingModel, 0.75, "default");
    }

    /**
     * Register a route. If the route supplies pre-computed embeddings they are stored directly;
     * otherwise embeddings are computed on first use and cached.
     */
    public void registerRoute(Route route) {
        Objects.requireNonNull(route, "Route must not be null");
        routes.put(route.name(), route);
        if (route.cachedEmbeddings() != null && !route.cachedEmbeddings().isEmpty()) {
            embeddingCache.put(route.name(), new ArrayList<>(route.cachedEmbeddings()));
        } else {
            embeddingCache.remove(route.name());
        }
        log.debug("Registered route '{}' with {} examples", route.name(), route.exampleUtterances().size());
    }

    /**
     * Remove a route and evict its cached embeddings.
     */
    public void unregisterRoute(String routeName) {
        routes.remove(routeName);
        embeddingCache.remove(routeName);
        log.debug("Unregistered route '{}'", routeName);
    }

    /**
     * Route a query to the best-matching route using cosine similarity.
     * Executed on a virtual thread.
     *
     * @param query the user utterance to classify
     * @return future containing the routing decision
     */
    public CompletableFuture<RouterResult> route(String query) {
        return CompletableFuture.supplyAsync(() -> doRoute(query), VIRTUAL_EXECUTOR);
    }

    private RouterResult doRoute(String query) {
        Objects.requireNonNull(query, "Query must not be null");

        if (routes.isEmpty()) {
            log.debug("No routes registered; falling back to default '{}'", defaultRouteName);
            return new RouterResult(defaultRouteName, 0.0, null);
        }

        List<Double> queryEmbedding = embedQuery(query);

        // Ensure every route has its example embeddings materialised
        for (Route route : routes.values()) {
            if (!embeddingCache.containsKey(route.name())) {
                List<List<Double>> computed = computeEmbeddingsForRoute(route);
                embeddingCache.putIfAbsent(route.name(), computed);
            }
        }

        String bestRoute = null;
        double bestScore = -1.0;
        String bestExample = null;

        for (Route route : routes.values()) {
            List<List<Double>> embeddings = embeddingCache.getOrDefault(route.name(), List.of());
            if (embeddings.isEmpty()) {
                continue;
            }

            double routeThreshold = route.threshold() > 0.0 ? route.threshold() : defaultThreshold;
            List<String> utterances = route.exampleUtterances();

            for (int i = 0; i < embeddings.size(); i++) {
                double score = cosineSimilarity(queryEmbedding, embeddings.get(i));
                if (score > bestScore && score >= routeThreshold) {
                    bestScore = score;
                    bestRoute = route.name();
                    bestExample = utterances.get(i);
                }
            }
        }

        if (bestRoute == null) {
            log.debug("No route exceeded threshold; falling back to default '{}'", defaultRouteName);
            return new RouterResult(defaultRouteName, 0.0, null);
        }

        log.debug("Routed to '{}' with confidence {} (matched example: '{}')", bestRoute, bestScore, bestExample);
        return new RouterResult(bestRoute, bestScore, bestExample);
    }

    /**
     * Evict all cached embeddings. Routes themselves remain registered.
     */
    public void clearCache() {
        embeddingCache.clear();
        log.debug("Cleared embedding cache");
    }

    private List<Double> embedQuery(String query) {
        float[] embedding = embeddingModel.embed(query);
        return toDoubleList(embedding);
    }

    private List<List<Double>> computeEmbeddingsForRoute(Route route) {
        List<String> utterances = route.exampleUtterances();
        if (utterances == null || utterances.isEmpty()) {
            return List.of();
        }
        List<float[]> embeddings = embeddingModel.embed(utterances);
        List<List<Double>> result = new ArrayList<>(embeddings.size());
        for (float[] array : embeddings) {
            result.add(toDoubleList(array));
        }
        return result;
    }

    private static List<Double> toDoubleList(float[] array) {
        List<Double> list = new ArrayList<>(array.length);
        for (float value : array) {
            list.add((double) value);
        }
        return list;
    }

    /**
     * Compute cosine similarity between two embedding vectors.
     */
    static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) {
            throw new IllegalArgumentException(
                "Embedding dimensions must match: " + a.size() + " vs " + b.size()
            );
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            double ai = a.get(i);
            double bi = b.get(i);
            dotProduct += ai * bi;
            normA += ai * ai;
            normB += bi * bi;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
