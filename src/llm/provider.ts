import type { ProviderName } from "./config.js";

export type ChatMessage = {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  reasoning_content?: string;
  tool_call_id?: string;
  tool_calls?: ToolCall[];
};

export type ToolDef = {
  type: "function";
  function: {
    name: string;
    description?: string;
    parameters?: Record<string, unknown>;
  };
};

export type ToolCall = {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string;
  };
};

export type ModelResponse = {
  content: string;
  reasoning_content?: string;
  tool_calls?: ToolCall[];
  usage?: { inputTokens: number; outputTokens: number };
};

export type ToolStreamEvent =
  | { type: "token"; text: string }
  | { type: "thinking"; text: string }
  | { type: "done"; response: ModelResponse };

export type GenerationRequest = {
  model: string;
  systemPrompt?: string;
  messages: ChatMessage[];
};

export type GenerationResult = {
  text: string;
  model: string;
};

export type StreamEvent =
  | { type: "response.delta"; text: string }
  | { type: "response.completed" }
  | { type: "response.error"; error: Error };

export type ProviderHealth = {
  ok: boolean;
  provider: ProviderName;
  detail?: string;
};

export interface LLMProvider {
  readonly name: ProviderName;
  generate(input: GenerationRequest): Promise<GenerationResult>;
  stream(input: GenerationRequest): AsyncIterable<StreamEvent>;
  streamWithTools(input: GenerationRequest & { tools: ToolDef[] }): AsyncIterable<ToolStreamEvent>;
  health(): Promise<ProviderHealth>;
  /**
   * Optional cost estimator for custom or self-hosted providers.
   * When present, overrides the built-in pricing table in pricing.ts.
   * Return value is USD.
   */
  estimateCost?(inputTokens: number, outputTokens: number): number;
}
