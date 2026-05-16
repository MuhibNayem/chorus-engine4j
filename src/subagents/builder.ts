import { gitTools } from "../tools/index.js";
import { buildSubagentPrompt } from "../prompts/system.js";
import type { AgentTool } from "../agent/types.js";
import type { SubAgentDef } from "./types.js";

/**
 * Builder subagent — implementation-focused.
 *
 * Equipped with git tools for version control operations during code
 * implementation. Filesystem tools are injected at runtime by the
 * graph executor when worktree isolation is enabled.
 */
export const builderSubagent: SubAgentDef = {
  name: "builder",
  description: "Senior software engineer for production-quality code implementation",
  systemPrompt: buildSubagentPrompt("builder"),
  tools: gitTools as unknown as AgentTool[],
};
