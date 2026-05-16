import { describe, it, expect, beforeEach } from "vitest";
import { z } from "zod";
import { runAgentLoop, HitlGate, BtwQueue, JsonFileCheckpointer, createDefaultMiddleware } from "../index.js";
import type { LLMProvider, ToolStreamEvent, ToolDef } from "../llm/provider.js";
import type { ProviderName } from "../llm/config.js";

function createMockProvider(
  streamFactory: (input: { model: string; messages: any[]; systemPrompt?: string; tools: ToolDef[] }) => AsyncGenerator<ToolStreamEvent>,
): LLMProvider {
  return {
    name: "mock" as ProviderName,
    async generate() { return { text: "mock", model: "mock" }; },
    async *stream() { yield { type: "response.completed" }; },
    async *streamWithTools(input) { yield* streamFactory(input); },
    async health() { return { ok: true, provider: "mock" }; },
    estimateCost() { return 0; },
  };
}

describe("runAgentLoop — edge cases & failure injection", () => {
  it("aborts between rounds when abortSignal is already fired", async () => {
    const controller = new AbortController();
    controller.abort(); // abort before loop starts

    const provider = createMockProvider(async function* () {
      yield { type: "done", response: { content: "Done" } };
    });

    const events: Array<{ type: string }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock", tools: [], messages: [{ role: "user", content: "Hi" }],
      systemPrompt: "", threadId: "test-abort", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(), abortSignal: controller.signal,
    })) {
      events.push(event);
    }

    expect(events.some((e) => e.type === "aborted")).toBe(true);
  });

  it("resumes from a HITL checkpoint with resumedDecision", async () => {
    const cp = new JsonFileCheckpointer();
    const threadId = `resume-${Date.now()}`;
    await cp.save(threadId, {
      messages: [
        { role: "user", content: "Do it" },
        { role: "assistant", content: "", tool_calls: [{ id: "tc1", type: "function", function: { name: "run_command", arguments: "{}" } }] },
      ],
      round: 0,
      waitingForHitl: {
        resumeKey: "hitl-resume-0",
        requests: [{ id: "tc1", name: "run_command", args: {} }],
        toolCalls: [{ id: "tc1", type: "function", function: { name: "run_command", arguments: "{}" } }],
        assistant: { content: "" },
      },
    });

    let callCount = 0;
    const provider = createMockProvider(async function* () {
      callCount++;
      yield { type: "done", response: { content: "Resumed" } };
    });

    const events: Array<{ type: string }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock",
      tools: [{ name: "run_command", description: "run", schema: undefined, invoke: async () => "ok" }],
      messages: [{ role: "user", content: "Do it" }],
      systemPrompt: "", threadId, hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: cp, resumedDecision: { type: "approve" },
    })) {
      events.push(event);
      if (event.type === "done") break;
    }

    expect(events.some((e) => e.type === "done")).toBe(true);
    await cp.delete(threadId);
  });

  it("handles tool that throws an exception", async () => {
    let callCount = 0;
    const provider = createMockProvider(async function* () {
      callCount++;
      if (callCount === 1) {
        yield { type: "done", response: { content: "", tool_calls: [{ id: "tc1", type: "function", function: { name: "boom", arguments: "{}" } }] } };
      } else {
        yield { type: "done", response: { content: "Recovered" } };
      }
    });

    const events: Array<{ type: string; error?: string }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock",
      tools: [{ name: "boom", description: "boom", schema: undefined, invoke: async () => { throw new Error("Kaboom"); } }],
      messages: [{ role: "user", content: "Boom" }],
      systemPrompt: "", threadId: "test-boom", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(),
    })) {
      events.push(event);
      if (event.type === "done") break;
    }

    const errorEvent = events.find((e) => e.type === "tool-error");
    expect(errorEvent).toBeDefined();
    expect(errorEvent!.error).toContain("Kaboom");
    expect(events.some((e) => e.type === "done")).toBe(true);
  });

  it("handles invalid JSON tool arguments gracefully", async () => {
    let callCount = 0;
    const provider = createMockProvider(async function* () {
      callCount++;
      if (callCount === 1) {
        yield { type: "done", response: { content: "", tool_calls: [{ id: "tc1", type: "function", function: { name: "noop", arguments: "not-json" } }] } };
      } else {
        yield { type: "done", response: { content: "Done" } };
      }
    });

    const events: Array<{ type: string; error?: string }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock",
      tools: [{ name: "noop", description: "noop", schema: undefined, invoke: async () => "ok" }],
      messages: [{ role: "user", content: "Bad args" }],
      systemPrompt: "", threadId: "test-badargs", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(),
    })) {
      events.push(event);
      if (event.type === "done") break;
    }

    expect(events.some((e) => e.type === "tool-error" && e.error?.includes("Invalid JSON"))).toBe(true);
  });

  it("handles unknown tool name gracefully", async () => {
    let callCount = 0;
    const provider = createMockProvider(async function* () {
      callCount++;
      if (callCount === 1) {
        yield { type: "done", response: { content: "", tool_calls: [{ id: "tc1", type: "function", function: { name: "nonexistent", arguments: "{}" } }] } };
      } else {
        yield { type: "done", response: { content: "Done" } };
      }
    });

    const events: Array<{ type: string; error?: string }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock", tools: [],
      messages: [{ role: "user", content: "Unknown tool" }],
      systemPrompt: "", threadId: "test-unknown", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(),
    })) {
      events.push(event);
      if (event.type === "done") break;
    }

    expect(events.some((e) => e.type === "tool-error" && e.error?.includes("Unknown tool"))).toBe(true);
  });

  it("yields checkpoint after every round", async () => {
    let callCount = 0;
    const provider = createMockProvider(async function* () {
      callCount++;
      if (callCount <= 2) {
        yield { type: "done", response: { content: `Round ${callCount}` } };
      } else {
        yield { type: "done", response: { content: "Final" } };
      }
    });

    const events: Array<{ type: string }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock", tools: [],
      messages: [{ role: "user", content: "Multi" }],
      systemPrompt: "", threadId: "test-cp", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(), maxRounds: 5,
    })) {
      events.push(event);
      if (event.type === "done") break;
    }

    const checkpoints = events.filter((e) => e.type === "checkpoint");
    expect(checkpoints.length).toBeGreaterThanOrEqual(1);
  });

  it("accumulates token counts across rounds", async () => {
    let callCount = 0;
    const provider = createMockProvider(async function* () {
      callCount++;
      yield { type: "done", response: { content: "Done", usage: { inputTokens: 10, outputTokens: 5 } } };
    });

    const events: Array<{ type: string; inputTokens?: number; outputTokens?: number }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock", tools: [],
      messages: [{ role: "user", content: "Count" }],
      systemPrompt: "", threadId: "test-tokens", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(),
    })) {
      events.push(event);
    }

    const done = events.find((e): e is typeof e & { type: "done"; inputTokens: number; outputTokens: number } => e.type === "done");
    expect(done).toBeDefined();
    expect(done!.inputTokens).toBe(10);
    expect(done!.outputTokens).toBe(5);
  });

  it("handles provider that never yields done (empty stream)", async () => {
    const provider = createMockProvider(async function* () {
      // Empty stream — no events at all
    });

    const events: Array<{ type: string; fatal?: boolean }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock", tools: [],
      messages: [{ role: "user", content: "Empty" }],
      systemPrompt: "", threadId: "test-empty", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(),
    })) {
      events.push(event);
    }

    expect(events.some((e) => e.type === "error" && e.fatal)).toBe(true);
  });

  it("handles middleware extraTools injection", async () => {
    let injectedToolCalled = false;
    const provider = createMockProvider(async function* () {
      yield { type: "done", response: { content: "Done", tool_calls: [{ id: "tc1", type: "function", function: { name: "injected", arguments: "{}" } }] } };
    });

    const customMw = {
      extraTools: () => [{
        name: "injected",
        description: "injected",
        schema: undefined,
        invoke: async () => { injectedToolCalled = true; return "injected-result"; },
      }],
    };

    for await (const event of runAgentLoop({
      provider, model: "mock", tools: [],
      messages: [{ role: "user", content: "Inject" }],
      systemPrompt: "", threadId: "test-inject", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(), middleware: [customMw as any],
    })) {
      if (event.type === "done") break;
    }

    expect(injectedToolCalled).toBe(true);
  });

  it("rejects HITL decision stops tool execution and yields done", async () => {
    const gate = new HitlGate();
    let callCount = 0;
    const provider = createMockProvider(async function* () {
      callCount++;
      if (callCount === 1) {
        yield { type: "done", response: { content: "", tool_calls: [{ id: "tc1", type: "function", function: { name: "run_command", arguments: "{}" } }] } };
      } else {
        yield { type: "done", response: { content: "After reject" } };
      }
    });

    const events: Array<{ type: string }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock",
      tools: [{ name: "run_command", description: "run", schema: undefined, invoke: async () => "ok" }],
      messages: [{ role: "user", content: "Reject" }],
      systemPrompt: "", threadId: "test-reject", hitlGate: gate,
      btwQueue: new BtwQueue(), policy: "auto_edit",
      checkpointer: new JsonFileCheckpointer(),
    })) {
      events.push(event);
      if (event.type === "hitl") {
        gate.resolve((event as { resumeKey: string }).resumeKey, { type: "reject", message: "No way" });
      }
      if (event.type === "done") break;
    }

    expect(events.some((e) => e.type === "done")).toBe(true);
    expect(events.some((e) => e.type === "tool-start")).toBe(false);
  });

  it("streams timeout aborts hung provider", async () => {
    const provider = createMockProvider(async function* () {
      await new Promise((r) => setTimeout(r, 500)); // simulate hung stream
      yield { type: "done", response: { content: "Too late" } };
    });

    const events: Array<{ type: string; fatal?: boolean }> = [];
    for await (const event of runAgentLoop({
      provider, model: "mock", tools: [],
      messages: [{ role: "user", content: "Hang" }],
      systemPrompt: "", threadId: "test-timeout", hitlGate: new HitlGate(),
      btwQueue: new BtwQueue(), policy: "full_auto",
      checkpointer: new JsonFileCheckpointer(), streamTimeoutMs: 50,
    })) {
      events.push(event);
    }

    expect(events.some((e) => e.type === "error" && e.fatal)).toBe(true);
  });
});
