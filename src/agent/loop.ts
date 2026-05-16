import { z } from "zod";
import type { ChatMessage, ModelResponse, ToolCall, ToolDef, ToolStreamEvent } from "../llm/provider.js";
import { DEFAULT_RETRY_POLICY, isRetryable, withRetry } from "./retry.js";
import type { AgentMiddleware, RoundContext } from "./middleware.js";
import type { AgentEvent, AgentTool, HitlDecision, HitlRequest, LoopOptions } from "./types.js";
import { estimateCost } from "../llm/pricing.js";
import type { InProcessTracer, MutableSpan } from "../telemetry/inprocess.js";

/**
 * Wraps a streaming iterable with a per-chunk timeout deadline.
 * Throws if no chunk (token/done) arrives within `timeoutMs` milliseconds.
 * The deadline resets after each received chunk, so fast streams are never
 * penalized — only hung providers that stall mid-stream.
 */
async function* withStreamTimeout<T extends ToolStreamEvent>(
  source: AsyncIterable<T>,
  timeoutMs: number,
): AsyncGenerator<T> {
  const iter = source[Symbol.asyncIterator]();
  try {
    while (true) {
      const timeoutError = new Error(`Provider stream timed out after ${timeoutMs}ms`);
      const result = await Promise.race([
        iter.next(),
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(timeoutError), timeoutMs),
        ),
      ]);
      if (result.done) break;
      yield result.value;
    }
  } finally {
    await iter.return?.();
  }
}

/**
 * Consumes the LLM provider stream with automatic retry for transient failures.
 *
 * Design:
 *   • Retryable errors that occur BEFORE any tokens are yielded → silent retry
 *     with exponential backoff. The consumer sees no partial output.
 *   • Errors that occur AFTER tokens have been yielded → fatal. We cannot retry
 *     without duplicating already-emitted tokens.
 *   • Truncated streams (end without `done`) → retry if no tokens emitted,
 *     otherwise fatal.
 *   • Non-retryable errors → fatal immediately.
 */
type StreamConsumptionEvent =
  | { type: "token"; text: string }
  | { type: "thinking"; text: string }
  | { type: "stream-done"; response: ModelResponse; inputTokens: number; outputTokens: number }
  | { type: "stream-error"; message: string; fatal: boolean };

async function* consumeStream(
  provider: LoopOptions["provider"],
  model: string,
  messages: ChatMessage[],
  systemPrompt: string,
  toolDefs: ToolDef[],
  streamTimeoutMs: number | undefined,
): AsyncGenerator<StreamConsumptionEvent> {
  const maxAttempts = 3;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    let yieldedAny = false;
    try {
      const rawStream = provider.streamWithTools({ model, messages, systemPrompt, tools: toolDefs });
      const stream = streamTimeoutMs ? withStreamTimeout(rawStream, streamTimeoutMs) : rawStream;
      let response: ModelResponse | null = null;
      let inputTokens = 0;
      let outputTokens = 0;

      for await (const event of stream) {
        if (event.type === "token") {
          yieldedAny = true;
          yield { type: "token", text: event.text };
          continue;
        }
        if (event.type === "thinking") {
          yieldedAny = true;
          yield { type: "thinking", text: event.text };
          continue;
        }
        if (event.type === "done") {
          response = event.response;
          if (response.usage) {
            inputTokens = response.usage.inputTokens;
            outputTokens = response.usage.outputTokens;
          }
          break;
        }
      }

      if (!response) {
        // Stream ended without a completion event — provider bug or truncation.
        if (attempt < maxAttempts && !yieldedAny) {
          await new Promise((r) => setTimeout(r, Math.min(500 * 2 ** attempt, 8_000)));
          continue;
        }
        yield {
          type: "stream-error",
          message: `Provider stream ended without completion${yieldedAny ? " after emitting partial tokens" : ""}.`,
          fatal: true,
        };
        return;
      }

      yield { type: "stream-done", response, inputTokens, outputTokens };
      return;
    } catch (error) {
      const err = error instanceof Error ? error : new Error(String(error));
      if (attempt < maxAttempts && isRetryable(err) && !yieldedAny) {
        await new Promise((r) => setTimeout(r, Math.min(500 * 2 ** attempt, 8_000)));
        continue;
      }
      yield { type: "stream-error", message: err.message, fatal: true };
      return;
    }
  }
}

type ToolByName = Map<string, AgentTool>;

function normalizeToolCallArgs(toolCall: ToolCall): Record<string, unknown> {
  const raw = toolCall.function.arguments.trim();
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
    throw new Error("Tool arguments must be a JSON object.");
  } catch (error) {
    throw new Error(
      `Invalid JSON arguments for ${toolCall.function.name}: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}

function toHitlRequests(toolCalls: ToolCall[]): HitlRequest[] {
  return toolCalls.map((toolCall) => ({
    id: toolCall.id,
    name: toolCall.function.name,
    args: safeArgs(toolCall),
  }));
}

function safeArgs(toolCall: ToolCall): Record<string, unknown> {
  try {
    return normalizeToolCallArgs(toolCall);
  } catch {
    return { _raw: toolCall.function.arguments };
  }
}

function toolDefsFromTools(tools: AgentTool[]): ToolDef[] {
  return tools
    .filter((tool): tool is AgentTool & { name: string } => typeof tool.name === "string" && tool.name.length > 0)
    .map((tool) => ({
      type: "function",
      function: {
        name: tool.name!,
        description: tool.description,
        parameters: zodToJsonSchema(tool.schema, tool.name),
      },
    }));
}

function zodToJsonSchema(schema: unknown, toolName?: string): Record<string, unknown> {
  if (schema instanceof z.ZodType) {
    return normalizeZodSchema(schema);
  }
  if (schema && typeof schema === "object" && !Array.isArray(schema)) {
    const maybeSchema = schema as Record<string, unknown>;
    if (
      typeof maybeSchema.type === "string" ||
      (maybeSchema.properties !== null && typeof maybeSchema.properties === "object" && !Array.isArray(maybeSchema.properties)) ||
      Array.isArray(maybeSchema.required)
    ) {
      return maybeSchema;
    }
  }
  // Schema was provided but did not match Zod or JSON-Schema shapes — warn
  // so developers know parameter validation has been stripped for this tool.
  if (schema !== undefined && schema !== null) {
    process.stderr.write(
      `[chorus] Warning: tool "${toolName ?? "unknown"}" has an unrecognised schema ` +
      `(expected Zod schema or JSON-Schema object). Falling back to open object schema. ` +
      `Parameter validation will be skipped.\n`,
    );
  }
  return { type: "object", properties: {}, additionalProperties: true };
}

function normalizeZodSchema(schema: z.ZodTypeAny): Record<string, unknown> {
  if (schema instanceof z.ZodOptional || schema instanceof z.ZodDefault) {
    return normalizeZodSchema(schema._def.innerType);
  }
  if (schema instanceof z.ZodString) {
    return { type: "string" };
  }
  if (schema instanceof z.ZodNumber) {
    return { type: "number" };
  }
  if (schema instanceof z.ZodBoolean) {
    return { type: "boolean" };
  }
  if (schema instanceof z.ZodEnum) {
    return { type: "string", enum: [...schema.options] };
  }
  if (schema instanceof z.ZodArray) {
    return { type: "array", items: normalizeZodSchema(schema.element) };
  }
  if (schema instanceof z.ZodObject) {
    const shape = schema.shape;
    const properties: Record<string, unknown> = {};
    const required: string[] = [];
    for (const [key, value] of Object.entries(shape)) {
      const field = value as z.ZodTypeAny;
      properties[key] = normalizeZodSchema(field);
      if (!(field instanceof z.ZodOptional) && !(field instanceof z.ZodDefault)) {
        required.push(key);
      }
    }
    return {
      type: "object",
      properties,
      ...(required.length > 0 ? { required } : {}),
      additionalProperties: false,
    };
  }
  return { type: "object", properties: {}, additionalProperties: true };
}

function mergeAssistantMessage(
  history: ChatMessage[],
  response: ModelResponse,
): void {
  history.push({
    role: "assistant",
    content: response.content,
    ...(response.reasoning_content ? { reasoning_content: response.reasoning_content } : {}),
    ...(response.tool_calls ? { tool_calls: response.tool_calls } : {}),
  });
}

async function executeToolCall(
  toolCall: ToolCall,
  toolsByName: ToolByName,
): Promise<{ result: string; attempts: number }> {
  const tool = toolsByName.get(toolCall.function.name);
  if (!tool) {
    throw new Error(`Unknown tool: ${toolCall.function.name}`);
  }

  const args = normalizeToolCallArgs(toolCall);
  const { value, attempts } = await withRetry(
    async () => tool.invoke(args),
    DEFAULT_RETRY_POLICY,
  );

  return {
    result: typeof value === "string" ? value : JSON.stringify(value, null, 2),
    attempts,
  };
}

function applyHitlDecision(
  decision: HitlDecision,
  history: ChatMessage[],
): "continue" | "stop" {
  if (decision.type === "reject") {
    history.push({
      role: "user",
      content: decision.message?.trim() || "Tool execution denied by user.",
    });
    return "stop";
  }
  return "continue";
}

/**
 * Execute middleware hooks grouped by priority. Hooks within the same priority
 * run in parallel via `Promise.all`; priority groups run sequentially from
 * lowest to highest. This eliminates linear I/O latency when multiple
 * middlewares perform independent work (e.g., logging + RAG fetch).
 *
 * Hooks with ordering semantics (`beforeTool` cancellation, `afterTool`
 * transformation chaining, `maybeCompact` first-wins) remain sequential and
 * are NOT routed through this helper.
 */
/**
 * Execute middleware hooks grouped by priority. Hooks within the same priority
 * run in parallel via `Promise.all`; priority groups run sequentially from
 * lowest to highest. This eliminates linear I/O latency when multiple
 * middlewares perform independent work (e.g., logging + RAG fetch).
 *
 * Hooks with ordering semantics (`beforeTool` cancellation, `afterTool`
 * transformation chaining, `maybeCompact` first-wins) remain sequential and
 * are NOT routed through this helper.
 */
async function runMiddleware(
  middleware: AgentMiddleware[],
  hook: "beforeRound" | "afterRound",
  ...args: [RoundContext]
): Promise<void> {
  // Group by priority (default 0)
  const groups = new Map<number, AgentMiddleware[]>();
  for (const mw of middleware) {
    const p = mw.priority ?? 0;
    const list = groups.get(p) ?? [];
    list.push(mw);
    groups.set(p, list);
  }

  const sortedPriorities = [...groups.keys()].sort((a, b) => a - b);
  for (const p of sortedPriorities) {
    const hooks = groups.get(p) ?? [];
    await Promise.all(
      hooks.map(async (mw) => {
        const fn = mw[hook];
        if (fn) await fn.apply(mw, args);
      }),
    );
  }
}

export async function* runAgentLoop(options: LoopOptions): AsyncGenerator<AgentEvent> {
  const {
    provider,
    model,
    tools,
    messages,
    systemPrompt,
    threadId,
    hitlGate,
    btwQueue,
    policy,
    checkpointer,
    maxRounds = 500,
    resumedDecision,
    middleware = [],
    abortSignal,
    streamTimeoutMs,
    outputSchema,
    tracer,
  } = options;

  const saved = await checkpointer.load(threadId);
  // Only restore when a HITL-paused run exists for this thread. A completed turn
  // also writes a checkpoint, but the caller's messages array already contains the
  // new user turn and must not be overridden.
  const restoreFromCheckpoint = saved?.waitingForHitl != null;
  const history = restoreFromCheckpoint ? saved!.messages : messages;

  let round = restoreFromCheckpoint ? saved!.round : 0;
  let totalTools = 0;
  let pendingDecision = resumedDecision;
  const loopStartMs = Date.now();
  let totalInputTokens = 0;
  let totalOutputTokens = 0;

  // In-process telemetry: loop-level span
  const loopSpan = tracer?.startSpan("agent.loop", { attributes: { "agent.thread_id": threadId, "agent.model": model } });
  const spansToExport: import("../telemetry/types.js").OTelSpan[] = [];

  while (round < maxRounds) {
    if (abortSignal?.aborted) {
      yield { type: "aborted", message: "Interrupted by user." };
      return;
    }
    for (const text of btwQueue.drain()) {
      history.push({ role: "user", content: `[/btw] ${text}` });
      yield { type: "btw", text };
    }

    // Middleware: beforeRound
    const roundCtx: RoundContext = { round, threadId, model, history, toolCallsThisRound: 0 };
    await runMiddleware(middleware, "beforeRound", roundCtx);

    // Rebuild tools + system prompt each round (enables per-turn skill routing)
    const allTools = [...tools, ...middleware.flatMap((mw) => mw.extraTools?.() ?? [])];
    const toolsByName: ToolByName = new Map(
      allTools
        .filter((tool): tool is AgentTool & { name: string } => typeof tool.name === "string" && tool.name.length > 0)
        .map((tool) => [tool.name!, tool]),
    );

    // Pass tool registry to middlewares that need it for pattern execution
    for (const mw of middleware) {
      mw.setTools?.(toolsByName);
    }

    const toolDefs = toolDefsFromTools(allTools);

    const extraPrompts = middleware.flatMap((mw) => {
      const extra = mw.extraSystemPrompt?.();
      return extra ? [extra] : [];
    });
    const effectiveSystemPrompt = extraPrompts.length > 0
      ? `${systemPrompt}\n\n${extraPrompts.join("\n\n")}`
      : systemPrompt;

    // Middleware: maybeCompact — first matching middleware wins
    for (const mw of middleware) {
      if (!mw.maybeCompact) continue;
      const compactResult = await mw.maybeCompact(history, { model, systemPrompt: effectiveSystemPrompt });
      if (compactResult) {
        history.splice(0, history.length, ...compactResult.replacement);
        yield { type: "compacted", removedMessages: compactResult.removedMessages, savedTokens: compactResult.savedTokens };
        break;
      }
    }

    // In-process telemetry: round-level span
    const roundSpan = tracer?.startSpan("agent.round", {
      parentSpanId: loopSpan?.spanId,
      attributes: { "agent.round": round, "agent.thread_id": threadId },
    });

    let response: ModelResponse | null = null;
    for await (const event of consumeStream(provider, model, history, effectiveSystemPrompt, toolDefs, streamTimeoutMs)) {
      if (event.type === "token") {
        yield { type: "token", text: event.text };
        continue;
      }
      if (event.type === "thinking") {
        yield { type: "thinking", text: event.text };
        continue;
      }
      if (event.type === "stream-done") {
        response = event.response;
        totalInputTokens += event.inputTokens;
        totalOutputTokens += event.outputTokens;
        break;
      }
      if (event.type === "stream-error") {
        if (roundSpan) spansToExport.push(tracer!.endSpan(roundSpan, { error: event.message }));
        yield { type: "error", message: event.message, fatal: event.fatal };
        if (loopSpan) spansToExport.push(tracer!.endSpan(loopSpan, { error: event.message }));
        if (tracer) await tracer.export(spansToExport);
        return;
      }
    }

    if (!response) {
      // Safety net: consumeStream should always produce either stream-done or stream-error,
      // but if it somehow returns early we emit a fatal error rather than silently substituting
      // an empty response (which would mask provider bugs).
      if (roundSpan) spansToExport.push(tracer!.endSpan(roundSpan, { error: "No response from stream" }));
      yield { type: "error", message: "Agent loop exited stream consumption without a response.", fatal: true };
      if (loopSpan) spansToExport.push(tracer!.endSpan(loopSpan, { error: "No response from stream" }));
      if (tracer) await tracer.export(spansToExport);
      return;
    }

    mergeAssistantMessage(history, response);

    if (!response.tool_calls || response.tool_calls.length === 0) {
      // Issue 15: Validate response against outputSchema before accepting it as final.
      if (outputSchema) {
        try {
          const parsed = JSON.parse(response.content) as unknown;
          outputSchema.parse(parsed);
        } catch (err) {
          const correctionMsg =
            `Your response must be a valid JSON object matching the required schema. ` +
            `Validation error: ${err instanceof Error ? err.message : String(err)}. ` +
            `Please respond with a valid JSON object only — no prose, no markdown fences.`;
          history.push({ role: "user", content: correctionMsg });
          round += 1;
          await checkpointer.save(threadId, { messages: history, round });
          yield { type: "checkpoint", round, threadId };
          continue;
        }
      }

      await checkpointer.save(threadId, { messages: history, round });
      yield { type: "checkpoint", round, threadId };
      if (roundSpan) spansToExport.push(tracer!.endSpan(roundSpan));
      if (loopSpan) spansToExport.push(tracer!.endSpan(loopSpan));
      if (tracer) await tracer.export(spansToExport);
      yield {
        type: "done",
        response: response.content,
        reasoning: response.reasoning_content ?? "",
        toolCount: totalTools,
        history,
        inputTokens: totalInputTokens,
        outputTokens: totalOutputTokens,
        costUsd: provider.estimateCost?.(totalInputTokens, totalOutputTokens) ?? estimateCost(model, totalInputTokens, totalOutputTokens),
        durationMs: Date.now() - loopStartMs,
      };
      return;
    }

    totalTools += response.tool_calls.length;
    const requests = toHitlRequests(response.tool_calls);
    let decision = pendingDecision;
    pendingDecision = undefined;

    if (!decision && hitlGate.shouldPause(response.tool_calls, policy)) {
      const resumeKey = `hitl-${threadId}-${round}`;
      await checkpointer.save(threadId, {
        messages: history,
        round,
        waitingForHitl: {
          resumeKey,
          requests,
          toolCalls: response.tool_calls,
          assistant: response,
        },
      });
      yield { type: "checkpoint", round, threadId };
      yield { type: "hitl", requests, resumeKey };
      decision = await hitlGate.wait(resumeKey);
    }

    if (decision && applyHitlDecision(decision, history) === "stop") {
      await checkpointer.save(threadId, { messages: history, round });
      yield { type: "checkpoint", round, threadId };
      if (roundSpan) spansToExport.push(tracer!.endSpan(roundSpan));
      if (loopSpan) spansToExport.push(tracer!.endSpan(loopSpan));
      if (tracer) await tracer.export(spansToExport);
      yield {
        type: "done",
        response: response.content,
        reasoning: response.reasoning_content ?? "",
        toolCount: totalTools,
        history,
        inputTokens: totalInputTokens,
        outputTokens: totalOutputTokens,
        costUsd: provider.estimateCost?.(totalInputTokens, totalOutputTokens) ?? estimateCost(model, totalInputTokens, totalOutputTokens),
        durationMs: Date.now() - loopStartMs,
      };
      return;
    }

    let toolCallsThisRound = 0;
    for (const toolCall of response.tool_calls) {
      const name = toolCall.function.name;
      let args: Record<string, unknown>;
      try {
        args = normalizeToolCallArgs(toolCall);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        yield { type: "tool-error", id: toolCall.id, name, error: message, willRetry: false };
        history.push({
          role: "tool",
          tool_call_id: toolCall.id,
          content: `Error: ${message}`,
        });
        continue;
      }

      // Middleware: beforeTool — any middleware may cancel tool execution
      let cancelled: { result: string } | undefined;
      for (const mw of middleware) {
        if (!mw.beforeTool) continue;
        const directive = await mw.beforeTool({ id: toolCall.id, name, args });
        if (directive && directive.cancel) {
          cancelled = { result: directive.result };
          break;
        }
      }
      if (cancelled) {
        history.push({ role: "tool", tool_call_id: toolCall.id, content: cancelled.result });
        yield { type: "tool-done", id: toolCall.id, name, result: cancelled.result, durationMs: 0 };
        toolCallsThisRound += 1;
        continue;
      }

      toolCallsThisRound += 1;
      yield { type: "tool-start", id: toolCall.id, name, args };
      const startedAt = Date.now();

      // In-process telemetry: tool-level span
      const toolSpan = tracer?.startSpan("agent.tool_call", {
        parentSpanId: roundSpan?.spanId,
        attributes: { "agent.tool_name": name, "agent.thread_id": threadId },
      });

      try {
        const { result: rawResult, attempts } = await executeToolCall(toolCall, toolsByName);
        const durationMs = Date.now() - startedAt;
        if (toolSpan) {
          toolSpan.setAttribute("agent.tool_duration_ms", durationMs);
          spansToExport.push(tracer!.endSpan(toolSpan));
        }

        // Middleware: afterTool — each mw may transform the result string
        let result = rawResult;
        for (const mw of middleware) {
          if (!mw.afterTool) continue;
          const transformed = await mw.afterTool({ id: toolCall.id, name, result, durationMs });
          if (transformed !== undefined) result = transformed;
        }

        history.push({
          role: "tool",
          tool_call_id: toolCall.id,
          content: result,
        });
        yield {
          type: "tool-done",
          id: toolCall.id,
          name,
          result: attempts > 1 ? `${result}\n\n[retried ${attempts - 1} time(s)]` : result,
          durationMs,
        };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        if (toolSpan) spansToExport.push(tracer!.endSpan(toolSpan, { error: message }));
        history.push({
          role: "tool",
          tool_call_id: toolCall.id,
          content: `Error: ${message}`,
        });
        yield {
          type: "tool-error",
          id: toolCall.id,
          name,
          error: message,
          willRetry: false,
        };
      }
    }

    round += 1;
    // Middleware: afterRound
    const afterCtx: RoundContext = { round, threadId, model, history, toolCallsThisRound };
    await runMiddleware(middleware, "afterRound", afterCtx);

    if (roundSpan) spansToExport.push(tracer!.endSpan(roundSpan));
    await checkpointer.save(threadId, { messages: history, round });
    yield { type: "checkpoint", round, threadId };
  }

  if (loopSpan) spansToExport.push(tracer!.endSpan(loopSpan, { error: `Exceeded max rounds (${maxRounds})` }));
  if (tracer) await tracer.export(spansToExport);
  yield {
    type: "error",
    message: `Agent loop exceeded max rounds (${maxRounds}).`,
    fatal: true,
  };
}
