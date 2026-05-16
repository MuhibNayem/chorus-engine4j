import type { ApprovalPolicy } from "../harness/types.js";
import type { ToolCall } from "../llm/provider.js";
import type { HitlDecision } from "./types.js";

const DEFAULT_HITL_TOOL_NAMES = new Set([
  "file_write",
  "file_edit",
  "run_command",
  "git_commit",
  "delegate_to_subagent",
]);

export interface HitlGateOptions {
  /**
   * Fully replace the default sensitive tool set. Only these names will trigger HITL.
   * MCP tools (mcp__*) are always included regardless of this setting.
   */
  sensitiveTools?: string[];
  /**
   * Extend the default sensitive tool set with additional tool names.
   * The defaults (file_write, file_edit, run_command, git_commit, delegate_to_subagent) are kept.
   */
  additionalSensitiveTools?: string[];
  /**
   * Default timeout for `wait()` calls when no explicit timeoutMs is provided.
   * Prevents resolver leaks if resolve() is never called. Default: 300_000 (5 min).
   */
  defaultTimeoutMs?: number;
}

export class HitlGateTimeoutError extends Error {
  constructor(resumeKey: string) {
    super(`HitlGate timed out waiting for approval of "${resumeKey}".`);
    this.name = "HitlGateTimeoutError";
  }
}

export class HitlGateDisposedError extends Error {
  constructor() {
    super("HitlGate was disposed before a decision was made.");
    this.name = "HitlGateDisposedError";
  }
}

export class HitlGate {
  private readonly gates = new Map<string, { resolve: (d: HitlDecision) => void; reject: (e: Error) => void; timer?: ReturnType<typeof setTimeout> }>();
  private readonly sessionApproved = new Set<string>();
  private readonly pending = new Map<string, HitlDecision>();
  private readonly sensitiveTools: Set<string>;
  private readonly defaultTimeoutMs: number;
  private disposed = false;

  constructor(opts?: HitlGateOptions) {
    if (opts?.sensitiveTools) {
      this.sensitiveTools = new Set(opts.sensitiveTools);
    } else {
      this.sensitiveTools = new Set(DEFAULT_HITL_TOOL_NAMES);
      for (const t of opts?.additionalSensitiveTools ?? []) {
        this.sensitiveTools.add(t);
      }
    }
    this.defaultTimeoutMs = opts?.defaultTimeoutMs ?? 300_000;
  }

  shouldPause(
    toolCalls: ToolCall[],
    policy: ApprovalPolicy,
  ): boolean {
    if (policy === "full_auto" || policy === "suggest") return false;
    return toolCalls.some((toolCall) => {
      const name = toolCall.function.name ?? "";
      return (this.sensitiveTools.has(name) || name.startsWith("mcp__")) && !this.sessionApproved.has(name);
    });
  }

  /**
   * Wait for a HITL decision.
   * @param resumeKey  Unique key for this approval request.
   * @param timeoutMs  If set, rejects with HitlGateTimeoutError after this many ms.
   */
  wait(resumeKey: string, timeoutMs?: number): Promise<HitlDecision> {
    const queued = this.pending.get(resumeKey);
    if (queued) {
      this.pending.delete(resumeKey);
      return Promise.resolve(queued);
    }
    return new Promise((resolve, reject) => {
      const entry: { resolve: (d: HitlDecision) => void; reject: (e: Error) => void; timer?: ReturnType<typeof setTimeout> } = { resolve, reject };
      const effectiveTimeout = timeoutMs ?? this.defaultTimeoutMs;
      if (effectiveTimeout > 0) {
        entry.timer = setTimeout(() => {
          this.gates.delete(resumeKey);
          reject(new HitlGateTimeoutError(resumeKey));
        }, effectiveTimeout);
      }
      this.gates.set(resumeKey, entry);
    });
  }

  resolve(resumeKey: string, decision: HitlDecision): void {
    if (decision.type === "approve_session") {
      for (const toolName of decision.toolNames ?? []) {
        this.sessionApproved.add(toolName);
      }
    }

    const entry = this.gates.get(resumeKey);
    const normalized = decision.type === "approve_session" ? { type: "approve" as const } : decision;
    if (!entry) {
      this.pending.set(resumeKey, normalized);
      return;
    }
    clearTimeout(entry.timer);
    entry.resolve(normalized);
    this.gates.delete(resumeKey);
  }

  /**
   * Dispose this gate. All pending `wait()` calls reject immediately with
   * `HitlGateDisposedError`. Call this when the agent loop exits with an error
   * to prevent resolver leaks.
   */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    const err = new HitlGateDisposedError();
    for (const [, entry] of this.gates) {
      clearTimeout(entry.timer);
      entry.reject(err);
    }
    this.gates.clear();
    this.pending.clear();
  }

  resetSessionApprovals(): void {
    this.sessionApproved.clear();
    this.pending.clear();
  }
}
