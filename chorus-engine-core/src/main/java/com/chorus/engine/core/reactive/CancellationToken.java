package com.chorus.engine.core.reactive;

import org.jspecify.annotations.NonNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Cooperative cancellation token. Thread-safe, lock-free.
 *
 * <p>Modeled after TypeScript's {@code AbortSignal} but adapted for
 * virtual-thread-friendly cancellation propagation.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String reason;
    private final CopyOnWriteArrayList<Consumer<String>> callbacks = new CopyOnWriteArrayList<>();

    private CancellationToken() {}

    public static @NonNull CancellationToken create() {
        return new CancellationToken();
    }

    public static @NonNull CancellationToken alreadyCancelled(@NonNull String reason) {
        CancellationToken token = new CancellationToken();
        token.cancel(reason);
        return token;
    }

    /**
     * Request cancellation. Idempotent. Callbacks fire synchronously on the calling thread.
     */
    public void cancel(@NonNull String reason) {
        if (cancelled.compareAndSet(false, true)) {
            this.reason = reason;
            for (Consumer<String> cb : callbacks) {
                try {
                    cb.accept(reason);
                } catch (Exception ignored) {
                    // Defensive: callbacks must not throw
                }
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public @NonNull String reason() {
        return reason != null ? reason : "cancelled";
    }

    /**
     * Register a callback invoked when cancellation occurs.
     * If already cancelled, fires immediately.
     */
    public void onCancel(@NonNull Consumer<String> callback) {
        if (cancelled.get()) {
            callback.accept(reason());
        } else {
            callbacks.add(callback);
        }
    }

    /**
     * Throw if cancelled. Use inside loops and blocking operations for cooperative cancellation.
     */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException(reason());
        }
    }

    /**
     * Creates a linked child token. Cancelling the parent cancels all children.
     */
    public @NonNull CancellationToken createLinked() {
        CancellationToken child = create();
        onCancel(child::cancel);
        return child;
    }
}
