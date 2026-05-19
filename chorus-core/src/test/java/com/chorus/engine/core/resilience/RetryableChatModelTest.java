package com.chorus.engine.core.resilience;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.llm.ChorusChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RetryableChatModelTest {

    @Test
    void succeedsOnFirstAttempt() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("gpt-4");
        when(delegate.generate(any(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                new ChorusChatModel.ModelResponse("hello", "", List.of(), 5, 2)));

        RetryableChatModel model = new RetryableChatModel(delegate, 3, Duration.ofMillis(10), Duration.ofMillis(100));
        ChorusChatModel.ModelResponse response = model.generate(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join();

        assertThat(response.content()).isEqualTo("hello");
        verify(delegate, times(1)).generate(any(), any(), any(), any());
    }

    @Test
    void retriesOnFailureAndSucceeds() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("gpt-4");

        AtomicInteger calls = new AtomicInteger(0);
        when(delegate.generate(any(), any(), any(), any()))
            .thenAnswer(inv -> {
                if (calls.incrementAndGet() < 3) {
                    return CompletableFuture.failedFuture(new RuntimeException("transient error"));
                }
                return CompletableFuture.completedFuture(
                    new ChorusChatModel.ModelResponse("success", "", List.of(), 5, 2));
            });

        RetryableChatModel model = new RetryableChatModel(delegate, 3, Duration.ofMillis(10), Duration.ofMillis(100));
        ChorusChatModel.ModelResponse response = model.generate(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join();

        assertThat(response.content()).isEqualTo("success");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void failsAfterMaxRetriesExceeded() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("gpt-4");
        when(delegate.generate(any(), any(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("persistent error")));

        RetryableChatModel model = new RetryableChatModel(delegate, 2, Duration.ofMillis(10), Duration.ofMillis(100));

        assertThatThrownBy(() -> model.generate(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join())
            .hasCauseInstanceOf(RuntimeException.class)
            .hasMessageContaining("persistent error");

        // Initial attempt + 2 retries = 3 total calls
        verify(delegate, times(3)).generate(any(), any(), any(), any());
    }

    @Test
    void circuitBreakerOpensAfterFailures() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("gpt-4");
        when(delegate.generate(any(), any(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        RetryableChatModel model = new RetryableChatModel(delegate, 2, Duration.ofMillis(1), Duration.ofMillis(10));
        CircuitBreaker cb = model.getCircuitBreaker();

        // First call fails (1 attempt + 2 retries, 1 failure recorded)
        assertThatThrownBy(() -> model.generate(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join())
            .hasMessageContaining("boom");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED); // threshold=2, only 1 failure

        // Second call fails → circuit opens
        assertThatThrownBy(() -> model.generate(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join())
            .hasMessageContaining("boom");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Third call: circuit breaker rejects immediately
        assertThatThrownBy(() -> model.generate(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join())
            .hasMessageContaining("Circuit breaker is OPEN");
    }

    @Test
    void circuitBreakerResetsAfterSuccess() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("gpt-4");

        AtomicInteger calls = new AtomicInteger(0);
        when(delegate.generate(any(), any(), any(), any()))
            .thenAnswer(inv -> {
                int c = calls.incrementAndGet();
                if (c == 1) {
                    return CompletableFuture.failedFuture(new RuntimeException("transient"));
                }
                return CompletableFuture.completedFuture(
                    new ChorusChatModel.ModelResponse("ok", "", List.of(), 5, 2));
            });

        RetryableChatModel model = new RetryableChatModel(delegate, 3, Duration.ofMillis(1), Duration.ofMillis(10));
        CircuitBreaker cb = model.getCircuitBreaker();

        ChorusChatModel.ModelResponse response = model.generate(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join();

        assertThat(response.content()).isEqualTo("ok");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void propagatesStreamCalls() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("gpt-4");

        ChorusChatModel.StreamingResponse streaming = mock(ChorusChatModel.StreamingResponse.class);
        when(delegate.stream(any(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(streaming));

        RetryableChatModel model = new RetryableChatModel(delegate, 2, Duration.ofMillis(10), Duration.ofMillis(100));
        ChorusChatModel.StreamingResponse response = model.stream(
            List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join();

        assertThat(response).isSameAs(streaming);
    }

    @Test
    void delegatesName() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("claude-3");

        RetryableChatModel model = new RetryableChatModel(delegate, 2, Duration.ofMillis(10), Duration.ofMillis(100));
        assertThat(model.name()).isEqualTo("claude-3");
    }

    @Test
    void exponentialBackoffWithJitter() {
        ChorusChatModel delegate = mock(ChorusChatModel.class);
        when(delegate.name()).thenReturn("gpt-4");

        AtomicInteger calls = new AtomicInteger(0);
        long[] timestamps = new long[4];
        when(delegate.generate(any(), any(), any(), any()))
            .thenAnswer(inv -> {
                timestamps[calls.get()] = System.currentTimeMillis();
                if (calls.incrementAndGet() < 4) {
                    return CompletableFuture.failedFuture(new RuntimeException("retry"));
                }
                return CompletableFuture.completedFuture(
                    new ChorusChatModel.ModelResponse("done", "", List.of(), 5, 2));
            });

        RetryableChatModel model = new RetryableChatModel(delegate, 3, Duration.ofMillis(50), Duration.ofMillis(500));
        model.generate(List.of(ChatMessage.user("hi")), "sys", List.of(), "gpt-4").join();

        // Delays should increase (with jitter), but stay within bounds
        long delay1 = timestamps[1] - timestamps[0];
        long delay2 = timestamps[2] - timestamps[1];
        long delay3 = timestamps[3] - timestamps[2];

        // Each delay should be at least base * (1 - jitter) and at most maxDelay
        assertThat(delay1).isGreaterThanOrEqualTo(30);  // 50ms * 0.8 (min jitter)
        assertThat(delay1).isLessThanOrEqualTo(500);
        assertThat(delay2).isGreaterThanOrEqualTo(30);  // 50ms * 2 * 0.8 with jitter variation
        assertThat(delay2).isLessThanOrEqualTo(500);
        assertThat(delay3).isGreaterThanOrEqualTo(30);  // 50ms * 4 * 0.8
        assertThat(delay3).isLessThanOrEqualTo(500);
    }
}
