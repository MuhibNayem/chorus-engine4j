package com.chorus.engine.core.reactive;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class FlowCollectorTest {

    @Test
    void toListWithNormalCompletion() throws Exception {
        List<String> result = FlowCollector.toList(
                new ImmediatePublisher<>("a", "b", "c"),
                Duration.ofSeconds(5),
                null
        );
        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void toListWithTimeout() {
        assertThatThrownBy(() ->
                FlowCollector.toList(
                        new StallingPublisher<>(),
                        Duration.ofMillis(50),
                        null
                )
        ).isInstanceOf(TimeoutException.class);
    }

    @Test
    void toListWithCancellation() {
        CancellationToken token = CancellationToken.alreadyCancelled("stopped");
        assertThatThrownBy(() ->
                FlowCollector.toList(
                        new ImmediatePublisher<>("a"),
                        Duration.ofSeconds(5),
                        token
                )
        ).isInstanceOf(InterruptedException.class)
                .hasMessageContaining("Cancelled");
    }

    @Test
    void lastWithMultipleItems() throws Exception {
        String result = FlowCollector.last(
                new ImmediatePublisher<>("first", "second", "third"),
                Duration.ofSeconds(5),
                null
        );
        assertThat(result).isEqualTo("third");
    }

    @Test
    void forEachAsyncCollectsItems() throws Exception {
        List<Integer> collected = new ArrayList<>();
        CompletableFuture<Void> future = FlowCollector.forEachAsync(
                new ImmediatePublisher<>(1, 2, 3),
                collected::add
        );
        future.get(2, TimeUnit.SECONDS);
        assertThat(collected).containsExactly(1, 2, 3);
    }

    @Test
    void forEachAsyncPropagatesConsumerException() {
        CompletableFuture<Void> future = FlowCollector.forEachAsync(
                new ImmediatePublisher<>(1, 2, 3),
                item -> {
                    if (item == 2) throw new IllegalStateException("boom");
                }
        );
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyPublisherToList() throws Exception {
        List<String> result = FlowCollector.toList(
                new EmptyPublisher<>(),
                Duration.ofSeconds(1),
                null
        );
        assertThat(result).isEmpty();
    }

    @Test
    void emptyPublisherLast() throws Exception {
        String result = FlowCollector.last(
                new EmptyPublisher<>(),
                Duration.ofSeconds(1),
                null
        );
        assertThat(result).isNull();
    }

    @Test
    void publisherThatThrows() {
        assertThatThrownBy(() ->
                FlowCollector.toList(
                        new ErrorPublisher<>(new IllegalStateException("pub error")),
                        Duration.ofSeconds(1),
                        null
                )
        ).isInstanceOf(IllegalStateException.class)
                .hasMessage("pub error");
    }

    @Test
    void backpressureHandling() throws Exception {
        BackpressurePublisher<String> publisher = new BackpressurePublisher<>("a", "b", "c", "d", "e");
        List<String> result = FlowCollector.toList(publisher, Duration.ofSeconds(2), null);
        assertThat(result).containsExactly("a", "b", "c", "d", "e");
        assertThat(publisher.getMaxRequested()).isPositive();
    }

    // ------------------------------------------------------------------
    // Hand-written fakes
    // ------------------------------------------------------------------

    private static final class ImmediatePublisher<T> implements Flow.Publisher<T> {
        private final List<T> items;

        @SafeVarargs
        ImmediatePublisher(T... items) {
            this.items = List.of(items);
        }

        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private volatile boolean cancelled = false;
                private int index = 0;

                @Override
                public void request(long n) {
                    if (cancelled) return;
                    long remaining = n;
                    while (remaining > 0 && index < items.size()) {
                        subscriber.onNext(items.get(index++));
                        remaining--;
                    }
                    if (index >= items.size() && !cancelled) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }
    }

    private static final class StallingPublisher<T> implements Flow.Publisher<T> {
        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // never emit
                }

                @Override
                public void cancel() {
                }
            });
        }
    }

    private static final class EmptyPublisher<T> implements Flow.Publisher<T> {
        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                }
            });
        }
    }

    private static final class ErrorPublisher<T> implements Flow.Publisher<T> {
        private final Throwable error;

        ErrorPublisher(Throwable error) {
            this.error = error;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onError(error);
                }

                @Override
                public void cancel() {
                }
            });
        }
    }

    private static final class BackpressurePublisher<T> implements Flow.Publisher<T> {
        private final List<T> items;
        private final AtomicLong maxRequested = new AtomicLong(0);

        @SafeVarargs
        BackpressurePublisher(T... items) {
            this.items = List.of(items);
        }

        long getMaxRequested() {
            return maxRequested.get();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private int index = 0;
                private boolean cancelled = false;

                @Override
                public void request(long n) {
                    if (cancelled) return;
                    maxRequested.updateAndGet(current -> Math.max(current, n));
                    long toEmit = Math.min(n, items.size() - index);
                    for (long i = 0; i < toEmit; i++) {
                        subscriber.onNext(items.get(index++));
                    }
                    if (index >= items.size()) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }
    }
}
