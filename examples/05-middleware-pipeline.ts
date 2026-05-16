/**
 * Example 5: Custom Middleware Pipeline
 *
 * Shows how to build a middleware stack that:
 *   1. Audits every tool call before execution (beforeTool hook)
 *   2. Compacts context at 70% instead of the default 85%
 *   3. Offloads large tool outputs at 64 KB instead of 8 KB
 *   4. Injects a custom system prompt fragment
 *
 * Run: npx ts-node --esm examples/05-middleware-pipeline.ts
 */

import {
  configureEngine,
  runAgentLoop,
  HitlGate,
  BtwQueue,
  JsonFileCheckpointer,
  SummarizationMiddleware,
  LargeOutputOffloadMiddleware,
  createFilesystemTools,
  type AgentMiddleware,
  type BeforeToolContext,
  type RoundContext,
} from "../src/index.js";
import { createProvider } from "../src/llm/registry.js";
import * as fs from "fs";

configureEngine({
  llm: {
    provider: "openai",
    providers: { openai: { apiKey: process.env.OPENAI_API_KEY!, model: "gpt-4o-mini" } },
  },
  chorusHomeDir: "/tmp/middleware-example",
});

// ─── Custom audit middleware ───────────────────────────────────────────────────

class AuditMiddleware implements AgentMiddleware {
  private readonly log: string[] = [];

  async beforeTool(ctx: BeforeToolContext): Promise<void | { cancel: true; result: string }> {
    const entry = `[${new Date().toISOString()}] TOOL_CALL ${ctx.name} args=${JSON.stringify(ctx.args)}`;
    this.log.push(entry);
    console.log(entry);

    // Example policy: block any tool trying to write outside /tmp
    if (ctx.name === "file_write") {
      const target = String(ctx.args.path ?? "");
      if (!target.startsWith("/tmp")) {
        return {
          cancel: true,
          result: `[BLOCKED] file_write outside /tmp is not permitted. Path: ${target}`,
        };
      }
    }
  }

  async afterRound(ctx: RoundContext): Promise<void> {
    const summary = `Round ${ctx.round}: ${ctx.toolCallsThisRound} tool calls`;
    this.log.push(summary);
  }

  flushAuditLog(path: string): void {
    fs.writeFileSync(path, this.log.join("\n"), "utf-8");
    console.log(`Audit log written to ${path}`);
  }
}

// ─── Build the pipeline ───────────────────────────────────────────────────────

const threadId = "example-05-middleware";
const auditMiddleware = new AuditMiddleware();

const middleware: AgentMiddleware[] = [
  auditMiddleware,
  new SummarizationMiddleware(threadId, { threshold: 0.70 }),  // compact earlier
  new LargeOutputOffloadMiddleware({ thresholdBytes: 64 * 1024 }), // 64 KB threshold
];

const provider = createProvider("openai");
const fsTools = createFilesystemTools("/tmp/middleware-workspace");

const stream = runAgentLoop({
  provider,
  model: "gpt-4o-mini",
  tools: fsTools,
  messages: [{ role: "user", content: "List the contents of /tmp and write a summary to /tmp/summary.txt." }],
  systemPrompt: "You are a filesystem assistant.",
  threadId,
  hitlGate: new HitlGate(),
  btwQueue: new BtwQueue(),
  policy: "full_auto",
  checkpointer: new JsonFileCheckpointer(),
  middleware,
});

for await (const event of stream) {
  if (event.type === "token") process.stdout.write(event.text);
  if (event.type === "tool-start") console.log(`\n→ ${event.name}(${JSON.stringify(event.args).slice(0, 80)})`);
  if (event.type === "tool-done") console.log(`← ${event.name}: ${event.result.slice(0, 80)}`);
  if (event.type === "done") {
    console.log("\n\nDone!");
    auditMiddleware.flushAuditLog("/tmp/middleware-example/audit.log");
  }
}
