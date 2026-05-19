package com.chorus.engine.core.loop;

import com.chorus.engine.core.ApprovalPolicy;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.event.HitlDecision;
import com.chorus.engine.core.hitl.HitlGate;
import com.chorus.engine.core.llm.ChorusChatModel;
import com.chorus.engine.core.middleware.AgentMiddleware;
import com.chorus.engine.core.tool.AgentTool;
import com.chorus.engine.core.trace.TraceContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public record LoopOptions(
    ChorusChatModel chatModel,
    String model,
    List<AgentTool> tools,
    List<com.chorus.engine.core.event.ChatMessage> messages,
    String systemPrompt,
    String threadId,
    HitlGate hitlGate,
    ApprovalPolicy policy,
    Checkpointer checkpointer,
    int maxRounds,
    Optional<HitlDecision> resumedDecision,
    List<AgentMiddleware> middleware,
    AtomicBoolean abortSignal,
    long streamTimeoutMs,
    Map<String, Object> outputSchema,
    Optional<TraceContext> traceContext
) {
    public LoopOptions {
        if (maxRounds <= 0) maxRounds = 500;
        if (streamTimeoutMs <= 0) streamTimeoutMs = 120_000;
        if (middleware == null) middleware = List.of();
        if (resumedDecision == null) resumedDecision = Optional.empty();
        if (abortSignal == null) abortSignal = new AtomicBoolean(false);
        if (traceContext == null) traceContext = Optional.empty();
    }
}
