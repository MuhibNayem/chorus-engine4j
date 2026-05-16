export interface RetryPolicy {
  maxAttempts: number;
  shouldRetry(error: Error, attempt: number): boolean;
  delayMs(attempt: number): number;
}

/** HTTP error with a numeric status code — thrown by well-behaved provider implementations. */
export interface HttpError extends Error {
  status?: number;
  statusCode?: number;
}

/** Node.js system error codes that indicate transient network failures. */
const RETRYABLE_NODE_CODES = new Set([
  "ECONNRESET",
  "ECONNREFUSED",
  "ETIMEDOUT",
  "EPIPE",
  "ENOTFOUND",
]);

function isRetryableHttpStatus(err: HttpError): boolean {
  const code = err.status ?? err.statusCode;
  if (!code) return false;
  return code === 429 || code === 503 || code === 502 || code === 504;
}

function isRetryableNodeCode(err: Error): boolean {
  const code = (err as Error & { code?: string }).code;
  if (!code) return false;
  return RETRYABLE_NODE_CODES.has(code);
}

function isRetryableMessage(err: Error): boolean {
  return /429|503|502|504|rate.?limit|too.?many.?req|temporar|timeout|timed.?out|service.?unavail|overload/i
    .test(err.message);
}

export function isRetryable(error: Error): boolean {
  return (
    isRetryableHttpStatus(error as HttpError) ||
    isRetryableNodeCode(error) ||
    isRetryableMessage(error)
  );
}

/**
 * Conservative 2-attempt policy for tool calls.
 * Handles transient network errors and rate limits.
 */
export const DEFAULT_RETRY_POLICY: RetryPolicy = {
  maxAttempts: 3,
  shouldRetry: (error, attempt) => attempt < 3 && isRetryable(error),
  delayMs: (attempt) => Math.min(500 * 2 ** (attempt - 1), 8_000),
};

/**
 * Aggressive rate-limit policy for LLM calls — more attempts, longer backoff.
 * Use this for provider `generate()` / `streamWithTools()` calls.
 */
export const RATE_LIMIT_RETRY_POLICY: RetryPolicy = {
  maxAttempts: 5,
  shouldRetry: (error, attempt) => attempt < 5 && isRetryable(error),
  // Exponential with jitter to prevent thundering herd
  delayMs: (attempt) => {
    const base = Math.min(1_000 * 2 ** attempt, 30_000);
    const jitter = Math.random() * base * 0.2;
    return Math.round(base + jitter);
  },
};

export async function withRetry<T>(
  action: () => Promise<T>,
  policy: RetryPolicy = DEFAULT_RETRY_POLICY,
): Promise<{ value: T; attempts: number }> {
  let attempt = 0;

  while (true) {
    try {
      return { value: await action(), attempts: attempt + 1 };
    } catch (error) {
      const err = error instanceof Error ? error : new Error(String(error));
      attempt += 1;
      if (attempt >= policy.maxAttempts || !policy.shouldRetry(err, attempt)) {
        throw err;
      }
      await new Promise((resolve) => setTimeout(resolve, policy.delayMs(attempt)));
    }
  }
}
