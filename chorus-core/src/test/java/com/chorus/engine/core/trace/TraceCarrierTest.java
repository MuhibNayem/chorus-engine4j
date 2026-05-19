package com.chorus.engine.core.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCarrierTest {

    @AfterEach
    void tearDown() {
        TraceCarrier.clear();
    }

    @Test
    void getReturnsNullWhenNotSet() {
        assertThat(TraceCarrier.get()).isNull();
        assertThat(TraceCarrier.hasContext()).isFalse();
    }

    @Test
    void setAndGetRoundTrip() {
        TraceContext ctx = TraceContext.createRoot();
        TraceCarrier.set(ctx);

        assertThat(TraceCarrier.get()).isEqualTo(ctx);
        assertThat(TraceCarrier.hasContext()).isTrue();
    }

    @Test
    void clearRemovesContext() {
        TraceCarrier.set(TraceContext.createRoot());
        TraceCarrier.clear();

        assertThat(TraceCarrier.get()).isNull();
        assertThat(TraceCarrier.hasContext()).isFalse();
    }

    @Test
    void getOrCreateGeneratesNewWhenMissing() {
        TraceContext ctx = TraceCarrier.getOrCreate();

        assertThat(ctx).isNotNull();
        assertThat(ctx.traceId()).isNotNull();
        assertThat(ctx.parentId()).isNotNull();
        assertThat(TraceCarrier.get()).isEqualTo(ctx);
    }

    @Test
    void getOrCreateReturnsExisting() {
        TraceContext existing = TraceContext.createRoot();
        TraceCarrier.set(existing);

        TraceContext ctx = TraceCarrier.getOrCreate();
        assertThat(ctx).isEqualTo(existing);
    }

    @Test
    void threadsAreIsolated() throws InterruptedException {
        TraceContext mainCtx = TraceContext.createRoot();
        TraceCarrier.set(mainCtx);

        TraceContext[] captured = new TraceContext[1];
        Thread t = new Thread(() -> {
            captured[0] = TraceCarrier.get();
        });
        t.start();
        t.join();

        assertThat(captured[0]).isNull();
        assertThat(TraceCarrier.get()).isEqualTo(mainCtx);
    }
}
