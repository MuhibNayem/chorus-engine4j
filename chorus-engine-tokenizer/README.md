# chorus-engine-tokenizer

Token counting for 50+ LLM models across all major providers.

## Purpose

Accurate token counting is essential for context window management, cost estimation, and RAG chunking. This module provides pluggable tokenizers with lazy loading and caching.

## Key APIs

| Class | Purpose |
|---|---|
| `Tokenizer` | Interface: `countTokens(String text)`, `countTokens(String text, String model)` |
| `TokenizerRegistry` | Thread-safe registry mapping model names to tokenizers. Pre-configured for 50+ models. Falls back to approximate tokenization for unknown models. |
| `BpeTokenizer` | Byte-pair encoding tokenizer loaded from `.tiktoken` files (OpenAI, Llama, DeepSeek, Qwen). |
| `ApproximateTokenizer` | Fast approximate tokenization for models without official BPE vocabularies (Claude, Gemini, Grok, Cohere, etc.). |

## Supported Models

| Provider | Models | Tokenizer |
|---|---|---|
| OpenAI | gpt-4o, gpt-4o-mini, gpt-4-turbo, o1, o3 | `o200k_base`, `cl100k_base` |
| Anthropic | claude-3-opus, claude-3-sonnet, claude-3-haiku | Approximate |
| Google | gemini-1.5-pro, gemini-1.5-flash | Approximate |
| Meta | llama-3, llama-3.1 | `llama-3` BPE |
| Mistral | mistral-7b, mixtral-8x7b, mistral-large | `mistral` BPE |
| DeepSeek | deepseek-chat, deepseek-coder, deepseek-reasoner | `deepseek` BPE |
| Qwen | qwen-2, qwen-2.5, qwen-max | `qwen` BPE |

## Usage Example

```java
import com.chorus.engine.tokenizer.TokenizerRegistry;
import com.chorus.engine.tokenizer.Tokenizer;

TokenizerRegistry registry = new TokenizerRegistry();
Tokenizer tokenizer = registry.forModel("gpt-4o");

int tokens = tokenizer.countTokens("The quick brown fox jumps over the lazy dog.");
System.out.println("Tokens: " + tokens);
```

## Resource Files

BPE tokenizers load `.tiktoken` files from the classpath:
- `tokenizers/cl100k_base.tiktoken`
- `tokenizers/o200k_base.tiktoken`
- `tokenizers/llama-3.tiktoken`
- `tokenizers/deepseek.tiktoken`
- `tokenizers/qwen.tiktoken`

## Dependencies

- `chorus-engine-core`

## Thread Safety

`TokenizerRegistry` uses `ConcurrentHashMap` for lazy caching. Safe to share across threads.
