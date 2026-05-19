package com.chorus.engine.telemetry.provenance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProvenanceTrackerTest {

    @Test
    void recordAndRetrieveEntry() {
        ProvenanceTracker tracker = new ProvenanceTracker();

        String id = tracker.record("run-1", "agent-A", "tool_call",
            Map.of("state", "active"), "User asked for weather", Map.of("tool", "get_weather"));

        ProvenanceTracker.ProvenanceEntry entry = tracker.get(id);
        assertNotNull(entry);
        assertEquals("run-1", entry.runId());
        assertEquals("agent-A", entry.agentId());
        assertEquals("tool_call", entry.decisionType());
        assertEquals("User asked for weather", entry.reasoning());
    }

    @Test
    void queryByRunId() {
        ProvenanceTracker tracker = new ProvenanceTracker();
        tracker.record("run-1", "a1", "decision", Map.of(), null, null);
        tracker.record("run-1", "a2", "decision", Map.of(), null, null);
        tracker.record("run-2", "a1", "decision", Map.of(), null, null);

        List<ProvenanceTracker.ProvenanceEntry> results = tracker.queryByRun("run-1");
        assertEquals(2, results.size());
    }

    @Test
    void queryByAgent() {
        ProvenanceTracker tracker = new ProvenanceTracker();
        tracker.record("run-1", "agent-A", "decision", Map.of(), null, null);
        tracker.record("run-1", "agent-B", "decision", Map.of(), null, null);

        List<ProvenanceTracker.ProvenanceEntry> results = tracker.queryByAgent("agent-A");
        assertEquals(1, results.size());
    }

    @Test
    void queryByType() {
        ProvenanceTracker tracker = new ProvenanceTracker();
        tracker.record("run-1", "a1", "tool_call", Map.of(), null, null);
        tracker.record("run-1", "a2", "router_choice", Map.of(), null, null);

        List<ProvenanceTracker.ProvenanceEntry> tools = tracker.queryByType("tool_call");
        assertEquals(1, tools.size());
    }

    @Test
    void getChainReturnsCausalChain() {
        ProvenanceTracker tracker = new ProvenanceTracker();

        String parent = tracker.record("run-1", "a1", "start", Map.of(), "start", null);
        String child = tracker.record("run-1", "a2", "action", Map.of(), "do something", null, List.of(parent));
        String grandchild = tracker.record("run-1", "a3", "result", Map.of(), "done", null, List.of(child));

        List<ProvenanceTracker.ProvenanceEntry> chain = tracker.getChain(grandchild);
        assertEquals(3, chain.size());
        assertEquals("start", chain.get(0).decisionType());
        assertEquals("action", chain.get(1).decisionType());
        assertEquals("result", chain.get(2).decisionType());
    }

    @Test
    void explainGeneratesReadableReport() {
        ProvenanceTracker tracker = new ProvenanceTracker();
        tracker.record("run-1", "agent-A", "tool_call", Map.of(), "Need weather", Map.of("city", "NYC"));
        tracker.record("run-1", "agent-A", "response", Map.of(), "Sunny today", "It's sunny");

        String explanation = tracker.explain("run-1");
        assertTrue(explanation.contains("Provenance Report for Run: run-1"));
        assertTrue(explanation.contains("tool_call"));
        assertTrue(explanation.contains("response"));
    }

    @Test
    void maxEntriesEnforced() {
        ProvenanceTracker tracker = new ProvenanceTracker(5);
        for (int i = 0; i < 10; i++) {
            tracker.record("run-" + i, "a", "decision", Map.of(), null, null);
        }
        assertEquals(5, tracker.size());
    }

    @Test
    void clearRemovesAllEntries() {
        ProvenanceTracker tracker = new ProvenanceTracker();
        tracker.record("run-1", "a", "decision", Map.of(), null, null);
        tracker.clear();
        assertEquals(0, tracker.size());
    }
}
