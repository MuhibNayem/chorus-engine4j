import { buildSubagentPrompt } from "../prompts/system.js";
import type { AgentTool } from "../agent/types.js";
import type { SubAgentDef } from "./types.js";

/**
 * Planner subagent — tool-lean by design.
 *
 * High-level planning should not prematurely jump into implementation.
 * The planner has NO filesystem or git tools; it focuses on architectural
 * reasoning and delegates implementation to the builder subagent.
 */
export const plannerSubagent: SubAgentDef = {
  name: "planner",
  description: "Expert system architect for deep architectural decisions and system design",
  systemPrompt: buildSubagentPrompt("planner"),
  tools: [] as AgentTool[],
};
