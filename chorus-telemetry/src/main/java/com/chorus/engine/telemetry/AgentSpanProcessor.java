package com.chorus.engine.telemetry;

import com.chorus.engine.core.event.AgentEvent;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes {@link AgentEvent} sealed-interface events and creates
 * corresponding OpenTelemetry spans via {@link ChorusTelemetry}.
 * <p>
 * Maintains lightweight in-memory correlation state so that start/end
 * event pairs (rounds, tools, streams, HITL) are mapped to proper spans.
 */
public class AgentSpanProcessor {

    private static final Logger log = LoggerFactory.getLogger(AgentSpanProcessor.class);

    private final ChorusTelemetry telemetry;
    private final CostTracker costTracker;

    // Correlation maps keyed by synthetic identifiers
    private final Map<String, Span> activeRounds = new ConcurrentHashMap<>();
    private final Map<String, Span> activeTools = new ConcurrentHashMap<>();
    private final Map<String, Span> activeLlmCalls = new ConcurrentHashMap<>();
    private final Map<String, Span> activeHitl = new ConcurrentHashMap<>();
    private final Map<String, Instant> hitlStartTimes = new ConcurrentHashMap<>();

    private volatile String currentThreadId;
    private volatile String currentModel;

    public AgentSpanProcessor(ChorusTelemetry telemetry, CostTracker costTracker) {
        this.telemetry = telemetry;
        this.costTracker = costTracker;
    }

    /**
     * Process a single agent event. Exhaustive pattern matching over the
     * sealed {@link AgentEvent} hierarchy.
     */
    public void process(AgentEvent event) {
        switch (event) {
            case AgentEvent.RoundStartEvent e -> {
                currentThreadId = e.threadId();
                Span span = telemetry.startAgentRound(e.round(), e.threadId(), currentModel);
                activeRounds.put(roundKey(e.round(), e.threadId()), span);
            }
            case AgentEvent.RoundEndEvent e -> {
                Span span = activeRounds.remove(roundKey(e.round(), e.threadId()));
                if (span != null) {
                    telemetry.endAgentRound(span, -1, -1);
                }
            }
            case AgentEvent.ToolStartEvent e -> {
                Span span = telemetry.startToolCall(e.name());
                activeTools.put(e.id(), span);
            }
            case AgentEvent.ToolDoneEvent e -> {
                Span span = activeTools.remove(e.id());
                if (span != null) {
                    telemetry.endToolCall(span, true);
                }
            }
            case AgentEvent.ToolErrorEvent e -> {
                Span span = activeTools.remove(e.id());
                if (span != null) {
                    telemetry.endToolCall(span, false);
                }
            }
            case AgentEvent.StreamStartEvent e -> {
                currentModel = e.model();
                currentThreadId = e.threadId();
                Span span = telemetry.startLlmCall(e.model());
                activeLlmCalls.put(streamKey(e.round(), e.threadId()), span);
            }
            case AgentEvent.StreamEndEvent e -> {
                Span span = activeLlmCalls.remove(streamKey(e.round(), e.threadId()));
                if (span != null) {
                    // Latency is derived from span start to end automatically by OTel.
                    telemetry.endLlmCall(span, -1, -1, e.tokensEmitted());
                }
            }
            case AgentEvent.DoneEvent e -> {
                // Record cost for the current thread/run
                if (currentThreadId != null) {
                    costTracker.recordThreadTokens(currentThreadId, e.inputTokens(), e.outputTokens());
                }
                costTracker.recordRunCost("default-run", e.inputTokens(), e.outputTokens(),
                    new CostTracker.Pricing(0.0, 0.0));

                // End any active round for this thread (defensive)
                activeRounds.values().forEach(Span::end);
                activeRounds.clear();
            }
            case AgentEvent.HitlEvent e -> {
                Span span = telemetry.startHitlGate(e.resumeKey());
                activeHitl.put(e.resumeKey(), span);
                hitlStartTimes.put(e.resumeKey(), Instant.now());
            }
            case AgentEvent.CheckpointLoadedEvent e -> {
                currentThreadId = e.threadId();
                endHitlForThread(e.threadId());
            }
            case AgentEvent.CheckpointSavedEvent e -> {
                currentThreadId = e.threadId();
                endHitlForThread(e.threadId());
            }
            case AgentEvent.ErrorEvent e -> {
                if (e.fatal()) {
                    activeRounds.values().forEach(s -> {
                        s.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.message());
                        s.end();
                    });
                    activeRounds.clear();
                }
            }
            case AgentEvent.AbortedEvent ignored -> {
                activeRounds.values().forEach(Span::end);
                activeRounds.clear();
                activeLlmCalls.values().forEach(Span::end);
                activeLlmCalls.clear();
            }
            default -> {
                // TokenEvent, ThinkingEvent, BtwEvent, CompactedEvent,
                // GuardrailTriggeredEvent, MemoryRecallEvent, MemoryCompactEvent,
                // MiddlewareBeforeEvent, MiddlewareAfterEvent, HandoffEvent,
                // CheckpointEvent — no spans created for these.
            }
        }
    }

    private void endHitlForThread(String threadId) {
        activeHitl.entrySet().removeIf(entry -> {
            if (entry.getKey().contains(threadId)) {
                Instant start = hitlStartTimes.remove(entry.getKey());
                long duration = (start != null)
                    ? java.time.Duration.between(start, Instant.now()).toMillis()
                    : 0L;
                telemetry.endHitlGate(entry.getValue(), duration);
                return true;
            }
            return false;
        });
    }

    private static String roundKey(int round, String threadId) {
        return threadId + ":round:" + round;
    }

    private static String streamKey(int round, String threadId) {
        return threadId + ":stream:" + round;
    }
}
