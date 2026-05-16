/**
 * In-Process Telemetry Tracer
 *
 * Replaces the post-hoc `swarmEventsToSpans` bridge with real-time span
 * creation inside `runAgentLoop` and `runSwarmGraph`. This enables:
 *   - Distributed trace context propagation across subagent boundaries
 *   - Live monitoring of agent "thinking" time via OTLP exporters
 *   - Sub-second latency visibility into wave execution
 *
 * Design:
 *   - Lightweight: no OTel SDK dependency; uses existing `OTelSpan` types.
 *   - Stack-based: spans form a tree via `parentSpanId`.
 *   - Best-effort export: failures are logged to stderr, never thrown.
 *   - Backward-compatible: `SwarmTracer` JSONL events continue to work.
 */

import { randomBytes } from "crypto";
import type { OTelSpan, TelemetryConfig } from "./types.js";
import { OTelExporter, attr, toNanos } from "./exporter.js";

/** A mutable span that can be started, updated, and ended in-process. */
export interface MutableSpan {
  traceId: string;
  spanId: string;
  parentSpanId?: string;
  name: string;
  kind: number;
  startTimeUnixNano: string;
  attributes: OTelSpan["attributes"];
  status: OTelSpan["status"];
  /** End the span and convert it to an immutable OTelSpan. */
  end(endMs?: number): OTelSpan;
  /** Add an attribute to the span. */
  setAttribute(key: string, value: string | number | boolean): void;
  /** Mark the span as an error. */
  setError(message: string): void;
}

function hexId(bytes: number): string {
  return randomBytes(bytes).toString("hex");
}

function createMutableSpan(
  name: string,
  traceId: string,
  parentSpanId?: string,
): MutableSpan {
  const spanId = hexId(8);
  const startMs = Date.now();
  const attributes: OTelSpan["attributes"] = [];

  return {
    traceId,
    spanId,
    parentSpanId,
    name,
    kind: 1, // INTERNAL
    startTimeUnixNano: toNanos(startMs),
    attributes,
    status: { code: 1 }, // OK

    setAttribute(key, value) {
      attributes.push(attr(key, value));
    },

    setError(message) {
      attributes.push(attr("error.message", message));
      this.status = { code: 2, message };
    },

    end(endMs = Date.now()) {
      return {
        traceId: this.traceId,
        spanId: this.spanId,
        parentSpanId: this.parentSpanId,
        name: this.name,
        kind: this.kind as OTelSpan["kind"],
        startTimeUnixNano: this.startTimeUnixNano,
        endTimeUnixNano: toNanos(endMs),
        attributes: this.attributes,
        status: this.status,
      };
    },
  };
}

/** Thread-local-ish span stack. Each tracer instance has its own stack. */
export class InProcessTracer {
  private exporter: OTelExporter;
  private config: TelemetryConfig;
  private activeSpans: MutableSpan[] = [];
  private traceId: string;

  constructor(config?: TelemetryConfig) {
    this.config = config ?? {};
    this.exporter = new OTelExporter(this.config);
    this.traceId = hexId(16);
  }

  /** Reset the trace ID — useful when starting a new top-level workflow. */
  newTrace(): void {
    this.traceId = hexId(16);
    this.activeSpans = [];
  }

  /** Get the current trace ID for propagation across subagent boundaries. */
  getTraceId(): string {
    return this.traceId;
  }

  /** Set the trace ID from an external context (e.g., incoming distributed trace). */
  setTraceId(traceId: string): void {
    this.traceId = traceId;
  }

  /** Start a new span, optionally as a child of the currently active span. */
  startSpan(name: string, opts?: { parentSpanId?: string; attributes?: Record<string, string | number | boolean> }): MutableSpan {
    const parent = opts?.parentSpanId ?? this.activeSpans[this.activeSpans.length - 1]?.spanId;
    const span = createMutableSpan(name, this.traceId, parent);

    if (opts?.attributes) {
      for (const [k, v] of Object.entries(opts.attributes)) {
        span.setAttribute(k, v);
      }
    }

    this.activeSpans.push(span);
    return span;
  }

  /** End a span and remove it from the active stack. */
  endSpan(span: MutableSpan, opts?: { error?: string }): OTelSpan {
    if (opts?.error) span.setError(opts.error);

    const idx = this.activeSpans.indexOf(span);
    if (idx >= 0) this.activeSpans.splice(idx, 1);

    return span.end();
  }

  /**
   * Run an async function inside a span. The span is automatically ended
   * when the promise resolves or rejects.
   */
  async withSpan<T>(
    name: string,
    fn: (span: MutableSpan) => Promise<T>,
    opts?: { attributes?: Record<string, string | number | boolean> },
  ): Promise<T> {
    const span = this.startSpan(name, opts);
    try {
      const result = await fn(span);
      this.endSpan(span);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.endSpan(span, { error: message });
      throw error;
    }
  }

  /** Export all given spans immediately. Best-effort; never throws. */
  async export(spans: OTelSpan[]): Promise<void> {
    try {
      await this.exporter.exportSpans(spans);
    } catch {
      // Export is best-effort; stderr logging happens inside OTelExporter.
    }
  }

  /** Export and end all still-active spans (useful for emergency cleanup). */
  async flush(): Promise<void> {
    const spans = this.activeSpans.map((s) => s.end());
    this.activeSpans = [];
    await this.export(spans);
  }
}

/** Global tracer instance for the process. Consumers may create their own. */
let globalTracer: InProcessTracer | undefined;

export function getGlobalTracer(config?: TelemetryConfig): InProcessTracer {
  if (!globalTracer) {
    globalTracer = new InProcessTracer(config);
  }
  return globalTracer;
}

export function setGlobalTracer(tracer: InProcessTracer): void {
  globalTracer = tracer;
}
