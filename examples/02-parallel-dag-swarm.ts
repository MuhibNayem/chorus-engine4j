/**
 * Example 2: Parallel DAG Swarm
 *
 * Three agents in a dependency graph:
 *   researcher ─┐
 *               ├─▶ synthesizer
 *   analyst   ──┘
 *
 * researcher and analyst run in parallel (wave 0), synthesizer waits (wave 1).
 * Run: npx ts-node --esm examples/02-parallel-dag-swarm.ts
 */

import {
  configureEngine,
  runSwarmGraph,
  createFilesystemTools,
  type SwarmConfig,
} from "../src/index.js";
import { createProvider } from "../src/llm/registry.js";

configureEngine({
  llm: {
    provider: "openai",
    providers: { openai: { apiKey: process.env.OPENAI_API_KEY!, model: "gpt-4o-mini" } },
  },
});

const provider = createProvider("openai");
const fsTools = createFilesystemTools(process.cwd());

const controller = new AbortController();
setTimeout(() => controller.abort(), 120_000); // 2-minute hard timeout

const config: SwarmConfig = {
  provider,
  modelName: "gpt-4o-mini",
  task: "Analyze the current state of TypeScript agent frameworks and produce a comparison report.",
  spec: "Deliver a structured markdown report with evidence from multiple sources.",
  executionModel: "graph",
  abortSignal: controller.signal,

  // Fine-tune the circuit breaker for this workload
  circuitBreaker: {
    maxConsecutiveSameAgent: 2,
    maxTokensPerAgent: 30_000,
  },

  // Hard cost ceiling — never spend more than $0.10 total
  costBudget: { totalUsd: 0.10 },

  agents: [
    {
      name: "researcher",
      description: "Researches TypeScript agent frameworks",
      systemPrompt: "You are a research analyst. Find and summarize 3-5 TypeScript agent frameworks. Use set_artifact to store your findings under key 'research'.",
      tools: fsTools,
      handoffDestinations: [],
      contextMode: "isolated",
      maxRounds: 5,
    },
    {
      name: "analyst",
      description: "Analyzes framework adoption trends",
      systemPrompt: "You are a market analyst. Evaluate developer adoption signals for agent frameworks. Use set_artifact to store your analysis under key 'analysis'.",
      tools: [],
      handoffDestinations: [],
      contextMode: "isolated",
      maxRounds: 3,
    },
    {
      name: "synthesizer",
      description: "Synthesizes findings into a final report",
      systemPrompt: "You are a technical writer. Retrieve the 'research' and 'analysis' artifacts and produce a final comparison report in markdown.",
      tools: [],
      handoffDestinations: [],
      contextMode: "isolated",
      maxRounds: 3,
      dependsOn: ["researcher", "analyst"], // waits for both
      requiredArtifacts: ["research"],       // circuit-breaks if researcher didn't deliver
    },
  ],
  initialAgent: "researcher",
};

console.log("Starting parallel DAG swarm...\n");

for await (const event of runSwarmGraph(config)) {
  switch (event.type) {
    case "swarm-start":
      console.log(`[swarm] started — agents: ${event.agents.join(", ")}`);
      break;
    case "wave-start":
      console.log(`[wave ${event.wave}] starting: ${event.agents.join(", ")}`);
      break;
    case "wave-done":
      console.log(`[wave ${event.wave}] done. Artifacts: ${event.artifacts.join(", ") || "none"}`);
      break;
    case "agent-done":
      console.log(`[${event.agent}] done — $${event.metrics.costUsd.toFixed(6)}`);
      break;
    case "circuit-break":
      console.error(`[circuit-break] ${event.agent}: ${event.reason}`);
      break;
    case "swarm-done":
      console.log(`\n[swarm] complete — total cost: $${event.totalCostUsd.toFixed(6)}, duration: ${event.durationMs}ms`);
      break;
  }
}
