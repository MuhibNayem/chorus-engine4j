package com.chorus.engine.harness;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.harness.fakes.FakeEmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SemanticTaskRouterTest {

    @Test
    void routeReturnsBestMatchingTask() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        // Make all prototype examples and the query share the same vector
        float[] shared = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
        client.setEmbedding("What is 2+2?", shared);
        client.setEmbedding("Explain how closures work in JavaScript", shared);
        client.setEmbedding("How do I use async/await?", shared);
        client.setEmbedding("What does the spread operator do?", shared);
        client.setEmbedding("When should I use let vs const?", shared);
        client.setEmbedding("Explain the difference between map and forEach", shared);
        client.setEmbedding("What is the latest version of React?", shared);
        client.setEmbedding("Search for best practices on error handling", shared);
        client.setEmbedding("Look up the official documentation", shared);
        client.setEmbedding("Find information about TypeScript decorators", shared);
        client.setEmbedding("Verify online whether this package is maintained", shared);
        client.setEmbedding("Check the release notes for Node.js 22", shared);
        client.setEmbedding("What's the current state of the art for vector databases?", shared);
        client.setEmbedding("Compare open source agent frameworks", shared);
        client.setEmbedding("Fix the null pointer exception in utils.js", shared);
        client.setEmbedding("Debug why tests are failing on CI", shared);
        client.setEmbedding("Investigate the memory leak", shared);
        client.setEmbedding("Root cause analysis for the 500 error", shared);
        client.setEmbedding("Why is the build breaking?", shared);
        client.setEmbedding("The API returns 403 — figure out why", shared);
        client.setEmbedding("Trace the race condition in the websocket handler", shared);
        client.setEmbedding("Refactor the authentication module across the codebase", shared);
        client.setEmbedding("Update all components to use the new hook", shared);
        client.setEmbedding("Migrate from JavaScript to TypeScript in the src folder", shared);
        client.setEmbedding("Rename every occurrence of oldApi to newApi", shared);
        client.setEmbedding("Restructure the project to use feature folders", shared);
        client.setEmbedding("Change the database schema and update all models", shared);
        client.setEmbedding("Fix the bug in helpers.ts", shared);
        client.setEmbedding("Add a validation function to utils.js", shared);
        client.setEmbedding("Update the config in package.json", shared);
        client.setEmbedding("Remove the deprecated method from api.ts", shared);
        client.setEmbedding("Implement the missing test in user.test.js", shared);
        client.setEmbedding("Audit the entire codebase for security issues", shared);
        client.setEmbedding("Run a full test suite analysis", shared);
        client.setEmbedding("Index all files for search", shared);
        client.setEmbedding("Batch update all dependencies", shared);
        client.setEmbedding("Generate documentation for the whole project", shared);
        client.setEmbedding("Perform a comprehensive performance review", shared);
        client.setEmbedding("Show me the contents of app.ts", shared);
        client.setEmbedding("List all exported functions", shared);
        client.setEmbedding("Where is the database connection defined?", shared);
        client.setEmbedding("What does this regex do?", shared);
        client.setEmbedding("Read the README", shared);

        SemanticTaskRouter router = new SemanticTaskRouter(client, VectorOperations.autoDetect(), 0.55);
        SemanticTaskRouter.SemanticRouteResult result = router.route("What is 2+2?", "");

        assertThat(result.method()).isEqualTo(SemanticTaskRouter.RoutingMethod.SEMANTIC);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    void confidenceThresholdEnforcementFallsBack() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        // Prototype examples get one vector
        float[] protoVec = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
        // Query gets an orthogonal vector -> similarity = 0
        float[] queryVec = {0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

        client.setEmbedding("Explain how closures work in JavaScript", protoVec);
        client.setEmbedding("How do I use async/await?", protoVec);
        client.setEmbedding("What does the spread operator do?", protoVec);
        client.setEmbedding("When should I use let vs const?", protoVec);
        client.setEmbedding("Explain the difference between map and forEach", protoVec);
        client.setEmbedding("What is the latest version of React?", protoVec);
        client.setEmbedding("Search for best practices on error handling", protoVec);
        client.setEmbedding("Look up the official documentation", protoVec);
        client.setEmbedding("Find information about TypeScript decorators", protoVec);
        client.setEmbedding("Verify online whether this package is maintained", protoVec);
        client.setEmbedding("Check the release notes for Node.js 22", protoVec);
        client.setEmbedding("What's the current state of the art for vector databases?", protoVec);
        client.setEmbedding("Compare open source agent frameworks", protoVec);
        client.setEmbedding("Fix the null pointer exception in utils.js", protoVec);
        client.setEmbedding("Debug why tests are failing on CI", protoVec);
        client.setEmbedding("Investigate the memory leak", protoVec);
        client.setEmbedding("Root cause analysis for the 500 error", protoVec);
        client.setEmbedding("Why is the build breaking?", protoVec);
        client.setEmbedding("The API returns 403 — figure out why", protoVec);
        client.setEmbedding("Trace the race condition in the websocket handler", protoVec);
        client.setEmbedding("Refactor the authentication module across the codebase", protoVec);
        client.setEmbedding("Update all components to use the new hook", protoVec);
        client.setEmbedding("Migrate from JavaScript to TypeScript in the src folder", protoVec);
        client.setEmbedding("Rename every occurrence of oldApi to newApi", protoVec);
        client.setEmbedding("Restructure the project to use feature folders", protoVec);
        client.setEmbedding("Change the database schema and update all models", protoVec);
        client.setEmbedding("Fix the bug in helpers.ts", protoVec);
        client.setEmbedding("Add a validation function to utils.js", protoVec);
        client.setEmbedding("Update the config in package.json", protoVec);
        client.setEmbedding("Remove the deprecated method from api.ts", protoVec);
        client.setEmbedding("Implement the missing test in user.test.js", protoVec);
        client.setEmbedding("Audit the entire codebase for security issues", protoVec);
        client.setEmbedding("Run a full test suite analysis", protoVec);
        client.setEmbedding("Index all files for search", protoVec);
        client.setEmbedding("Batch update all dependencies", protoVec);
        client.setEmbedding("Generate documentation for the whole project", protoVec);
        client.setEmbedding("Perform a comprehensive performance review", protoVec);
        client.setEmbedding("Show me the contents of app.ts", protoVec);
        client.setEmbedding("List all exported functions", protoVec);
        client.setEmbedding("Where is the database connection defined?", protoVec);
        client.setEmbedding("What does this regex do?", protoVec);
        client.setEmbedding("Read the README", protoVec);

        SemanticTaskRouter router = new SemanticTaskRouter(client, VectorOperations.autoDetect(), 0.55);
        // Use a query text that is NOT one of the prototype examples
        client.setEmbedding("Quantum physics overview", queryVec);
        SemanticTaskRouter.SemanticRouteResult result = router.route("Quantum physics overview", "");

        assertThat(result.method()).isEqualTo(SemanticTaskRouter.RoutingMethod.FALLBACK);
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    @Test
    void fallbackToRegexRouterOnEmbedError() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        client.setFailNext(true);

        SemanticTaskRouter router = new SemanticTaskRouter(client, VectorOperations.autoDetect());
        SemanticTaskRouter.SemanticRouteResult result = router.route("What is 2+2?", "");

        assertThat(result.method()).isEqualTo(SemanticTaskRouter.RoutingMethod.FALLBACK);
    }

    @Test
    void emptyInputRoutesViaFallback() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        SemanticTaskRouter router = new SemanticTaskRouter(client, VectorOperations.autoDetect());

        SemanticTaskRouter.SemanticRouteResult result = router.route("", "");

        assertThat(result.method()).isEqualTo(SemanticTaskRouter.RoutingMethod.FALLBACK);
    }

    @Test
    void scoreReturnsSortedList() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        float[] shared = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
        client.setEmbedding("What is 2+2?", shared);
        client.setEmbedding("Explain how closures work in JavaScript", shared);
        client.setEmbedding("How do I use async/await?", shared);
        client.setEmbedding("What does the spread operator do?", shared);
        client.setEmbedding("When should I use let vs const?", shared);
        client.setEmbedding("Explain the difference between map and forEach", shared);
        client.setEmbedding("What is the latest version of React?", shared);
        client.setEmbedding("Search for best practices on error handling", shared);
        client.setEmbedding("Look up the official documentation", shared);
        client.setEmbedding("Find information about TypeScript decorators", shared);
        client.setEmbedding("Verify online whether this package is maintained", shared);
        client.setEmbedding("Check the release notes for Node.js 22", shared);
        client.setEmbedding("What's the current state of the art for vector databases?", shared);
        client.setEmbedding("Compare open source agent frameworks", shared);
        client.setEmbedding("Fix the null pointer exception in utils.js", shared);
        client.setEmbedding("Debug why tests are failing on CI", shared);
        client.setEmbedding("Investigate the memory leak", shared);
        client.setEmbedding("Root cause analysis for the 500 error", shared);
        client.setEmbedding("Why is the build breaking?", shared);
        client.setEmbedding("The API returns 403 — figure out why", shared);
        client.setEmbedding("Trace the race condition in the websocket handler", shared);
        client.setEmbedding("Refactor the authentication module across the codebase", shared);
        client.setEmbedding("Update all components to use the new hook", shared);
        client.setEmbedding("Migrate from JavaScript to TypeScript in the src folder", shared);
        client.setEmbedding("Rename every occurrence of oldApi to newApi", shared);
        client.setEmbedding("Restructure the project to use feature folders", shared);
        client.setEmbedding("Change the database schema and update all models", shared);
        client.setEmbedding("Fix the bug in helpers.ts", shared);
        client.setEmbedding("Add a validation function to utils.js", shared);
        client.setEmbedding("Update the config in package.json", shared);
        client.setEmbedding("Remove the deprecated method from api.ts", shared);
        client.setEmbedding("Implement the missing test in user.test.js", shared);
        client.setEmbedding("Audit the entire codebase for security issues", shared);
        client.setEmbedding("Run a full test suite analysis", shared);
        client.setEmbedding("Index all files for search", shared);
        client.setEmbedding("Batch update all dependencies", shared);
        client.setEmbedding("Generate documentation for the whole project", shared);
        client.setEmbedding("Perform a comprehensive performance review", shared);
        client.setEmbedding("Show me the contents of app.ts", shared);
        client.setEmbedding("List all exported functions", shared);
        client.setEmbedding("Where is the database connection defined?", shared);
        client.setEmbedding("What does this regex do?", shared);
        client.setEmbedding("Read the README", shared);

        SemanticTaskRouter router = new SemanticTaskRouter(client, VectorOperations.autoDetect());
        List<SemanticTaskRouter.RouteScore> scores = router.score("What is 2+2?", "");

        assertThat(scores).isNotEmpty();
        // Scores should be sorted descending by confidence
        for (int i = 1; i < scores.size(); i++) {
            assertThat(scores.get(i - 1).confidence())
                .isGreaterThanOrEqualTo(scores.get(i).confidence());
        }
    }

    @Test
    void nullTextRejection() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        SemanticTaskRouter router = new SemanticTaskRouter(client, VectorOperations.autoDetect());

        assertThatThrownBy(() -> router.route(null, ""))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullExpandedTextRejection() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        SemanticTaskRouter router = new SemanticTaskRouter(client, VectorOperations.autoDetect());

        assertThatThrownBy(() -> router.route("test", null))
            .isInstanceOf(NullPointerException.class);
    }
}
