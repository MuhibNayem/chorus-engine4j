/**
 * Example 3: Custom LLM Provider
 *
 * Implements LLMProvider for a hypothetical internal API.
 * Key points:
 *   - `name` can be any string (not limited to KnownProviderName)
 *   - `estimateCost?` overrides the built-in pricing table
 *   - Works with configureEngine() for fully headless operation
 *
 * Run: npx ts-node --esm examples/03-custom-provider.ts
 */

import {
  configureEngine,
  runAgentLoop,
  HitlGate,
  BtwQueue,
  JsonFileCheckpointer,
  type LLMProvider,
  type ProviderName,
  type GenerationRequest,
  type GenerationResult,
  type StreamEvent,
  type ToolStreamEvent,
  type ProviderHealth,
} from "../src/index.js";

// ─── Custom provider implementation ───────────────────────────────────────────

class MyInternalLLM implements LLMProvider {
  // Any string satisfies ProviderName — no casting needed
  readonly name: ProviderName = "my-internal-llm";

  private readonly baseUrl: string;
  private readonly apiKey: string;

  constructor(opts: { baseUrl: string; apiKey: string }) {
    this.baseUrl = opts.baseUrl;
    this.apiKey = opts.apiKey;
  }

  async generate(input: GenerationRequest): Promise<GenerationResult> {
    const res = await fetch(`${this.baseUrl}/v1/chat`, {
      method: "POST",
      headers: { Authorization: `Bearer ${this.apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model: input.model, messages: input.messages }),
    });
    const data = await res.json() as { content: string };
    return { text: data.content, model: input.model };
  }

  async *stream(_input: GenerationRequest): AsyncIterable<StreamEvent> {
    yield { type: "response.completed" };
  }

  async *streamWithTools(input: GenerationRequest & { tools: unknown[] }): AsyncIterable<ToolStreamEvent> {
    const result = await this.generate(input);
    yield { type: "token", text: result.text };
    yield { type: "done", response: { content: result.text } };
  }

  async health(): Promise<ProviderHealth> {
    try {
      await fetch(`${this.baseUrl}/health`);
      return { ok: true, provider: this.name };
    } catch {
      return { ok: false, provider: this.name, detail: "unreachable" };
    }
  }

  // Override the built-in pricing table for this provider
  estimateCost(inputTokens: number, outputTokens: number): number {
    // Internal pricing: $0.50 / 1M input, $2.00 / 1M output
    return (inputTokens * 0.50 + outputTokens * 2.00) / 1_000_000;
  }
}

// ─── Usage ────────────────────────────────────────────────────────────────────

configureEngine({ chorusHomeDir: "/tmp/my-app-chorus" });

const provider = new MyInternalLLM({
  baseUrl: process.env.INTERNAL_LLM_URL ?? "http://localhost:8080",
  apiKey: process.env.INTERNAL_LLM_KEY ?? "dev-key",
});

const health = await provider.health();
console.log("Provider health:", health);

const stream = runAgentLoop({
  provider,
  model: "internal-v2",
  tools: [],
  messages: [{ role: "user", content: "Hello from a custom provider!" }],
  systemPrompt: "You are a helpful assistant.",
  threadId: "example-03",
  hitlGate: new HitlGate(),
  btwQueue: new BtwQueue(),
  policy: "full_auto",
  checkpointer: new JsonFileCheckpointer(),
});

for await (const event of stream) {
  if (event.type === "token") process.stdout.write(event.text);
  if (event.type === "done") {
    console.log(`\nEstimated cost: $${event.costUsd.toFixed(8)}`);
  }
}
