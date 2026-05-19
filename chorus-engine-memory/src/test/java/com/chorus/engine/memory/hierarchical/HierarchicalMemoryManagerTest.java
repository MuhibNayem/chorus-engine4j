package com.chorus.engine.memory.hierarchical;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.memory.LongTermMemory;
import com.chorus.engine.memory.ShortTermMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HierarchicalMemoryManagerTest {

    HierarchicalMemoryManager manager;

    @BeforeEach
    void setUp() {
        ShortTermMemory working = new ShortTermMemory(1000, 100);
        EpisodicMemory episodic = new EpisodicMemory(100, 30);
        LongTermMemory semantic = new LongTermMemory(null, null);
        ProceduralMemory procedural = new ProceduralMemory(50);
        manager = new HierarchicalMemoryManager(working, episodic, semantic, procedural);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void storeAndRetrieveWorking() {
        Message msg = Message.user("Hello world");
        manager.storeWorking(msg, 10);

        List<Message> recent = manager.workingRecent(5);
        assertEquals(1, recent.size());
        assertEquals("Hello world", recent.get(0).content());
    }

    @Test
    void recordAndQueryEpisodes() {
        Message msg = Message.assistant("I processed the refund");
        String id = manager.recordEpisode(msg, "refund_processed",
            Map.of("customer_id", "12345"), "success");

        assertNotNull(id);
        List<EpisodicMemory.Episode> recent = manager.episodicRecent(5);
        assertEquals(1, recent.size());
        assertEquals("refund_processed", recent.get(0).eventType());

        List<EpisodicMemory.Episode> byEntity = manager.episodicByEntity("customer_id", "12345", 5);
        assertEquals(1, byEntity.size());
    }

    @Test
    void learnAndRetrieveProcedures() {
        manager.learnProcedure("refund-flow", "Process a customer refund",
            List.of("Verify order", "Check eligibility", "Issue refund", "Notify customer"),
            Map.of("department", "finance"));

        List<ProceduralMemory.Procedure> found = manager.proceduresByKeyword("refund");
        assertEquals(1, found.size());
        assertEquals("refund-flow", found.get(0).id());
        assertEquals(4, found.get(0).steps().size());
    }

    @Test
    void procedureSuccessTracking() {
        manager.learnProcedure("p1", "Test procedure", List.of("step1"), Map.of());
        manager.recordProcedureOutcome("p1", true);
        manager.recordProcedureOutcome("p1", true);
        manager.recordProcedureOutcome("p1", false);

        List<ProceduralMemory.Procedure> reliable = manager.reliableProcedures(0.5);
        assertEquals(1, reliable.size());
        assertEquals(0.67, reliable.get(0).successRate(), 0.01);
    }

    @Test
    void assembleContextQueriesAllTiers() {
        manager.storeWorking(Message.user("query about refunds"), 5);
        manager.recordEpisode(Message.assistant("refund policy is 30 days"), "policy_lookup",
            Map.of(), null);
        manager.learnProcedure("refund-flow", "Process refund", List.of("step1"), Map.of());

        HierarchicalMemoryManager.ContextAssembly assembly = manager.assembleContext("refund", 3);
        assertFalse(assembly.working().isEmpty());
        assertFalse(assembly.episodes().isEmpty());
        assertFalse(assembly.skills().isEmpty());
    }

    @Test
    void statsReflectOperations() {
        manager.storeWorking(Message.user("test"), 2);
        manager.recordEpisode(Message.user("test"), "test", Map.of(), null);
        manager.learnProcedure("p1", "desc", List.of("s1"), Map.of());

        HierarchicalMemoryManager.Stats stats = manager.stats();
        assertEquals(1, stats.workingSize());
        assertEquals(1, stats.episodicSize());
        assertEquals(1, stats.proceduralSize());
    }
}
