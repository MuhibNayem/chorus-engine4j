package com.chorus.engine.core.reactive;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Production-grade utility for collecting items from a {@link Flow.Publisher}.
 *
 * <p>Addresses the common need to bridge the reactive {@code Flow.Publisher} API
 * to synchronous or future-based consumption patterns without manual
 * {@code CountDownLatch} boilerplate at every call site.
 *
 * <p>All methods are cancellation-aware — passing a {@link CancellationToken}
 * ensures the subscriber cancels upstream on timeout or explicit cancellation.
 *
 * <p>Thread-safe. Reusable across all modules (agent, harness, swarm, graph).
 */
public final class FlowCollector {

    private FlowCollector() {}

    /**
     * Collect all items from a publisher into a list, blocking until completion.
     *
     * @param publisher the publisher to subscribe to
     * @param timeout   maximum time to wait
     * @param token     cancellation token (may be null)
     * @return all items emitted by the publisher
     * @throws java.util.concurrent.TimeoutException if the timeout is exceeded
     */
    public static <T> @NonNull List<T> toList(
        Flow.Publisher<T> publisher,
        @NonNull Duration timeout,
        @Nullable CancellationToken token
    ) throws TimeoutException, InterruptedException {
        ListCollector<T> collector = new ListCollector<>();
        publisher.subscribe(collector);
        return collector.await(timeout, token);
    }

    /**
     * Collect the final (last) item from a publisher, blocking until completion.
     * Useful when only the terminal result matters (e.g., {@code AgentEvent.Done}).
     */
    public static <T> @Nullable T last(
        Flow.Publisher<T> publisher,
        @NonNull Duration timeout,
        @Nullable CancellationToken token
    ) throws TimeoutException, InterruptedException {
        LastCollector<T> collector = new LastCollector<>();
        publisher.subscribe(collector);
        return collector.await(timeout, token);
    }

    /**
     * Subscribe to a publisher and forward each item to a consumer,
     * returning a CompletableFuture that completes when the publisher finishes.
     * Non-blocking — suitable for async chaining.
     */
    public static <T> @NonNull CompletableFuture<Void> forEachAsync(
        Flow.Publisher<T> publisher,
        @NonNull Consumer<T> consumer
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(T item) {
                try {
                    consumer.accept(item);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
            @Override public void onError(Throwable t) { future.completeExceptionally(t); }
            @Override public void onComplete() { future.complete(null); }
        });
        return future;
    }

    /**
     * Subscribe to a publisher and collect items into a CompletableFuture list.
     * Non-blocking — suitable for async chaining.
     */
    public static <T> @NonNull CompletableFuture<List<T>> toListAsync(
        Flow.Publisher<T> publisher
    ) {
        List<T> items = new ArrayList<>();
        CompletableFuture<List<T>> future = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(T item) { items.add(item); }
            @Override public void onError(Throwable t) { future.completeExceptionally(t); }
            @Override public void onComplete() { future.complete(List.copyOf(items)); }
        });
        return future;
    }

    // ------------------------------------------------------------------

    private static final class ListCollector<T> implements Flow.Subscriber<T> {
        private final List<T> items = new ArrayList<>();
        private final CompletableFuture<List<T>> future = new CompletableFuture<>();

        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(T item) { items.add(item); }
        @Override public void onError(Throwable t) { future.completeExceptionally(t); }
        @Override public void onComplete() { future.complete(List.copyOf(items)); }

        List<T> await(Duration timeout, CancellationToken token) throws TimeoutException, InterruptedException {
            if (token != null) {
                token.onCancel(reason -> future.cancel(true));
            }
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (java.util.concurrent.CancellationException e) {
                throw new InterruptedException("Cancelled");
            }
        }
    }

    private static final class LastCollector<T> implements Flow.Subscriber<T> {
        private T last;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(T item) { this.last = item; }
        @Override public void onError(Throwable t) { future.completeExceptionally(t); }
        @Override public void onComplete() { future.complete(last); }

        T await(Duration timeout, CancellationToken token) throws TimeoutException, InterruptedException {
            if (token != null) {
                token.onCancel(reason -> future.cancel(true));
            }
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (java.util.concurrent.CancellationException e) {
                throw new InterruptedException("Cancelled");
            }
        }
    }
}
