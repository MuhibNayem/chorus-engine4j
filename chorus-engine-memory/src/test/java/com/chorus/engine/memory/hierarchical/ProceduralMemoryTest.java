package com.chorus.engine.memory.hierarchical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ProceduralMemoryTest {

    @Test
    void learnAndGetProcedure() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Process refund",
            List.of("Verify order", "Check eligibility", "Issue refund"),
            Map.of("dept", "finance"));

        ProceduralMemory.Procedure proc = memory.get("p-1");
        assertThat(proc).isNotNull();
        assertThat(proc.description()).isEqualTo("Process refund");
        assertThat(proc.steps()).hasSize(3);
        assertThat(proc.context()).containsEntry("dept", "finance");
        assertThat(proc.successRate()).isEqualTo(0.0);
    }

    @Test
    void recordOutcomeSuccessTracking() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Test", List.of("step1"), Map.of());

        memory.recordSuccess("p-1");
        memory.recordSuccess("p-1");

        ProceduralMemory.Procedure proc = memory.get("p-1");
        assertThat(proc.invocationCount()).isEqualTo(2);
        assertThat(proc.successCount()).isEqualTo(2);
        assertThat(proc.successRate()).isEqualTo(1.0);
    }

    @Test
    void recordOutcomeFailureTracking() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Test", List.of("step1"), Map.of());

        memory.recordSuccess("p-1");
        memory.recordFailure("p-1");
        memory.recordFailure("p-1");

        ProceduralMemory.Procedure proc = memory.get("p-1");
        assertThat(proc.invocationCount()).isEqualTo(3);
        assertThat(proc.successCount()).isEqualTo(1);
        assertThat(proc.successRate()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void maxProceduresEnforcement() {
        ProceduralMemory memory = new ProceduralMemory(2);

        memory.learn("p-1", "Desc 1", List.of("s1"), Map.of());
        memory.learn("p-2", "Desc 2", List.of("s2"), Map.of());
        memory.learn("p-3", "Desc 3", List.of("s3"), Map.of());

        assertThat(memory.size()).isEqualTo(2);
        // p-1 should have been evicted (lowest invocation count and success rate)
        assertThat(memory.get("p-1")).isNull();
    }

    @Test
    void findByKeywordMatchesDescription() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Refund process", List.of("step1"), Map.of());
        memory.learn("p-2", "Order cancellation", List.of("step1"), Map.of());

        List<ProceduralMemory.Procedure> results = memory.findByKeyword("refund");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("p-1");
    }

    @Test
    void findByKeywordMatchesSteps() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Desc", List.of("verify email", "send receipt"), Map.of());

        List<ProceduralMemory.Procedure> results = memory.findByKeyword("receipt");
        assertThat(results).hasSize(1);
    }

    @Test
    void findReliableFiltersByRateAndInvocations() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Reliable", List.of("s1"), Map.of());
        memory.learn("p-2", "Unreliable", List.of("s1"), Map.of());

        memory.recordSuccess("p-1");
        memory.recordSuccess("p-1");
        memory.recordSuccess("p-1");

        memory.recordSuccess("p-2");
        memory.recordFailure("p-2");

        List<ProceduralMemory.Procedure> reliable = memory.findReliable(0.8);
        assertThat(reliable).hasSize(1);
        assertThat(reliable.get(0).id()).isEqualTo("p-1");
    }

    @Test
    void forgetRemovesProcedure() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Test", List.of("s1"), Map.of());

        memory.forget("p-1");
        assertThat(memory.get("p-1")).isNull();
        assertThat(memory.size()).isEqualTo(0);
    }

    @Test
    void allReturnsCopy() {
        ProceduralMemory memory = new ProceduralMemory(100);
        memory.learn("p-1", "Test", List.of("s1"), Map.of());

        List<ProceduralMemory.Procedure> all = memory.all();
        assertThat(all).hasSize(1);
    }

    @Test
    void nullProcedureIdRejection() {
        ProceduralMemory memory = new ProceduralMemory(100);
        assertThatThrownBy(() -> memory.learn(null, "desc", List.of("s1"), Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullDescriptionRejection() {
        ProceduralMemory memory = new ProceduralMemory(100);
        assertThatThrownBy(() -> memory.learn("p-1", null, List.of("s1"), Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullStepsRejection() {
        ProceduralMemory memory = new ProceduralMemory(100);
        assertThatThrownBy(() -> memory.learn("p-1", "desc", null, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordSuccessNullIdThrows() {
        ProceduralMemory memory = new ProceduralMemory(100);
        assertThatThrownBy(() -> memory.recordSuccess(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordFailureNullIdThrows() {
        ProceduralMemory memory = new ProceduralMemory(100);
        assertThatThrownBy(() -> memory.recordFailure(null))
            .isInstanceOf(NullPointerException.class);
    }
}
