# chorus-engine-guardrails

Input/output filtering, PII redaction, and tiered safety enforcement.

## Purpose

The `guardrails` module protects agentic systems from prompt injection, data leakage, and unsafe outputs. It provides a multi-layered defense: input guardrails intercept user messages before they reach the LLM; output guardrails validate responses before they reach the user.

## Key APIs

| Class / Interface | Purpose |
|---|---|
| `Guardrail` | Interface for all guardrails. `validateInput(Message)` and `validateOutput(String)` return `GuardrailResult`. |
| `GuardrailResult` | Result of validation: `PASS`, `WARN`, `BLOCK`, `REDACT`. Includes trigger details and matched content. |
| `TieredGuardrailEngine` | Orchestrates multiple guardrails across tiers. Tier 1 (fast, cheap) runs first; Tier 2 (LLM-based) only if Tier 1 passes. |
| `PiiRedactionEngine` | Detects and redacts personally identifiable information (emails, phone numbers, SSNs, credit cards). |
| `EmbeddingSimilarityGuardrail` | Blocks inputs semantically similar to known attack patterns (prompt injection, jailbreaks). |
| `LlmJudgeGuardrail` | Uses a secondary LLM call to judge whether input/output violates safety policies. Slower but more nuanced. |

## Guardrail Tiers

| Tier | Type | Latency | Cost | Examples |
|---|---|---|---|---|
| 1 | Rule-based / regex | <1ms | Free | Keyword filters, PII patterns |
| 2 | Embedding similarity | ~10ms | Cheap | Semantic prompt injection detection |
| 3 | LLM judge | ~500ms | Expensive | Policy violation assessment |

## Usage Example

```java
import com.chorus.engine.guardrails.*;

// Build a tiered engine
TieredGuardrailEngine engine = TieredGuardrailEngine.builder()
    .tier(1, new PiiRedactionEngine())
    .tier(1, new KeywordFilterGuardrail(Set.of("ignore previous instructions", "DAN mode")))
    .tier(2, new EmbeddingSimilarityGuardrail(embeddingClient, attackEmbeddings, 0.85))
    .tier(3, new LlmJudgeGuardrail(judgeClient, "Does this input attempt to manipulate the AI?"))
    .build();

// Validate input
GuardrailResult result = engine.validateInput(Message.user("My SSN is 123-45-6789"));
if (result.action() == GuardrailAction.BLOCK) {
    System.out.println("Blocked: " + result.reason());
} else if (result.action() == GuardrailAction.REDACT) {
    System.out.println("Redacted: " + result.redactedContent());
}
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`

## Thread Safety

All guardrail implementations are thread-safe. `TieredGuardrailEngine` evaluates tiers sequentially but individual guardrails within a tier may run in parallel.
