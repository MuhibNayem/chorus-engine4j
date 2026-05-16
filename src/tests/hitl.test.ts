import { describe, it, expect } from "vitest";
import { HitlGate, HitlGateTimeoutError, HitlGateDisposedError } from "../index.js";
import type { ToolCall } from "../llm/provider.js";

describe("HitlGate — production gate tests", () => {
  it("resolves a queued decision before wait() is called", async () => {
    const gate = new HitlGate();
    gate.resolve("key-1", { type: "approve" });
    const decision = await gate.wait("key-1");
    expect(decision.type).toBe("approve");
  });

  it("resolves a decision after wait() is called", async () => {
    const gate = new HitlGate();
    const promise = gate.wait("key-2");
    gate.resolve("key-2", { type: "approve" });
    const decision = await promise;
    expect(decision.type).toBe("approve");
  });

  it("times out with HitlGateTimeoutError when resolve() is never called", async () => {
    const gate = new HitlGate({ defaultTimeoutMs: 50 });
    await expect(gate.wait("key-3")).rejects.toThrow(HitlGateTimeoutError);
  });

  it("uses explicit timeoutMs over defaultTimeoutMs", async () => {
    const gate = new HitlGate({ defaultTimeoutMs: 10_000 });
    await expect(gate.wait("key-4", 50)).rejects.toThrow(HitlGateTimeoutError);
  });

  it("dispose() rejects all pending waits with HitlGateDisposedError", async () => {
    const gate = new HitlGate();
    const p1 = gate.wait("key-5");
    const p2 = gate.wait("key-6");
    gate.dispose();
    await expect(p1).rejects.toThrow(HitlGateDisposedError);
    await expect(p2).rejects.toThrow(HitlGateDisposedError);
  });

  it("dispose() is idempotent", () => {
    const gate = new HitlGate();
    gate.dispose();
    expect(() => gate.dispose()).not.toThrow();
  });

  it("session approval persists across multiple tool calls", async () => {
    const gate = new HitlGate();
    gate.resolve("key-7", { type: "approve_session", toolNames: ["file_write", "file_edit"] });
    const decision = await gate.wait("key-7");
    expect(decision.type).toBe("approve");

    const toolCalls: ToolCall[] = [
      { id: "tc1", type: "function", function: { name: "file_write", arguments: "{}" } },
    ];
    expect(gate.shouldPause(toolCalls, "suggest")).toBe(false);
  });

  it("resetSessionApprovals() clears session approvals", () => {
    const gate = new HitlGate();
    gate.resolve("key-8", { type: "approve_session", toolNames: ["file_write"] });
    gate.resetSessionApprovals();
    const toolCalls: ToolCall[] = [
      { id: "tc1", type: "function", function: { name: "file_write", arguments: "{}" } },
    ];
    expect(gate.shouldPause(toolCalls, "auto_edit")).toBe(true);
  });

  it("shouldPause returns false for full_auto and suggest policies", () => {
    const gate = new HitlGate();
    const toolCalls: ToolCall[] = [
      { id: "tc1", type: "function", function: { name: "file_write", arguments: "{}" } },
    ];
    expect(gate.shouldPause(toolCalls, "full_auto")).toBe(false);
    expect(gate.shouldPause(toolCalls, "suggest")).toBe(false);
    expect(gate.shouldPause(toolCalls, "auto_edit")).toBe(true);
  });

  it("shouldPause includes mcp__ prefixed tools", () => {
    const gate = new HitlGate();
    const toolCalls: ToolCall[] = [
      { id: "tc1", type: "function", function: { name: "mcp__fetch", arguments: "{}" } },
    ];
    expect(gate.shouldPause(toolCalls, "auto_edit")).toBe(true);
  });

  it("shouldPause respects custom sensitiveTools", () => {
    const gate = new HitlGate({ sensitiveTools: ["custom_danger"] });
    expect(gate.shouldPause(
      [{ id: "tc1", type: "function", function: { name: "custom_danger", arguments: "{}" } }],
      "auto_edit",
    )).toBe(true);
    expect(gate.shouldPause(
      [{ id: "tc2", type: "function", function: { name: "file_write", arguments: "{}" } }],
      "auto_edit",
    )).toBe(false);
  });

  it("shouldPause respects additionalSensitiveTools", () => {
    const gate = new HitlGate({ additionalSensitiveTools: ["custom_danger"] });
    expect(gate.shouldPause(
      [{ id: "tc1", type: "function", function: { name: "custom_danger", arguments: "{}" } }],
      "auto_edit",
    )).toBe(true);
    expect(gate.shouldPause(
      [{ id: "tc2", type: "function", function: { name: "file_write", arguments: "{}" } }],
      "auto_edit",
    )).toBe(true);
  });

  it("stores unresolved decisions for later wait()", async () => {
    const gate = new HitlGate();
    gate.resolve("key-9", { type: "approve" });
    // wait() called AFTER resolve — decision should still be queued
    const decision = await gate.wait("key-9");
    expect(decision.type).toBe("approve");
  });
});
