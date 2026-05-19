# chorus-engine-core

The foundational module of Chorus Engine. Provides the shared vocabulary, concurrency primitives, and utility types used by every other module.

## Purpose

`core` is the only module with **zero external runtime dependencies**. It defines the immutable data structures and reactive primitives that all other modules agree upon. If you use any part of Chorus Engine, you are implicitly using `core`.

## Key APIs

| Class / Interface | Purpose |
|---|---|
| `AgentEvent` | Sealed event hierarchy for every significant moment in an agent's lifecycle. 20+ record types covering tokens, tool calls, checkpoints, HITL, memory, guardrails, handoffs, and completion. |
| `Result<T, E>` | Railway-oriented programming result type. `ok(value)` / `err(error)` with `map`, `flatMap`, `filter`, `unwrapOrElse`. Immutable and null-safe. |
| `Message` | Immutable chat message with `Role` (`SYSTEM`, `USER`, `ASSISTANT`, `TOOL`). Records with compact canonical constructors. |
| `CancellationToken` | Cooperative cancellation primitive. Check `isCancelled()` in long-running loops; propagate via `throwIfCancelled()`. |
| `FlowCollector` | Bridges JDK `Flow.Publisher<T>` to synchronous APIs (`toList`, `last`, `first`). Used by every streaming consumer in the framework. |
| `VectorOperations` | Pluggable vector math abstraction with three implementations: Vector API (SIMD), FMA, and pure scalar. Auto-detected at startup. |

## Usage Example

```java
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.core.context.Message;

// Railway-oriented error handling
Result<String, String> parsed = Result.ok("42");
Result<Integer, String> number = parsed.map(Integer::parseInt);

// Build a conversation
Message system = Message.system("You are a helpful assistant.");
Message user = Message.user("What is the capital of France?");

// Consume agent events
AgentEvent event = new AgentEvent.StreamToken(
    "run-123", Instant.now(), "Paris", 0, null
);
```

## Thread Safety

All types in `core` are immutable and thread-safe by design. `CancellationToken` uses atomic state; `FlowCollector` is safe for concurrent subscribers.

## Dependencies

None. This module is pure JDK 25.
