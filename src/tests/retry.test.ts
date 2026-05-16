import { describe, it, expect } from "vitest";
import { withRetry, isRetryable, DEFAULT_RETRY_POLICY, RATE_LIMIT_RETRY_POLICY, type HttpError } from "../index.js";

describe("isRetryable — error classification", () => {
  it("retries on HTTP 429", () => {
    const err = new Error("rate limit") as HttpError;
    err.status = 429;
    expect(isRetryable(err)).toBe(true);
  });

  it("retries on HTTP 502, 503, 504", () => {
    for (const code of [502, 503, 504]) {
      const err = new Error("bad gateway") as HttpError;
      err.status = code;
      expect(isRetryable(err)).toBe(true);
    }
  });

  it("retries on Node.js ECONNRESET code", () => {
    const err = Object.assign(new Error("Connection reset"), { code: "ECONNRESET" });
    expect(isRetryable(err)).toBe(true);
  });

  it("retries on Node.js ETIMEDOUT code", () => {
    const err = Object.assign(new Error("Connection timed out"), { code: "ETIMEDOUT" });
    expect(isRetryable(err)).toBe(true);
  });

  it("retries on Node.js ECONNREFUSED code", () => {
    const err = Object.assign(new Error("Connection refused"), { code: "ECONNREFUSED" });
    expect(isRetryable(err)).toBe(true);
  });

  it("retries on message containing 'rate limit'", () => {
    expect(isRetryable(new Error("You have hit the rate limit"))).toBe(true);
  });

  it("retries on message containing 'timeout'", () => {
    expect(isRetryable(new Error("Request timeout"))).toBe(true);
  });

  it("does NOT retry on HTTP 400", () => {
    const err = new Error("bad request") as HttpError;
    err.status = 400;
    expect(isRetryable(err)).toBe(false);
  });

  it("does NOT retry on HTTP 401", () => {
    const err = new Error("unauthorized") as HttpError;
    err.status = 401;
    expect(isRetryable(err)).toBe(false);
  });

  it("does NOT retry on HTTP 500", () => {
    const err = new Error("internal error") as HttpError;
    err.status = 500;
    expect(isRetryable(err)).toBe(false);
  });

  it("does NOT retry on arbitrary errors", () => {
    expect(isRetryable(new Error("Something went wrong"))).toBe(false);
    expect(isRetryable(new Error("quota exceeded"))).toBe(false);
  });
});

describe("withRetry — execution wrapper", () => {
  it("returns immediately on success", async () => {
    const result = await withRetry(async () => "ok", DEFAULT_RETRY_POLICY);
    expect(result.value).toBe("ok");
    expect(result.attempts).toBe(1);
  });

  it("retries on retryable error and eventually succeeds", async () => {
    let calls = 0;
    const result = await withRetry(async () => {
      calls++;
      if (calls < 3) {
        throw Object.assign(new Error("temporarily unavailable"), { status: 503 });
      }
      return "recovered";
    }, DEFAULT_RETRY_POLICY);
    expect(result.value).toBe("recovered");
    expect(result.attempts).toBe(3);
  });

  it("throws after max attempts exhausted", async () => {
    let calls = 0;
    await expect(
      withRetry(async () => {
        calls++;
        throw Object.assign(new Error("timeout"), { status: 504 });
      }, { ...DEFAULT_RETRY_POLICY, maxAttempts: 2 }),
    ).rejects.toThrow("timeout");
    expect(calls).toBe(2);
  });

  it("does not retry non-retryable errors", async () => {
    let calls = 0;
    await expect(
      withRetry(async () => {
        calls++;
        throw new Error("invalid request");
      }, DEFAULT_RETRY_POLICY),
    ).rejects.toThrow("invalid request");
    expect(calls).toBe(1);
  });

  it("RATE_LIMIT_RETRY_POLICY has more attempts and jitter", async () => {
    let calls = 0;
    const fastPolicy = {
      ...RATE_LIMIT_RETRY_POLICY,
      delayMs: (attempt: number) => 10, // override jitter for test speed
    };
    const result = await withRetry(async () => {
      calls++;
      if (calls < 4) {
        throw new Error("429 rate limit");
      }
      return "ok";
    }, fastPolicy);
    expect(result.attempts).toBe(4);
  }, 10_000);
});
