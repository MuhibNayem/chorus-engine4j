import { describe, it, expect } from "vitest";
import { createFilesystemTools, shellTools, assessCommandSafety, auditCommand } from "../index.js";

describe("createFilesystemTools — sandboxing", () => {
  it("contains expected tool names", () => {
    const tools = createFilesystemTools("/tmp/workspace");
    const names = tools.map((t) => t.name);
    expect(names).toContain("file_read");
    expect(names).toContain("file_write");
    expect(names).toContain("file_edit");
    expect(names).toContain("list_dir");
    expect(names).toContain("find_files");
    expect(names).toContain("search_files");
  });

  it("rejects paths outside the workspace", async () => {
    const tools = createFilesystemTools("/tmp/workspace");
    const writeTool = tools.find((t) => t.name === "file_write")!;
    const result = await writeTool.invoke({ path: "../../../etc/passwd", content: "hack" });
    expect(typeof result === "string" ? result : "").toMatch(/outside|sandbox/i);
  });

  it("allows paths inside the workspace", async () => {
    const tools = createFilesystemTools("/tmp/workspace");
    const listTool = tools.find((t) => t.name === "list_dir")!;
    const result = await listTool.invoke({ path: "subdir" });
    expect(result).toBeDefined();
  });
});

describe("assessCommandSafety — command filtering", () => {
  it("blocks rm with destructive flags", () => {
    expect(assessCommandSafety("rm", ["-rf", "/"]).ok).toBe(false);
    expect(assessCommandSafety("rm", ["-rf", "node_modules"]).ok).toBe(false);
  });

  it("allows rm without destructive flags", () => {
    expect(assessCommandSafety("rm", ["file.txt"]).ok).toBe(true);
  });

  it("blocks destructive git commands", () => {
    expect(assessCommandSafety("git", ["clean", "-fdx"]).ok).toBe(false);
    expect(assessCommandSafety("git", ["reset", "--hard"]).ok).toBe(false);
  });

  it("allows safe git commands", () => {
    expect(assessCommandSafety("git", ["status"]).ok).toBe(true);
    expect(assessCommandSafety("git", ["log"]).ok).toBe(true);
  });

  it("allows general safe commands", () => {
    expect(assessCommandSafety("ls", ["-la"]).ok).toBe(true);
    expect(assessCommandSafety("cat", ["file.txt"]).ok).toBe(true);
    expect(assessCommandSafety("grep", ["pattern", "file.txt"]).ok).toBe(true);
    expect(assessCommandSafety("npm", ["test"]).ok).toBe(true);
  });
});

describe("auditCommand — side-effect logging", () => {
  it("does not throw for any input", () => {
    expect(() => auditCommand({ command: "rm -rf node_modules", allowed: false, reason: "Dangerous" })).not.toThrow();
    expect(() => auditCommand({ command: "npm test", allowed: true })).not.toThrow();
  });
});

describe("shellTools — smoke", () => {
  it("exports run_command tool", () => {
    expect(shellTools.map((t) => t.name)).toContain("run_command");
  });
});
