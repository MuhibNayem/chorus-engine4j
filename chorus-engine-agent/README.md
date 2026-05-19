# chorus-engine-agent

Production ReAct agent loop with parallel tool execution, human-in-the-loop gates, and self-healing.

## Purpose

The `agent` module implements the core reasoning agent: an LLM-driven loop that thinks, calls tools, observes results, and iterates until the task is complete. It supports streaming, parallel tool execution, HITL approval, and automatic recovery from transient failures.

## Key APIs

| Class | Purpose |
|---|---|
| `AgentLoop` | The main ReAct loop. Configurable max rounds, timeout, middleware pipeline. Handles tool calls in parallel using virtual threads. |
| `ToolRegistry` | Register and lookup tools by name. Thread-safe. |
| `Tool` | Interface for callable tools: `name()`, `description()`, `parametersSchema()`, `execute(Map<String, Object>)`. |
| `HitlGate` | Human-in-the-loop gate. Pause execution before sensitive tool calls. Supports approve, reject, approve-for-session. |
| `AgentLoopMiddleware` | Interceptor interface for `beforeTool`, `afterTool`, `afterRound` hooks. |

## Agent Loop Flow

```
User Message → LLM → [Thinking] → Tool Call(s) → Parallel Execution
     ↑                                              ↓
     └────────── Observation ←── Tool Result(s) ←───┘
```

The loop continues until:
- The LLM returns a final answer (no tool calls)
- Max rounds reached
- Timeout exceeded
- HITL gate rejects a tool call
- Cancellation token triggered

## Usage Example

```java
import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.agent.loop.AgentLoopConfig;
import com.chorus.engine.tools.ToolRegistry;
import com.chorus.engine.llm.LlmClient;

// Build the loop
AgentLoopConfig config = AgentLoopConfig.builder()
    .maxRounds(10)
    .timeout(Duration.ofMinutes(2))
    .build();

AgentLoop loop = new AgentLoop(llmClient, toolRegistry, eventBus, config);

// Run with HITL
loop.run("Calculate the 50th Fibonacci number", CancellationToken.never())
    .subscribe(new Flow.Subscriber<>() {
        public void onNext(AgentEvent event) {
            switch (event) {
                case AgentEvent.StreamToken t -> System.out.print(t.token());
                case AgentEvent.ToolCallStart t -> System.out.println("Calling: " + t.toolName());
                case AgentEvent.Done d -> System.out.println("\nAnswer: " + d.finalAnswer());
            }
        }
    });
```

## HITL Example

```java
HitlGate gate = HitlGate.builder()
    .sensitiveTools(Set.of("shell", "filesystem_delete", "database_write"))
    .timeout(Duration.ofSeconds(30))
    .build();

// In your approval UI:
gate.approve("gate-id-123", "Approved by admin");
```

## Self-Healing

`SelfHealingAgentLoop` wraps the standard loop with automatic retry on transient failures, exponential backoff, and fallback to simpler prompts when the model gets stuck.

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`
- `chorus-engine-tokenizer`

## Thread Safety

`AgentLoop` is thread-safe. Each `run()` call is independent. Tool execution uses a virtual-thread-per-task model for parallelism.
