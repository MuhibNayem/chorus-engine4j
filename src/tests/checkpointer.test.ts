import { describe, it, expect, beforeEach } from "vitest";
import { JsonFileCheckpointer } from "../index.js";
import * as fs from "fs";
import * as path from "path";
import { getSettingsPath } from "../settings/storage.js";

function getCheckpointDir(threadId: string): string {
  return path.join(path.dirname(getSettingsPath()), "checkpoints", threadId);
}

describe("JsonFileCheckpointer — production persistence tests", () => {
  let cp: JsonFileCheckpointer;

  beforeEach(() => {
    cp = new JsonFileCheckpointer();
  });

  it("saves and loads the latest checkpoint", async () => {
    const threadId = `cp-test-${Date.now()}`;
    await cp.save(threadId, {
      messages: [{ role: "user", content: "hello" }],
      round: 0,
    });
    const loaded = await cp.load(threadId);
    expect(loaded).not.toBeNull();
    expect(loaded!.round).toBe(0);
    expect(loaded!.messages[0].content).toBe("hello");
    await cp.delete(threadId);
  });

  it("returns the most recent checkpoint when multiple rounds exist", async () => {
    const threadId = `cp-latest-${Date.now()}`;
    await cp.save(threadId, { messages: [{ role: "user", content: "r0" }], round: 0 });
    await cp.save(threadId, { messages: [{ role: "user", content: "r1" }], round: 1 });
    await cp.save(threadId, { messages: [{ role: "user", content: "r2" }], round: 2 });

    const loaded = await cp.load(threadId);
    expect(loaded!.round).toBe(2);
    expect(loaded!.messages[0].content).toBe("r2");
    await cp.delete(threadId);
  });

  it("loads a specific round with loadAt", async () => {
    const threadId = `cp-at-${Date.now()}`;
    await cp.save(threadId, { messages: [{ role: "user", content: "r0" }], round: 0 });
    await cp.save(threadId, { messages: [{ role: "user", content: "r1" }], round: 1 });

    const at0 = await cp.loadAt(threadId, 0);
    expect(at0!.round).toBe(0);
    const at1 = await cp.loadAt(threadId, 1);
    expect(at1!.round).toBe(1);
    const atMissing = await cp.loadAt(threadId, 999);
    expect(atMissing).toBeNull();
    await cp.delete(threadId);
  });

  it("lists all checkpoints in order", async () => {
    const threadId = `cp-list-${Date.now()}`;
    await cp.save(threadId, { messages: [{ role: "user", content: "a" }], round: 0 });
    await cp.save(threadId, { messages: [{ role: "user", content: "b" }], round: 1 });

    const list = await cp.list(threadId);
    expect(list.length).toBe(2);
    expect(list[0].round).toBe(0);
    expect(list[1].round).toBe(1);
    await cp.delete(threadId);
  });

  it("forks a checkpoint to a new thread", async () => {
    const threadId = `cp-fork-src-${Date.now()}`;
    const newThreadId = `cp-fork-dst-${Date.now()}`;
    await cp.save(threadId, { messages: [{ role: "user", content: "fork-me" }], round: 3 });

    await cp.fork(threadId, 3, newThreadId);
    const forked = await cp.load(newThreadId);
    expect(forked).not.toBeNull();
    expect(forked!.round).toBe(3);
    expect(forked!.messages[0].content).toBe("fork-me");

    await cp.delete(threadId);
    await cp.delete(newThreadId);
  });

  it("delete removes all checkpoints for a thread", async () => {
    const threadId = `cp-del-${Date.now()}`;
    await cp.save(threadId, { messages: [{ role: "user", content: "x" }], round: 0 });
    await cp.delete(threadId);
    const loaded = await cp.load(threadId);
    expect(loaded).toBeNull();
    expect(fs.existsSync(getCheckpointDir(threadId))).toBe(false);
  });

  it("load returns null for non-existent thread", async () => {
    const loaded = await cp.load(`cp-nonexistent-${Date.now()}`);
    expect(loaded).toBeNull();
  });

  it("persists waitingForHitl state", async () => {
    const threadId = `cp-hitl-${Date.now()}`;
    await cp.save(threadId, {
      messages: [{ role: "user", content: "hi" }],
      round: 1,
      waitingForHitl: {
        resumeKey: "r1",
        requests: [{ id: "tc1", name: "run_command", args: {} }],
        toolCalls: [{ id: "tc1", type: "function", function: { name: "run_command", arguments: "{}" } }],
        assistant: { content: "" },
      },
    });

    const loaded = await cp.load(threadId);
    expect(loaded!.waitingForHitl).toBeDefined();
    expect(loaded!.waitingForHitl!.resumeKey).toBe("r1");
    await cp.delete(threadId);
  });
});
