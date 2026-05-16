/**
 * Example 4: Human-in-the-Loop (HITL) Approval
 *
 * Demonstrates how to pause the agent before sensitive tool calls and
 * programmatically approve, reject, or approve-for-the-session.
 *
 * Use cases:
 *   - Production systems where file writes need audit
 *   - Custom tool names that should always require human sign-off
 *
 * Run: npx ts-node --esm examples/04-hitl-approval.ts
 */

import {
  configureEngine,
  runAgentLoop,
  HitlGate,
  BtwQueue,
  JsonFileCheckpointer,
  createFilesystemTools,
  type AgentEvent,
} from "../src/index.js";
import { createProvider } from "../src/llm/registry.js";
import * as readline from "readline/promises";

configureEngine({
  llm: {
    provider: "openai",
    providers: { openai: { apiKey: process.env.OPENAI_API_KEY!, model: "gpt-4o-mini" } },
  },
  chorusHomeDir: "/tmp/hitl-example",
});

const provider = createProvider("openai");
const rl = readline.createInterface({ input: process.stdin, output: process.stdout });

// Extend the default sensitive set with a custom tool name
const hitlGate = new HitlGate({
  additionalSensitiveTools: ["deploy_to_production"],
});

const fsTools = createFilesystemTools("/tmp/hitl-workspace");

const stream = runAgentLoop({
  provider,
  model: "gpt-4o-mini",
  tools: fsTools,
  messages: [{ role: "user", content: "Create a file called hello.txt with the content 'Hello World'." }],
  systemPrompt: "You are a helpful assistant. You have access to filesystem tools.",
  threadId: "example-04-hitl",
  hitlGate,
  btwQueue: new BtwQueue(),
  policy: "auto_edit", // triggers HITL for sensitive tools
  checkpointer: new JsonFileCheckpointer(),
});

for await (const event of stream) {
  if (event.type === "token") {
    process.stdout.write(event.text);
    continue;
  }

  if (event.type === "hitl") {
    console.log("\n\n--- HITL APPROVAL REQUIRED ---");
    for (const req of event.requests) {
      console.log(`Tool: ${req.name}`);
      console.log(`Args: ${JSON.stringify(req.args, null, 2)}`);
    }

    const answer = await rl.question("Approve? [y/N/session] > ");
    if (answer.toLowerCase() === "y") {
      hitlGate.resolve(event.resumeKey, { type: "approve" });
    } else if (answer.toLowerCase() === "session") {
      hitlGate.resolve(event.resumeKey, {
        type: "approve_session",
        toolNames: event.requests.map((r) => r.name),
      });
    } else {
      hitlGate.resolve(event.resumeKey, {
        type: "reject",
        message: "User rejected this tool call.",
      });
    }
    continue;
  }

  if (event.type === "tool-done") {
    console.log(`\n[tool:${event.name}] ${event.result.slice(0, 120)}`);
  }

  if (event.type === "done") {
    console.log("\n\nAgent finished.");
    rl.close();
  }
}
