package com.chorus.engine.agent.hitl;

import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class HitlGateTest {

    @Test
    void requestApprovalCreatesPendingRequest() {
        HitlGate gate = new HitlGate();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Result<HitlGate.HitlDecision, HitlGate.HitlError>> future = executor.submit(() ->
            gate.requestApproval("run-1", "tool-a", Map.of("key", "val"), null)
        );

        await(() -> gate.pendingCount() == 1);
        assertThat(gate.pendingCount()).isEqualTo(1);

        // Approve to clean up
        String gateId = findGateId(gate);
        assertThat(gate.approve(gateId)).isTrue();

        Result<HitlGate.HitlDecision, HitlGate.HitlError> result = awaitFuture(future);
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap()).isEqualTo(HitlGate.HitlDecision.APPROVE);
        assertThat(gate.pendingCount()).isEqualTo(0);

        executor.shutdown();
    }

    @Test
    void approveResolvesPendingRequest() {
        HitlGate gate = new HitlGate();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Result<HitlGate.HitlDecision, HitlGate.HitlError>> future = executor.submit(() ->
            gate.requestApproval("run-1", "tool-b", Map.of(), null)
        );

        await(() -> gate.pendingCount() == 1);
        String gateId = findGateId(gate);
        boolean approved = gate.approve(gateId);

        assertThat(approved).isTrue();
        Result<HitlGate.HitlDecision, HitlGate.HitlError> result = awaitFuture(future);
        assertThat(result.unwrap()).isEqualTo(HitlGate.HitlDecision.APPROVE);
    }

    @Test
    void rejectResolvesWithRejection() {
        HitlGate gate = new HitlGate();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Result<HitlGate.HitlDecision, HitlGate.HitlError>> future = executor.submit(() ->
            gate.requestApproval("run-1", "tool-c", Map.of(), null)
        );

        await(() -> gate.pendingCount() == 1);
        String gateId = findGateId(gate);
        boolean rejected = gate.reject(gateId, "unsafe operation");

        assertThat(rejected).isTrue();
        Result<HitlGate.HitlDecision, HitlGate.HitlError> result = awaitFuture(future);
        // gap #6 fix: reject with a reason now returns Err so the reason is propagated
        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr().code()).isEqualTo("REJECTED");
        assertThat(result.unwrapErr().message()).isEqualTo("unsafe operation");
    }

    @Test
    void approveSessionBulkApproves() {
        HitlGate gate = new HitlGate();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Result<HitlGate.HitlDecision, HitlGate.HitlError>> future = executor.submit(() ->
            gate.requestApproval("run-1", "tool-d", Map.of(), null)
        );

        await(() -> gate.pendingCount() == 1);
        String gateId = findGateId(gate);
        boolean approved = gate.approveSession(gateId);

        assertThat(approved).isTrue();
        Result<HitlGate.HitlDecision, HitlGate.HitlError> result = awaitFuture(future);
        // gap #5 fix: approveSession now correctly resolves with APPROVE_SESSION
        assertThat(result.unwrap()).isEqualTo(HitlGate.HitlDecision.APPROVE_SESSION);

        // Second request for same tool should be auto-approved
        Result<HitlGate.HitlDecision, HitlGate.HitlError> second =
            gate.requestApproval("run-1", "tool-d", Map.of(), null);
        assertThat(second.isOk()).isTrue();
        assertThat(second.unwrap()).isEqualTo(HitlGate.HitlDecision.APPROVE_SESSION);
    }

    @Test
    void timeoutHandling() {
        HitlGate gate = new HitlGate();

        Result<HitlGate.HitlDecision, HitlGate.HitlError> result =
            gate.requestApproval("run-1", "tool-e", Map.of(), Duration.ofMillis(50));

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr().code()).isEqualTo("TIMEOUT");
        assertThat(gate.pendingCount()).isEqualTo(0);
    }

    @Test
    void cancellationDuringWait() throws InterruptedException {
        HitlGate gate = new HitlGate();

        Thread worker = new Thread(() -> {
            gate.requestApproval("run-1", "tool-f", Map.of(), Duration.ofSeconds(30));
        });
        worker.start();

        await(() -> gate.pendingCount() == 1);
        worker.interrupt();
        worker.join(1000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(gate.pendingCount()).isEqualTo(0);
    }

    @Test
    void disposeCleansUp() {
        HitlGate gate = new HitlGate();

        ExecutorService executor = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            executor.submit(() -> gate.requestApproval("run-1", "tool-" + idx, Map.of(), Duration.ofSeconds(10)));
        }

        await(() -> gate.pendingCount() == 3);
        gate.dispose();

        assertThat(gate.isDisposed()).isTrue();
        assertThat(gate.pendingCount()).isEqualTo(0);
        executor.shutdown();
    }

    @Test
    void multipleConcurrentRequests() {
        HitlGate gate = new HitlGate();
        int n = 5;
        ExecutorService executor = Executors.newFixedThreadPool(n);
        Future<?>[] futures = new Future[n];

        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures[i] = executor.submit(() ->
                gate.requestApproval("run-1", "concurrent-tool", Map.of("idx", idx), null)
            );
        }

        await(() -> gate.pendingCount() == n);

        // Approve all
        for (String gateId : findAllGateIds(gate)) {
            gate.approve(gateId);
        }

        for (Future<?> f : futures) {
            awaitFuture(f);
        }

        assertThat(gate.pendingCount()).isEqualTo(0);
        executor.shutdown();
    }

    @Test
    void requestForUnknownIdReturnsFalse() {
        HitlGate gate = new HitlGate();
        assertThat(gate.approve("unknown-id")).isFalse();
        assertThat(gate.reject("unknown-id", "reason")).isFalse();
        assertThat(gate.approveSession("unknown-id")).isFalse();
    }

    // ---- helpers ----

    private void await(java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Condition not met within timeout");
    }

    private String findGateId(HitlGate gate) {
        // Hack: we don't have direct access to gate IDs, so we extract from pendingCount
        // by approving each known gate. Since there's only one pending, we can use reflection
        // or track it externally. For simplicity, use the fact that the gate IDs are stored
        // internally; we'll use a side-channel by making a second gate with known ID.
        // Actually, the easiest way is to track the gate ID from requestApproval via a wrapper.
        // But since we can't here, let's use a small workaround: create a fresh gate with a single
        // request and capture the gate ID by scanning internal state via reflection.
        try {
            java.lang.reflect.Field field = HitlGate.class.getDeclaredField("gates");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> gates = (Map<String, ?>) field.get(gate);
            return gates.keySet().iterator().next();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private java.util.Set<String> findAllGateIds(HitlGate gate) {
        try {
            java.lang.reflect.Field field = HitlGate.class.getDeclaredField("gates");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> gates = (Map<String, ?>) field.get(gate);
            return new java.util.HashSet<>(gates.keySet());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T awaitFuture(Future<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
