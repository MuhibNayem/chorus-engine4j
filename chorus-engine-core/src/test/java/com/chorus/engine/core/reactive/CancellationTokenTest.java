package com.chorus.engine.core.reactive;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class CancellationTokenTest {

    @Test
    void initially_not_cancelled() {
        CancellationToken token = CancellationToken.create();
        assertThat(token.isCancelled()).isFalse();
        token.throwIfCancelled();
    }

    @Test
    void cancel_sets_state() {
        CancellationToken token = CancellationToken.create();
        token.cancel("user request");
        assertThat(token.isCancelled()).isTrue();
        assertThat(token.reason()).isEqualTo("user request");
    }

    @Test
    void cancel_idempotent() {
        CancellationToken token = CancellationToken.create();
        token.cancel("first");
        token.cancel("second");
        assertThat(token.reason()).isEqualTo("first");
    }

    @Test
    void throwIfCancelled_after_cancel() {
        CancellationToken token = CancellationToken.alreadyCancelled("stop");
        assertThatThrownBy(token::throwIfCancelled)
            .isInstanceOf(CancellationException.class)
            .hasMessageContaining("stop");
    }

    @Test
    void onCancel_fires_when_cancelled() {
        AtomicBoolean fired = new AtomicBoolean(false);
        CancellationToken token = CancellationToken.create();
        token.onCancel(reason -> fired.set(true));
        assertThat(fired.get()).isFalse();
        token.cancel("now");
        assertThat(fired.get()).isTrue();
    }

    @Test
    void onCancel_fires_immediately_if_already_cancelled() {
        AtomicReference<String> reason = new AtomicReference<>();
        CancellationToken token = CancellationToken.alreadyCancelled("early");
        token.onCancel(reason::set);
        assertThat(reason.get()).isEqualTo("early");
    }

    @Test
    void linked_child_inherits_cancellation() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = parent.createLinked();
        assertThat(child.isCancelled()).isFalse();
        parent.cancel("parent cancelled");
        assertThat(child.isCancelled()).isTrue();
        assertThat(child.reason()).isEqualTo("parent cancelled");
    }

    @Test
    void callback_exception_does_not_propagate() {
        CancellationToken token = CancellationToken.create();
        token.onCancel(r -> { throw new RuntimeException("boom"); });
        assertThatNoException().isThrownBy(() -> token.cancel("test"));
        assertThat(token.isCancelled()).isTrue();
    }
}
