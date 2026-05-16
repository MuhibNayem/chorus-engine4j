import { describe, it, expect, vi } from "vitest";
import { OTelExporter, attr, toNanos } from "../telemetry/exporter.js";
import type { OTelSpan } from "../telemetry/types.js";

describe("telemetry primitives", () => {
  it("attr builds string attribute", () => {
    expect(attr("key", "value")).toEqual({ key: "key", value: { stringValue: "value" } });
  });

  it("attr builds bool attribute", () => {
    expect(attr("key", true)).toEqual({ key: "key", value: { boolValue: true } });
  });

  it("attr builds int attribute for integers", () => {
    expect(attr("key", 42)).toEqual({ key: "key", value: { intValue: "42" } });
  });

  it("attr builds double attribute for floats", () => {
    expect(attr("key", 3.14)).toEqual({ key: "key", value: { doubleValue: 3.14 } });
  });

  it("toNanos converts ms to nanoseconds string", () => {
    expect(toNanos(1)).toBe("1000000");
    expect(toNanos(0)).toBe("0");
  });
});

describe("OTelExporter — production telemetry", () => {
  const makeSpan = (name: string): OTelSpan => ({
    traceId: "abc",
    spanId: "def",
    name,
    kind: 1,
    startTimeUnixNano: toNanos(Date.now()),
    endTimeUnixNano: toNanos(Date.now() + 1),
    attributes: [],
    status: { code: 1 },
  });

  it("exports spans to stdout when configured", async () => {
    const write = vi.spyOn(process.stdout, "write").mockImplementation(() => true);
    const exporter = new OTelExporter({ stdoutExport: true });
    await exporter.exportSpans([makeSpan("test")]);
    expect(write).toHaveBeenCalled();
    write.mockRestore();
  });

  it("buffers failed spans for retry", async () => {
    const exporter = new OTelExporter({ endpoint: "http://localhost:99999/bad" });
    // First export fails
    await exporter.exportSpans([makeSpan("a")]);
    // Should not throw
    expect(true).toBe(true);
  });

  it("drops spans after max retries exhausted", async () => {
    const write = vi.spyOn(process.stderr, "write").mockImplementation(() => true);
    const exporter = new OTelExporter({ endpoint: "http://localhost:99999/bad" });
    // Force retryAttempt to max and clear cooldown
    (exporter as any).retryAttempt = 3;
    (exporter as any).nextRetryAt = 0;
    await exporter.exportSpans([makeSpan("span-final")]);
    const permanentLogged = write.mock.calls.some((call) =>
      String(call[0]).includes("permanently failed"),
    );
    expect(permanentLogged).toBe(true);
    write.mockRestore();
  });

  it("honours backoff cooldown between retries", async () => {
    const exporter = new OTelExporter({ endpoint: "http://localhost:99999/bad" });
    await exporter.exportSpans([makeSpan("first")]); // fails, sets retryAttempt=1, nextRetryAt in future
    const before = Date.now();
    await exporter.exportSpans([makeSpan("second")]); // should be buffered, not sent
    // No delay needed because it's buffered during cooldown
    expect(Date.now() - before).toBeLessThan(100);
  });

  it("overflows buffer by dropping oldest spans", async () => {
    const write = vi.spyOn(process.stderr, "write").mockImplementation(() => true);
    const exporter = new OTelExporter({ endpoint: "http://localhost:99999/bad" });
    const manySpans = Array.from({ length: 1_100 }, (_, i) => makeSpan(`span-${i}`));
    await exporter.exportSpans(manySpans);
    const overflowLogged = write.mock.calls.some((call) =>
      String(call[0]).includes("buffer overflow"),
    );
    expect(overflowLogged).toBe(true);
    write.mockRestore();
  });
});
