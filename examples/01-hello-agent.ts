/**
 * Example 1: Single Agent Loop
 *
 * The minimal setup: one provider, one agent loop, stream events to stdout.
 * Run: npx ts-node --esm examples/01-hello-agent.ts
 */

import { configureEngine, runAgentLoop, HitlGate, BtwQueue, JsonFileCheckpointer } from "../src/index.js";
import { createProvider } from "../src/llm/registry.js";

// ─── 1. Inject config — no ~/.chorus disk read ────────────────────────────────
configureEngine({
  llm: {
    provider: "openai",
    providers: {
      openai: {
        apiKey: process.env.OPENAI_API_KEY!,
        model: "gpt-4o-mini",
      },
    },
  },
});

// ─── 2. Wire up the agent loop ────────────────────────────────────────────────
const provider = createProvider("openai");

const stream = runAgentLoop({
  provider,
  model: "gpt-4o-mini",
  tools: [],
  messages: [{ role: "user", content: "What is 12 * 34?" }],
  systemPrompt: "You are a helpful assistant.",
  threadId: "example-01",
  hitlGate: new HitlGate(),
  btwQueue: new BtwQueue(),
  policy: "full_auto",
  checkpointer: new JsonFileCheckpointer(),
});

// ─── 3. Consume the event stream ─────────────────────────────────────────────
for await (const event of stream) {
  if (event.type === "token") process.stdout.write(event.text);
  if (event.type === "done") {
    console.log("\n\nDone!");
    console.log(`Cost: $${event.costUsd.toFixed(6)} | Tokens: ${event.inputTokens + event.outputTokens}`);
  }
  if (event.type === "error") {
    console.error("Error:", event.message);
    process.exit(1);
  }
}
