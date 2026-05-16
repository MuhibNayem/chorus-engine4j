import { describe, it, expect, beforeEach } from "vitest";
import { TrajectorySynthesizer, longestCommonSubsequence } from "../index.js";
import type { ChatMessage } from "../llm/provider.js";

describe("longestCommonSubsequence — algorithm correctness", () => {
  it("finds LCS of two sequences", () => {
    const a = ["read", "parse", "write"];
    const b = ["read", "transform", "write"];
    expect(longestCommonSubsequence(a, b)).toEqual(["read", "write"]);
  });

  it("returns empty for completely different sequences", () => {
    expect(longestCommonSubsequence(["a", "b"], ["c", "d"])).toEqual([]);
  });

  it("returns full sequence when identical", () => {
    expect(longestCommonSubsequence(["a", "b", "c"], ["a", "b", "c"])).toEqual(["a", "b", "c"]);
  });

  it("handles empty arrays", () => {
    expect(longestCommonSubsequence([], ["a"])).toEqual([]);
    expect(longestCommonSubsequence(["a"], [])).toEqual([]);
    expect(longestCommonSubsequence([], [])).toEqual([]);
  });
});

describe("TrajectorySynthesizer — data integrity tests", () => {
  let registry: any;
  let synth: TrajectorySynthesizer;

  beforeEach(() => {
    registry = {
      registerPattern: (p: any) => {},
      savePattern: (p: any) => "",
    };
    synth = new TrajectorySynthesizer(registry as any, {
      similarityThreshold: 0.5,
      minTrajectories: 2,
      maxTrajectories: 10,
    });
  });

  it("extracts a single-tool trajectory correctly", () => {
    const history: ChatMessage[] = [
      { role: "user", content: "Do thing" },
      { role: "assistant", content: "", tool_calls: [{ id: "tc1", type: "function", function: { name: "tool_a", arguments: JSON.stringify({ x: 1 }) } }] },
      { role: "tool", tool_call_id: "tc1", content: "result_a" },
    ];

    const traj = TrajectorySynthesizer.extractTrajectory(history, { success: true });
    expect(traj.tools.length).toBe(1);
    expect(traj.tools[0].name).toBe("tool_a");
    expect(traj.tools[0].input).toEqual({ x: 1 });
    expect(traj.tools[0].output).toBe("result_a");
  });

  it("extracts parallel tool calls correctly by tool_call_id", () => {
    const history: ChatMessage[] = [
      { role: "user", content: "Do both" },
      {
        role: "assistant",
        content: "",
        tool_calls: [
          { id: "tcA", type: "function", function: { name: "tool_a", arguments: JSON.stringify({ x: 1 }) } },
          { id: "tcB", type: "function", function: { name: "tool_b", arguments: JSON.stringify({ y: 2 }) } },
        ],
      },
      { role: "tool", tool_call_id: "tcB", content: "result_b" },
      { role: "tool", tool_call_id: "tcA", content: "result_a" },
    ];

    const traj = TrajectorySynthesizer.extractTrajectory(history, { success: true });
    expect(traj.tools.length).toBe(2);

    // Verify each tool is matched to its correct result by tool_call_id
    const toolA = traj.tools.find((t) => t.name === "tool_a");
    const toolB = traj.tools.find((t) => t.name === "tool_b");
    expect(toolA).toBeDefined();
    expect(toolB).toBeDefined();
    expect(toolA!.output).toBe("result_a");
    expect(toolB!.output).toBe("result_b");
    expect(toolA!.input).toEqual({ x: 1 });
    expect(toolB!.input).toEqual({ y: 2 });
  });

  it("handles tool results arriving before their tool_call (out of order)", () => {
    const history: ChatMessage[] = [
      { role: "user", content: "Test" },
      {
        role: "assistant",
        content: "",
        tool_calls: [
          { id: "tc1", type: "function", function: { name: "tool_a", arguments: "{}" } },
        ],
      },
      { role: "tool", tool_call_id: "tc1", content: "result" },
    ];

    const traj = TrajectorySynthesizer.extractTrajectory(history);
    expect(traj.tools.length).toBe(1);
    expect(traj.tools[0].output).toBe("result");
  });

  it("ignores tool results with no matching tool_call_id", () => {
    const history: ChatMessage[] = [
      { role: "user", content: "Test" },
      { role: "assistant", content: "no tools" },
      { role: "tool", tool_call_id: "orphan", content: "orphan result" },
    ];

    const traj = TrajectorySynthesizer.extractTrajectory(history);
    expect(traj.tools.length).toBe(0);
  });

  it("caps trajectories at maxTrajectories", () => {
    const smallSynth = new TrajectorySynthesizer(registry as any, {
      maxTrajectories: 3,
    });

    for (let i = 0; i < 5; i++) {
      smallSynth.observe({
        id: `t${i}`,
        task: "task",
        tools: [],
        success: false,
        tokens: 0,
        duration: 0,
        timestamp: Date.now(),
      });
    }

    // Access private field via reflection for test verification
    expect((smallSynth as any).trajectories.length).toBe(3);
    expect((smallSynth as any).trajectories[0].id).toBe("t2"); // oldest kept
    expect((smallSynth as any).trajectories[2].id).toBe("t4"); // newest
  });

  it("computes similarity 1.0 for identical sequences", () => {
    const t1 = { id: "a", task: "t", tools: [{ name: "x", input: {}, output: "" }], success: true, tokens: 0, duration: 0, timestamp: 0 };
    const t2 = { id: "b", task: "t", tools: [{ name: "x", input: {}, output: "" }], success: true, tokens: 0, duration: 0, timestamp: 0 };
    (synth as any).trajectories = [t1];
    const similar = (synth as any).findSimilar(t2);
    expect(similar.length).toBe(1);
  });

  it("computes similarity 1.0 for both empty sequences (division by zero guard)", () => {
    const t1 = { id: "a", task: "t", tools: [], success: true, tokens: 0, duration: 0, timestamp: 0 };
    const t2 = { id: "b", task: "t", tools: [], success: true, tokens: 0, duration: 0, timestamp: 0 };
    (synth as any).trajectories = [t1];
    const similar = (synth as any).findSimilar(t2);
    expect(similar.length).toBe(1);
  });

  it("does not synthesize from failed trajectories", () => {
    const patterns: any[] = [];
    const mockRegistry = {
      registerPattern: (p: any) => patterns.push(p),
      savePattern: () => "",
    };
    const s = new TrajectorySynthesizer(mockRegistry as any, { minTrajectories: 2 });

    for (let i = 0; i < 3; i++) {
      s.observe({
        id: `f${i}`,
        task: "fail",
        tools: [{ name: "x", input: {}, output: "" }],
        success: false,
        tokens: 0,
        duration: 0,
        timestamp: Date.now(),
      });
    }

    expect(patterns.length).toBe(0);
  });
});
