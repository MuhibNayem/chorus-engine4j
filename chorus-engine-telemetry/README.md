# chorus-engine-telemetry

Event-driven observability with metrics, cost tracking, provenance, and OpenTelemetry integration.

## Purpose

The `telemetry` module makes agentic systems observable. Every significant event in the agent lifecycle is emitted as an immutable `ChorusEvent` and can be consumed by multiple subscribers: metrics collectors, cost trackers, provenance recorders, and OpenTelemetry bridges.

## Key APIs

| Class | Purpose |
|---|---|
| `EventBus` | Publish-subscribe event bus. Subscribe by event type pattern (`"*"` for all). Thread-safe, non-blocking. |
| `ChorusEvent` | Sealed event hierarchy: `AgentStartEvent`, `AgentEndEvent`, `LlmCallEvent`, `ToolCallEvent`, `RagQueryEvent`, `HandoffEvent`, `GuardrailEvent`, `CheckpointEvent`, `CircuitBreakerEvent`. |
| `MetricsCollector` | Collects latency histograms, throughput counters, and error rates per operation type. |
| `CostTracker` | Tracks cumulative token cost across all LLM calls. Enforces budget limits. |
| `BudgetEnforcer` | Hard stop when token budget is exceeded. Configurable per-run, per-session, or global. |
| `ProvenanceTracker` | Records the complete lineage of every agent decision: which model, which tools, which parameters, in what order. Immutable audit trail. |
| `OpenTelemetryBridge` | Bridges `ChorusEvent` instances to OTel spans and metrics. Requires OpenTelemetry on classpath (compile-only dependency). |
| `StructuredLogger` | JSON-structured logging with configurable verbosity and redaction. |

## Event Bus Usage

```java
import com.chorus.engine.telemetry.event.EventBus;
import com.chorus.engine.telemetry.event.InMemoryEventBus;

EventBus bus = new InMemoryEventBus();

// Subscribe to all events
bus.subscribe("*", event -> {
    System.out.println("[" + event.eventType() + "] " + event.runId());
});

// Subscribe to LLM calls only
bus.subscribe("llm.call", event -> {
    LlmCallEvent e = (LlmCallEvent) event;
    System.out.println("Tokens: " + (e.inputTokens() + e.outputTokens()));
});
```

## Cost Tracking

```java
BudgetEnforcer enforcer = new BudgetEnforcer(
    BudgetLimit.perRun(BigDecimal.valueOf(0.50), Currency.USD),
    eventBus
);

// When budget exceeded, enforcer emits BudgetExceededEvent and
// cancels the active CancellationToken
```

## OpenTelemetry Integration

```java
// Only works if opentelemetry-api is on the classpath
OpenTelemetryBridge bridge = new OpenTelemetryBridge(eventBus, OtelConfig.builder()
    .endpoint("http://localhost:4317")
    .samplingRate(1.0)
    .build());

// Every agent run becomes a trace
// Every tool call becomes a span
// Every LLM call emits metrics
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`
- `chorus-engine-agent`
- `chorus-engine-tools`
- `chorus-engine-rag`
- `chorus-engine-guardrails`
- `chorus-engine-swarm`
- OpenTelemetry API (compile-only)
- SLF4J (compile-only)

## Thread Safety

`EventBus` is lock-free and non-blocking. `MetricsCollector` and `CostTracker` use atomic counters. `ProvenanceTracker` produces immutable audit records.
