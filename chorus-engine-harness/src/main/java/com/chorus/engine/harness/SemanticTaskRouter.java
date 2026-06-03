package com.chorus.engine.harness;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.llm.embed.EmbeddingClient;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Semantic task router — uses embedding-based classification with confidence scoring.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Embed the input text</li>
 *   <li>Compute cosine similarity against all prototype vectors (multi-vector routing)</li>
 *   <li>Take the MAX similarity per route</li>
 *   <li>Select the route with the highest max similarity</li>
 *   <li>If confidence &gt;= threshold → semantic route</li>
 *   <li>Otherwise → fallback to regex-based router</li>
 * </ol>
 */
public final class SemanticTaskRouter {

    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.55;

    private final EmbeddingClient embedder;
    private final VectorOperations vectorOps;
    private final double confidenceThreshold;
    private final TaskRouter fallbackRouter;
    private final List<RoutePrototype> prototypes = new ArrayList<>();
    private final ReentrantLock initLock = new ReentrantLock();
    private volatile boolean initialized = false;

    public SemanticTaskRouter(@NonNull EmbeddingClient embedder) {
        this(embedder, VectorOperations.autoDetect(), DEFAULT_CONFIDENCE_THRESHOLD);
    }

    public SemanticTaskRouter(@NonNull EmbeddingClient embedder, @NonNull VectorOperations vectorOps) {
        this(embedder, vectorOps, DEFAULT_CONFIDENCE_THRESHOLD);
    }

    public SemanticTaskRouter(@NonNull EmbeddingClient embedder, @NonNull VectorOperations vectorOps, double confidenceThreshold) {
        this.embedder = embedder;
        this.vectorOps = vectorOps;
        this.confidenceThreshold = confidenceThreshold;
        this.fallbackRouter = new TaskRouter();
    }

    /**
     * Route a task using semantic embeddings with confidence scoring.
     * Falls back to regex routing if embedding fails or confidence is too low.
     */
    public @NonNull SemanticRouteResult route(@NonNull String text, @NonNull String expandedText) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(expandedText, "expandedText");
        ensureInitialized();

        String combined = (text + " " + expandedText).trim();
        if (combined.isEmpty()) {
            return fallback(text, expandedText);
        }

        try {
            var embedResult = embedder.embed(combined, embedOptions());

            if (embedResult.isErr()) {
                return fallback(text, expandedText);
            }

            float[] queryVector = embedResult.unwrap();
            return routeByEmbedding(queryVector, text, expandedText);
        } catch (Exception e) {
            return fallback(text, expandedText);
        }
    }

    /**
     * Score all routes for a given input without committing to a route.
     * Useful for debugging and diagnostics.
     */
    public @NonNull List<RouteScore> score(@NonNull String text, @NonNull String expandedText) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(expandedText, "expandedText");
        ensureInitialized();

        String combined = (text + " " + expandedText).trim();
        var embedResult = embedder.embed(combined, embedOptions());

        if (embedResult.isErr()) {
            return List.of();
        }

        float[] queryVector = embedResult.unwrap();
        List<RouteScore> scores = new ArrayList<>();

        for (RoutePrototype proto : prototypes) {
            double maxSim = -1.0;
            for (float[] vec : proto.vectors) {
                double sim = vectorOps.cosineSimilarity(queryVector, vec);
                if (sim > maxSim) maxSim = sim;
            }
            scores.add(new RouteScore(proto.label, maxSim));
        }

        scores.sort(Comparator.comparingDouble(RouteScore::confidence).reversed());
        return List.copyOf(scores);
    }

    private @NonNull SemanticRouteResult routeByEmbedding(float[] queryVector, String text, String expandedText) {
        double bestScore = -1.0;
        RoutePrototype bestProto = null;

        for (RoutePrototype proto : prototypes) {
            double maxSim = -1.0;
            for (float[] vec : proto.vectors) {
                double sim = vectorOps.cosineSimilarity(queryVector, vec);
                if (sim > maxSim) maxSim = sim;
            }
            if (maxSim > bestScore) {
                bestScore = maxSim;
                bestProto = proto;
            }
        }

        if (bestProto != null && bestScore >= confidenceThreshold) {
            return new SemanticRouteResult(
                bestProto.kind,
                bestProto.lane,
                bestProto.path,
                bestProto.path == TaskPath.RESEARCH_THEN_PLAN_PATH,
                bestProto.path == TaskPath.PARALLEL_MULTI_WORKER_PATH,
                bestProto.lane == ExecutionLane.CHEAP_TRIAGE,
                bestScore,
                RoutingMethod.SEMANTIC,
                bestProto.label
            );
        }

        return fallback(text, expandedText);
    }

    private @NonNull SemanticRouteResult fallback(String text, String expandedText) {
        TaskRoute route = fallbackRouter.route(text, expandedText);
        return new SemanticRouteResult(
            route.kind(), route.lane(), route.path(),
            route.requiresResearch(), route.canParallelize(), route.usesCheapTriage(),
            0.0, RoutingMethod.FALLBACK, "fallback-regex"
        );
    }

    private void ensureInitialized() {
        if (initialized) return;
        initLock.lock();
        try {
            if (initialized) return;
            initializePrototypes();
            initialized = true;
        } finally {
            initLock.unlock();
        }
    }

    private void initializePrototypes() {
        for (RouteCategory cat : RouteCategory.DEFAULTS) {
            List<float[]> vectors = new ArrayList<>();
            for (String example : cat.examples) {
                var result = embedder.embed(example, embedOptions());
                if (result.isOk()) {
                    vectors.add(result.unwrap());
                }
            }
            if (!vectors.isEmpty()) {
                prototypes.add(new RoutePrototype(
                    cat.kind, cat.lane, cat.path, cat.label,
                    vectors, cat.examples
                ));
            }
        }
    }



    private EmbeddingClient.EmbedOptions embedOptions() {
        return new EmbeddingClient.EmbedOptions(
            embedder.modelName(),
            EmbeddingClient.EmbedOptions.InputType.QUERY,
            0,
            true,
            EmbeddingClient.EmbedOptions.Quantization.FP32,
            java.util.Map.of()
        );
    }

    // ------------------------------------------------------------------

    public enum RoutingMethod { SEMANTIC, FALLBACK }

    public record SemanticRouteResult(
        TaskKind kind,
        ExecutionLane lane,
        TaskPath path,
        boolean requiresResearch,
        boolean canParallelize,
        boolean usesCheapTriage,
        double confidence,
        RoutingMethod method,
        String matchedLabel
    ) {}

    public record RouteScore(String label, double confidence) {}

    private record RoutePrototype(
        TaskKind kind, ExecutionLane lane, TaskPath path, String label,
        List<float[]> vectors, List<String> examples
    ) {}

    private record RouteCategory(
        TaskKind kind, ExecutionLane lane, TaskPath path, String label, List<String> examples
    ) {
        static final List<RouteCategory> DEFAULTS = List.of(
            new RouteCategory(TaskKind.RESEARCH, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.RESEARCH_THEN_PLAN_PATH, "research", List.of(
                "What is the latest version of React?",
                "Search for best practices on error handling",
                "Look up the official documentation",
                "Find information about TypeScript decorators",
                "Verify online whether this package is maintained",
                "Check the release notes for Node.js 22",
                "What's the current state of the art for vector databases?",
                "Compare open source agent frameworks"
            )),
            new RouteCategory(TaskKind.DEBUG, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.TOOL_OR_SINGLE_WORKER_PATH, "debug", List.of(
                "Fix the null pointer exception in utils.js",
                "Debug why tests are failing on CI",
                "Investigate the memory leak",
                "Root cause analysis for the 500 error",
                "Why is the build breaking?",
                "The API returns 403 — figure out why",
                "Trace the race condition in the websocket handler"
            )),
            new RouteCategory(TaskKind.MULTI_FILE_EDIT, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.PARALLEL_MULTI_WORKER_PATH, "multi_file_edit", List.of(
                "Refactor the authentication module across the codebase",
                "Update all components to use the new hook",
                "Migrate from JavaScript to TypeScript in the src folder",
                "Rename every occurrence of oldApi to newApi",
                "Restructure the project to use feature folders",
                "Change the database schema and update all models"
            )),
            new RouteCategory(TaskKind.SINGLE_FILE_EDIT, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.TOOL_OR_SINGLE_WORKER_PATH, "single_file_edit", List.of(
                "Fix the bug in helpers.ts",
                "Add a validation function to utils.js",
                "Update the config in package.json",
                "Remove the deprecated method from api.ts",
                "Implement the missing test in user.test.js"
            )),
            new RouteCategory(TaskKind.ANSWER_ONLY, ExecutionLane.CHEAP_TRIAGE,
                TaskPath.DIRECT_AGENT_PATH, "answer_only", List.of(
                "What is 2+2?",
                "Explain how closures work in JavaScript",
                "How do I use async/await?",
                "What does the spread operator do?",
                "When should I use let vs const?",
                "Explain the difference between map and forEach"
            )),
            new RouteCategory(TaskKind.PROJECT_PHASE, ExecutionLane.BACKGROUND_ASYNC,
                TaskPath.BACKGROUND_OR_BATCH_PATH, "project_phase", List.of(
                "Audit the entire codebase for security issues",
                "Run a full test suite analysis",
                "Index all files for search",
                "Batch update all dependencies",
                "Generate documentation for the whole project",
                "Perform a comprehensive performance review"
            )),
            new RouteCategory(TaskKind.ANSWER_ONLY, ExecutionLane.CHEAP_TRIAGE,
                TaskPath.CACHE_AMPLIFIED_PATH, "cache_amplified", List.of(
                "What was that result again?",
                "Can you remind me what we decided?",
                "What's the status of that task?",
                "Any progress on the build?",
                "Update on that issue?",
                "You just showed me that, can you show me again?",
                "What did we just do?",
                "Repeat the last thing you said",
                "Show me the output again"
            )),
            new RouteCategory(TaskKind.INSPECT_ONLY, ExecutionLane.CHEAP_TRIAGE,
                TaskPath.DIRECT_AGENT_PATH, "inspect_only", List.of(
                "Show me the contents of app.ts",
                "List all exported functions",
                "Where is the database connection defined?",
                "What does this regex do?",
                "Read the README"
            ))
        );
    }
}
