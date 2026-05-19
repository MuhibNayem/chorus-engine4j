package com.chorus.engine.swarm.consensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentConsensusEngineTest {

    @Test
    void unanimousConsensus() {
        AgentConsensusEngine.Voter<String> voter1 = proposal ->
            new AgentConsensusEngine.Vote<>("YES", 0.95, "Looks good");
        AgentConsensusEngine.Voter<String> voter2 = proposal ->
            new AgentConsensusEngine.Vote<>("YES", 0.90, "Agreed");
        AgentConsensusEngine.Voter<String> voter3 = proposal ->
            new AgentConsensusEngine.Vote<>("YES", 0.92, "Approved");

        var voters = List.of(
            new AgentConsensusEngine.Agent<>("v1", "Honest A", voter1),
            new AgentConsensusEngine.Agent<>("v2", "Honest B", voter2),
            new AgentConsensusEngine.Agent<>("v3", "Honest C", voter3)
        );

        var engine = new AgentConsensusEngine<String, String>(
            voters, p -> p, 0.6, 2.0);

        var result = engine.decide("Deploy to production?");

        assertTrue(result.consensusReached());
        assertEquals("YES", result.winningValue());
        assertTrue(result.consensusScore() >= 0.9);
    }

    @Test
    void splitVoteNoConsensus() {
        var voters = List.of(
            new AgentConsensusEngine.Agent<>("v1", "A",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("YES", 0.9, null)),
            new AgentConsensusEngine.Agent<>("v2", "B",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("NO", 0.9, null)),
            new AgentConsensusEngine.Agent<>("v3", "C",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("NO", 0.9, null))
        );

        var engine = new AgentConsensusEngine<String, String>(
            voters, p -> p, 0.8, 2.0);

        var result = engine.decide("Deploy?");

        assertFalse(result.consensusReached());
        assertEquals("NO", result.winningValue());
    }

    @Test
    void byzantineFaultTolerance() {
        var voters = List.of(
            new AgentConsensusEngine.Agent<>("v1", "Honest",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("CORRECT", 0.95, null)),
            new AgentConsensusEngine.Agent<>("v2", "Honest",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("CORRECT", 0.94, null)),
            new AgentConsensusEngine.Agent<>("v3", "Honest",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("CORRECT", 0.93, null)),
            new AgentConsensusEngine.Agent<>("v4", "Byzantine",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("WRONG", 0.99, null))
        );

        var engine = new AgentConsensusEngine<String, String>(
            voters, p -> p, 0.6, 2.0);

        var result = engine.decide("Test");

        assertTrue(result.consensusReached());
        assertEquals("CORRECT", result.winningValue());
    }

    @Test
    void trackRecordUpdates() {
        var voters = List.of(
            new AgentConsensusEngine.Agent<>("v1", "Expert",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("A", 0.9, null)),
            new AgentConsensusEngine.Agent<>("v2", "Novice",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("B", 0.9, null))
        );

        var engine = new AgentConsensusEngine<String, String>(
            voters, p -> p, 0.5, 2.0);

        engine.decide("Q1");

        var record = engine.trackRecord();
        var snapshot = record.snapshot();
        assertTrue(snapshot.containsKey("v1"));
        assertTrue(snapshot.containsKey("v2"));
        assertTrue(snapshot.get("v1").accuracy() > snapshot.get("v2").accuracy());
    }

    @Test
    void handlesVoterExceptions() {
        // 3 voters: 1 fails, 2 agree → quorum is 2 out of 3
        var voters = List.of(
            new AgentConsensusEngine.Agent<>("v1", "Good",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("YES", 0.9, null)),
            new AgentConsensusEngine.Agent<>("v2", "Good2",
                (AgentConsensusEngine.Voter<String>) proposal ->
                    new AgentConsensusEngine.Vote<>("YES", 0.85, null)),
            new AgentConsensusEngine.Agent<>("v3", "Broken",
                (AgentConsensusEngine.Voter<String>) proposal -> {
                    throw new RuntimeException("Simulated failure");
                })
        );

        var engine = new AgentConsensusEngine<String, String>(
            voters, p -> p, 0.5, 2.0);

        var result = engine.decide("Test");

        assertTrue(result.consensusReached());
        assertEquals("YES", result.winningValue());
        assertEquals(3, result.rawVotes().size());
    }

    @Test
    void weightedVotingFavorsAccurateAgents() {
        var expert = new AgentConsensusEngine.Agent<>("expert", "Expert",
            (AgentConsensusEngine.Voter<String>) proposal ->
                new AgentConsensusEngine.Vote<>("YES", 0.95, null));
        var novice = new AgentConsensusEngine.Agent<>("novice", "Novice",
            (AgentConsensusEngine.Voter<String>) proposal ->
                new AgentConsensusEngine.Vote<>("NO", 0.7, null));

        var engine = new AgentConsensusEngine<String, String>(
            List.of(expert, novice), p -> p, 0.5, 2.0);

        for (int i = 0; i < 5; i++) {
            engine.decide("q" + i);
        }

        var record = engine.trackRecord();
        double expertWeight = record.getWeight("expert");
        double noviceWeight = record.getWeight("novice");

        assertTrue(expertWeight > noviceWeight);
    }
}
