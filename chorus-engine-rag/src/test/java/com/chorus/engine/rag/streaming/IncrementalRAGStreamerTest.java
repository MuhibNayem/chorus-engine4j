package com.chorus.engine.rag.streaming;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalRAGStreamerTest {

    private final List<IncrementalRAGStreamer> toClose = new ArrayList<>();

    @AfterEach
    void tearDown() {
        toClose.forEach(IncrementalRAGStreamer::close);
    }

    @Test
    void waitForAllStrategyBlocksUntilAllStagesComplete() throws InterruptedException {
        var stage1 = new DelayedRetrievalStage("scout", 1, 50, List.of(chunk("c1", "Scout result")));
        var stage2 = new DelayedRetrievalStage("dense", 2, 100, List.of(chunk("c2", "Dense result")));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1, stage2),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.WAIT_FOR_ALL
        );
        toClose.add(streamer);

        List<RagStreamEvent> events = collectEvents(streamer, "query", CancellationToken.create());

        // Both retrieval stages must complete BEFORE generation starts
        var retrievalCompleted = events.stream()
            .filter(e -> e instanceof RagStreamEvent.RetrievalCompleted)
            .map(e -> (RagStreamEvent.RetrievalCompleted) e)
            .toList();
        assertEquals(2, retrievalCompleted.size());

        var genStarted = events.stream()
            .filter(e -> e instanceof RagStreamEvent.GenerationStarted).findFirst();
        assertTrue(genStarted.isPresent());

        // GenerationStarted must come AFTER both retrievals
        int genStartIdx = events.indexOf(genStarted.get());
        int lastRetrievalIdx = events.lastIndexOf(retrievalCompleted.get(1));
        assertTrue(genStartIdx > lastRetrievalIdx,
            "WAIT_FOR_ALL: generation must start after all retrievals");

        assertTrue(events.stream().anyMatch(e -> e instanceof RagStreamEvent.SessionCompleted));
    }

    @Test
    void pipelineStrategyStartsGenerationAfterFirstWave() throws InterruptedException {
        var stage1 = new DelayedRetrievalStage("scout", 1, 50, List.of(chunk("c1", "Scout result")));
        var stage2 = new DelayedRetrievalStage("dense", 2, 200, List.of(chunk("c2", "Dense result")));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1, stage2),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.PIPELINE
        );
        toClose.add(streamer);

        List<RagStreamEvent> events = collectEvents(streamer, "query", CancellationToken.create());

        // Generation must start after first stage, before second stage
        var genStarted = events.stream()
            .filter(e -> e instanceof RagStreamEvent.GenerationStarted)
            .findFirst();
        assertTrue(genStarted.isPresent());

        int genStartIdx = events.indexOf(genStarted.get());

        var retrievals = events.stream()
            .filter(e -> e instanceof RagStreamEvent.RetrievalCompleted)
            .toList();
        assertEquals(2, retrievals.size());

        int firstRetrievalIdx = events.indexOf(retrievals.get(0));
        int secondRetrievalIdx = events.indexOf(retrievals.get(1));

        assertTrue(genStartIdx > firstRetrievalIdx,
            "PIPELINE: generation starts after first wave");
        assertTrue(genStartIdx < secondRetrievalIdx,
            "PIPELINE: generation starts BEFORE second wave completes");

        // Should have tokens emitted before second retrieval completes
        long tokensBeforeSecondRetrieval = events.subList(0, secondRetrievalIdx).stream()
            .filter(e -> e instanceof RagStreamEvent.Token).count();
        assertTrue(tokensBeforeSecondRetrieval > 0,
            "PIPELINE: tokens should stream while second wave is still retrieving");

        // Second wave should emit supplemental context
        assertTrue(events.stream().anyMatch(e -> e instanceof RagStreamEvent.SupplementalContext),
            "PIPELINE: late wave should emit SupplementalContext");
    }

    @Test
    void adaptiveStrategyWaitsForThreshold() throws InterruptedException {
        var stage1 = new DelayedRetrievalStage("scout", 1, 30, List.of(
            chunk("c1", "A"), chunk("c2", "B"), chunk("c3", "C")));
        var stage2 = new DelayedRetrievalStage("dense", 2, 100, List.of(chunk("c4", "D")));

        // Threshold: minWaves=2, so must wait for both stages
        var threshold = new AdaptiveThreshold(2, 0.90, Duration.ofMillis(50));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1, stage2),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.ADAPTIVE, threshold, null, Duration.ofSeconds(5)
        );
        toClose.add(streamer);

        List<RagStreamEvent> events = collectEvents(streamer, "query", CancellationToken.create());

        var genStarted = events.stream()
            .filter(e -> e instanceof RagStreamEvent.GenerationStarted)
            .findFirst();
        assertTrue(genStarted.isPresent());

        int genStartIdx = events.indexOf(genStarted.get());
        var retrievals = events.stream()
            .filter(e -> e instanceof RagStreamEvent.RetrievalCompleted).toList();
        assertEquals(2, retrievals.size());

        int lastRetrievalIdx = events.indexOf(retrievals.get(1));
        assertTrue(genStartIdx >= lastRetrievalIdx,
            "ADAPTIVE with minWaves=2: generation should wait for both waves");
    }

    @Test
    void adaptiveStrategyStartsEarlyWhenMaxWaitExceeded() throws InterruptedException {
        var stage1 = new DelayedRetrievalStage("slow", 1, 500, List.of(chunk("c1", "Slow result")));

        // maxWaitTime=50ms, so generation starts after 50ms even though wave takes 500ms
        var threshold = new AdaptiveThreshold(5, 0.99, Duration.ofMillis(50));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.ADAPTIVE, threshold, null, Duration.ofSeconds(5)
        );
        toClose.add(streamer);

        List<RagStreamEvent> events = collectEvents(streamer, "query", CancellationToken.create());

        assertTrue(events.stream().anyMatch(e -> e instanceof RagStreamEvent.GenerationStarted),
            "ADAPTIVE: should start generation when maxWaitTime exceeded");
    }

    @Test
    void pipelineEmitsSupplementalContextForLateWaves() throws InterruptedException {
        var stage1 = new DelayedRetrievalStage("scout", 1, 30, List.of(chunk("c1", "Scout")));
        var stage2 = new DelayedRetrievalStage("dense", 2, 150, List.of(chunk("c2", "Dense")));
        var stage3 = new DelayedRetrievalStage("rerank", 3, 300, List.of(chunk("c3", "Reranked")));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1, stage2, stage3),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.PIPELINE
        );
        toClose.add(streamer);

        List<RagStreamEvent> events = collectEvents(streamer, "query", CancellationToken.create());

        long supplementalCount = events.stream()
            .filter(e -> e instanceof RagStreamEvent.SupplementalContext)
            .count();
        assertEquals(2, supplementalCount,
            "PIPELINE: stages 2 and 3 should both emit SupplementalContext");
    }

    @Test
    void handlesRetrievalFailureGracefully() throws InterruptedException {
        var stage1 = new FailingRetrievalStage("broken", 1);
        var stage2 = new DelayedRetrievalStage("backup", 2, 50, List.of(chunk("c1", "Backup")));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1, stage2),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.WAIT_FOR_ALL
        );
        toClose.add(streamer);

        List<RagStreamEvent> events = collectEvents(streamer, "query", CancellationToken.create());

        assertTrue(events.stream().anyMatch(e -> e instanceof RagStreamEvent.RetrievalFailed),
            "Should report retrieval failure");
        assertTrue(events.stream().anyMatch(e -> e instanceof RagStreamEvent.SessionCompleted),
            "Should complete despite one failed stage");
    }

    @Test
    void respectsCancellationToken() throws InterruptedException {
        var stage1 = new DelayedRetrievalStage("slow", 1, 3000, List.of(chunk("c1", "Slow")));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.PIPELINE
        );
        toClose.add(streamer);

        CancellationToken token = CancellationToken.create();

        CountDownLatch started = new CountDownLatch(1);
        List<RagStreamEvent> events = new ArrayList<>();

        streamer.stream("query", RetrievalEngine.RetrieveOptions.defaults(5), token)
            .subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(RagStreamEvent event) { events.add(event); started.countDown(); }
                @Override public void onError(Throwable t) { started.countDown(); }
                @Override public void onComplete() { started.countDown(); }
            });

        Thread.sleep(50);
        token.cancel("User cancelled");

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertFalse(events.stream().anyMatch(e -> e instanceof RagStreamEvent.SessionCompleted),
            "Cancelled session should not emit SessionCompleted");
    }

    @Test
    void sessionCompletedHasCorrectStrategy() throws InterruptedException {
        var stage1 = new DelayedRetrievalStage("scout", 1, 30, List.of(chunk("c1", "Content")));

        var streamer = new IncrementalRAGStreamer(
            List.of(stage1),
            new CountingLlmClient(),
            "model", 0.3, 100, 2000,
            GenerationStrategy.PIPELINE
        );
        toClose.add(streamer);

        List<RagStreamEvent> events = collectEvents(streamer, "query", CancellationToken.create());

        var completed = events.stream()
            .filter(e -> e instanceof RagStreamEvent.SessionCompleted)
            .map(e -> (RagStreamEvent.SessionCompleted) e)
            .findFirst();

        assertTrue(completed.isPresent());
        assertEquals(GenerationStrategy.PIPELINE, completed.get().strategyUsed());
        assertEquals(1, completed.get().totalRetrievalStages());
        assertTrue(completed.get().totalLatencyMs() >= 0);
    }

    // ---- Helper methods ----

    private List<RagStreamEvent> collectEvents(
        IncrementalRAGStreamer streamer,
        String query,
        CancellationToken token
    ) throws InterruptedException {
        List<RagStreamEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        streamer.stream(query, RetrievalEngine.RetrieveOptions.defaults(5), token)
            .subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(RagStreamEvent event) { events.add(event); }
                @Override public void onError(Throwable t) { latch.countDown(); }
                @Override public void onComplete() { latch.countDown(); }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Stream did not complete in time");
        return events;
    }

    private Chunk chunk(String id, String text) {
        return new Chunk(id, "doc-" + id, text, 0, text.length() / 4, null, Map.of());
    }

    // ---- Stub implementations ----

    static class DelayedRetrievalStage implements RetrievalStage {
        private final String name;
        private final int priority;
        private final long delayMs;
        private final List<Chunk> results;

        DelayedRetrievalStage(String name, int priority, long delayMs, List<Chunk> results) {
            this.name = name;
            this.priority = priority;
            this.delayMs = delayMs;
            this.results = results;
        }

        @Override public String name() { return name; }
        @Override public int priority() { return priority; }
        @Override public long estimatedLatencyMs() { return delayMs; }

        @Override
        public CompletableFuture<List<Chunk>> retrieve(String query, RetrievalEngine.RetrieveOptions options) {
            return CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return results;
            });
        }
    }

    static class FailingRetrievalStage implements RetrievalStage {
        private final String name;
        private final int priority;

        FailingRetrievalStage(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override public String name() { return name; }
        @Override public int priority() { return priority; }
        @Override public long estimatedLatencyMs() { return 10; }

        @Override
        public CompletableFuture<List<Chunk>> retrieve(String query, RetrievalEngine.RetrieveOptions options) {
            return CompletableFuture.failedFuture(new RuntimeException("Simulated retrieval failure"));
        }
    }

    static class CountingLlmClient implements LlmClient {
        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() {}
                });
                for (int i = 0; i < 5; i++) {
                    if (cancellationToken.isCancelled()) break;
                    subscriber.onNext(new StreamEvent.Token("tok" + i, i, null));
                    try { Thread.sleep(15); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
                subscriber.onNext(new StreamEvent.Finish("stop", 10, 5));
                subscriber.onComplete();
            };
        }

        @Override public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            return new ChatResponse("r", "m", "stub", Message.assistant("hello"),
                new com.chorus.engine.core.context.TokenCount(10, 5, "m"),
                Duration.ZERO, "stop", null, null, Map.of());
        }
        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
        @Override public String providerName() { return "counting-stub"; }
    }
}
