import { countMessagesTokens } from "../context/tokenizer.js";
import type { SwarmAgent, SwarmSession, CircuitBreakerResult } from "./types.js";

export function checkCircuitBreaker(
  session: SwarmSession,
  agent: SwarmAgent,
): CircuitBreakerResult {
  // 1. handoff budget exhausted
  if (session.handoffCount >= session.maxHandoffs) {
    return {
      tripped: true,
      reason: `Max handoffs reached (${session.maxHandoffs}). Halting swarm.`,
    };
  }

  // 2. same agent in a tight loop
  const limit = session.maxConsecutiveSameAgent;
  const recent = session.agentHistory.slice(-limit);
  if (recent.length === limit && recent.every((name) => name === agent.name)) {
    return {
      tripped: true,
      reason: `Agent "${agent.name}" was selected ${limit} consecutive times — possible infinite loop.`,
    };
  }

  // 3. per-agent token budget
  const agentMsgs = session.agentMessages[agent.name] ?? [];
  if (agentMsgs.length > 0) {
    const used = countMessagesTokens(agentMsgs, "");
    const budget = session.tokenBudget.perAgent[agent.name] ?? 50_000;
    if (used >= budget) {
      return {
        tripped: true,
        reason: `Agent "${agent.name}" exceeded its token budget (${used} / ${budget} tokens).`,
      };
    }
  }

  return { tripped: false };
}
