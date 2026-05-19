# chorus-engine-llm

LLM client abstraction with streaming, tool calling, retries, circuit breakers, and embedding support.

## Purpose

The `llm` module is the gateway to every large language model provider. It normalizes provider-specific APIs (OpenAI, Anthropic, Gemini, vLLM, Ollama) into a single `LlmClient` interface with unified `ChatRequest` / `ChatResponse` types.

## Key APIs

| Class / Interface | Purpose |
|---|---|
| `LlmClient` | Core interface: `complete(ChatRequest, CancellationToken)` and `stream(ChatRequest, CancellationToken)` returning `Flow.Publisher<StreamEvent>`. |
| `ChatRequest` | Immutable request with model, messages, tools, temperature, maxTokens, responseFormat, providerExtras. |
| `ChatResponse` | Immutable response with content, toolCalls, token counts, finish reason, reasoning content. |
| `StreamEvent` | Sealed streaming event: `Token`, `ToolCallStart`, `ToolCallDelta`, `ToolCallDone`, `Finish`, `Error`. |
| `ToolDefinition` | Schema-driven tool definition with JSON Schema parameters. |
| `ResponseFormat` | Structured output: `TEXT`, `JSON_OBJECT`, `JSON_SCHEMA`. |
| `ProviderRegistry` | Factory for pre-configured providers. `ProviderRegistry.defaults(...)` wires OpenAI, Anthropic, Gemini, vLLM. |
| `RetryPolicy` | Configurable exponential backoff with jitter. |
| `CircuitBreaker` | Fail-fast protection with half-open recovery. |
| `EmbeddingClient` | Interface for text embedding with `embed(List<String>)`. |
| `OpenAiEmbeddingClient` | OpenAI-compatible embedding implementation. |

## Supported Providers

| Provider | Class | Streaming | Tool Calling |
|---|---|---|---|
| OpenAI / Azure | `OpenAiProvider` | ✅ | ✅ |
| Anthropic | `AnthropicProvider` | ✅ | ✅ |
| Google Gemini | `GeminiProvider` | ✅ | ✅ |
| vLLM / Ollama | `VllmChatProvider` | ✅ | ✅ |

## Usage Example

```java
import com.chorus.engine.llm.*;
import com.chorus.engine.llm.provider.ProviderRegistry;
import com.chorus.engine.core.context.Message;

// Build a request
ChatRequest request = ChatRequest.builder()
    .model("gpt-4o")
    .message(Message.system("You are a coding assistant."))
    .message(Message.user("Write a Java record for a Point."))
    .temperature(0.7)
    .maxTokens(500)
    .build();

// Get a client
ProviderRegistry registry = ProviderRegistry.defaults(httpClient, objectMapper, retryPolicy, circuitBreaker);
LlmClient client = registry.get("openai");

// Non-streaming
ChatResponse response = client.complete(request, CancellationToken.never());
System.out.println(response.message().content());

// Streaming
client.stream(request, CancellationToken.never()).subscribe(new Flow.Subscriber<>() {
    public void onNext(StreamEvent event) {
        switch (event) {
            case StreamEvent.Token t -> System.out.print(t.token());
            case StreamEvent.ToolCallStart t -> System.out.println("Tool: " + t.toolName());
            case StreamEvent.Finish f -> System.out.println("\nDone. Tokens: " + f.promptTokens() + "/" + f.completionTokens());
        }
    }
    // ... onError, onComplete
});
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-tokenizer`
- Jackson (databind, core)

## Thread Safety

`LlmClient` implementations are thread-safe and can be shared. `ProviderRegistry` is immutable after construction.
