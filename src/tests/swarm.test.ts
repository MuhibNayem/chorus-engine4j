import { describe, it, expect } from "vitest";
import { computeWaves, runSwarmGraph, JsonFileCheckpointer, HitlGate, BtwQueue } from "../index.js";
import type { SwarmAgent } from "../swarm/types.js";
import type { LLMProvider, ToolStreamEvent, ToolDef } from "../llm/provider.js";
import type { ProviderName } from "../llm/config.js";
import type { SwarmConfig } from "../swarm/types.js";

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

describe("computeWaves — DAG topology", () => {
  it("sorts a linear chain correctly", () => {
    const agents: SwarmAgent[] = [
      { name: "A", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5 },
      { name: "B", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["A"] },
      { name: "C", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["B"] },
    ];
    const waves = computeWaves(agents);
    expect(waves.length).toBe(3);
    expect(waves[0].map((a) => a.name)).toEqual(["A"]);
    expect(waves[1].map((a) => a.name)).toEqual(["B"]);
    expect(waves[2].map((a) => a.name)).toEqual(["C"]);
  });

  it("sorts a diamond DAG correctly", () => {
    const agents: SwarmAgent[] = [
      { name: "A", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5 },
      { name: "B", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["A"] },
      { name: "C", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["A"] },
      { name: "D", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["B", "C"] },
    ];
    const waves = computeWaves(agents);
    expect(waves.length).toBe(3);
    expect(waves[0].map((a) => a.name)).toEqual(["A"]);
    expect(waves[1].map((a) => a.name).sort()).toEqual(["B", "C"]);
    expect(waves[2].map((a) => a.name)).toEqual(["D"]);
  });

  it("detects circular dependencies", () => {
    const agents: SwarmAgent[] = [
      { name: "A", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["B"] },
      { name: "B", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["A"] },
    ];
    expect(() => computeWaves(agents)).toThrow(/Circular/i);
  });

  it("detects missing dependencies", () => {
    const agents: SwarmAgent[] = [
      { name: "A", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["NonExistent"] },
    ];
    expect(() => computeWaves(agents)).toThrow(/unknown/i);
  });

  it("handles agents with no dependencies in wave 0", () => {
    const agents: SwarmAgent[] = [
      { name: "A", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5 },
      { name: "B", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5 },
    ];
    const waves = computeWaves(agents);
    expect(waves.length).toBe(1);
    expect(waves[0].map((a) => a.name).sort()).toEqual(["A", "B"]);
  });
});

describe("runSwarmGraph — graph execution", () => {
  it("executes a simple two-agent graph", async () => {
    const provider = createMockProvider(async function* () {
      yield { type: "done", response: { content: "Result" } };
    });

    const events: Array<{ type: string }> = [];
    for await (const event of runSwarmGraph({
      provider,
      modelName: "mock",
      initialAgent: "A",
      agents: [
        { name: "A", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5 },
        { name: "B", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5, dependsOn: ["A"] },
      ],
      task: "Test",
      checkpointer: new JsonFileCheckpointer(),
    } as SwarmConfig)) {
      events.push(event);
    }

    expect(events.some((e) => e.type === "swarm-done")).toBe(true);
  });

  it("circuit-breaks on empty agents array", async () => {
    const provider = createMockProvider(async function* () {
      yield { type: "done", response: { content: "" } };
    });

    const events: Array<{ type: string }> = [];
    for await (const event of runSwarmGraph({
      provider, modelName: "mock", initialAgent: "", agents: [], task: "Test",
      checkpointer: new JsonFileCheckpointer(),
    } as SwarmConfig)) {
      events.push(event);
    }

    expect(events.some((e) => e.type === "circuit-break")).toBe(true);
  });

  it("emits swarm-start and swarm-done for a valid graph", async () => {
    const provider = createMockProvider(async function* () {
      yield { type: "done", response: { content: "Result" } };
    });

    const events: Array<{ type: string }> = [];
    for await (const event of runSwarmGraph({
      provider, modelName: "mock", initialAgent: "A",
      agents: [
        { name: "A", description: "", systemPrompt: "", tools: [], handoffDestinations: [], contextMode: "isolated", maxRounds: 5 },
      ],
      task: "Test",
      checkpointer: new JsonFileCheckpointer(),
    } as SwarmConfig)) {
      events.push(event);
    }

    expect(events.some((e) => e.type === "swarm-start")).toBe(true);
    expect(events.some((e) => e.type === "swarm-done")).toBe(true);
  });
});
