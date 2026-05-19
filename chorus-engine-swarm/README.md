# chorus-engine-swarm

Multi-agent orchestration with handoff, consensus, supervisor patterns, and cost-aware routing.

## Purpose

The `swarm` module implements multi-agent systems where specialized agents collaborate to solve complex tasks. It supports four orchestration paradigms: handoff (OpenAI-style), consensus (voting), supervisor (hierarchical), and planner-executor (task decomposition).

## Key APIs

| Class | Purpose |
|---|---|
| `HandoffOrchestrator` | OpenAI-style handoff pattern. Agents dynamically transfer control to the most qualified agent for each subtask. |
| `AgentConsensusEngine` | Multiple agents vote on the same task; the majority or highest-confidence answer wins. |
| `SupervisorOrchestrator` | Hierarchical pattern. A supervisor agent delegates to worker agents and synthesizes results. Uses `StructuredTaskScope` for parallel execution. |
| `PlannerExecutorOrchestrator` | Task decomposition: planner breaks the goal into subtasks, executor agents run them in parallel or pipeline. |
| `SwarmConfig` | Configuration for max agents, cost budget, timeout, consensus threshold. |
| `AgentDefinition` | Blueprint for an agent: name, system prompt, model, tools, capabilities. |
| `SwarmEvent` | Sealed event hierarchy for swarm lifecycle: `AgentSpawned`, `HandoffOccurred`, `ConsensusReached`, `BudgetExceeded`. |

## Orchestration Patterns

| Pattern | Use Case | Parallelism |
|---|---|---|
| **Handoff** | Dynamic routing (customer support → billing → technical) | Sequential |
| **Consensus** | High-stakes decisions (medical diagnosis, legal review) | Parallel voting |
| **Supervisor** | Complex projects with clear phases (research → write → review) | Parallel workers |
| **Planner-Executor** | Task decomposition (build a feature: plan → code → test → deploy) | DAG-based |

## Usage Example

```java
import com.chorus.engine.swarm.*;

// Define agents
AgentDefinition researcher = AgentDefinition.builder()
    .name("researcher")
    .systemPrompt("You are a research assistant. Find facts and cite sources.")
    .model("gpt-4o")
    .tools(Set.of("web_search", "calculator"))
    .build();

AgentDefinition writer = AgentDefinition.builder()
    .name("writer")
    .systemPrompt("You are a technical writer. Synthesize research into clear prose.")
    .model("gpt-4o")
    .build();

// Handoff orchestration
HandoffOrchestrator orchestrator = new HandoffOrchestrator(
    List.of(researcher, writer),
    llmClient,
    toolRegistry
);

orchestrator.run("Write a report on quantum computing advances in 2025", token)
    .subscribe(event -> {
        switch (event) {
            case SwarmEvent.HandoffOccurred h ->
                System.out.println(h.fromAgent() + " → " + h.toAgent());
            case SwarmEvent.AgentSpawned s ->
                System.out.println("Spawned: " + s.agentName());
        }
    });
```

## Cost-Aware Routing

The swarm tracks cumulative token cost across all agents. When the budget is exceeded, subsequent requests are routed to cheaper models or the operation is halted.

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`
- `chorus-engine-agent`
- `chorus-engine-tools`

## Thread Safety

Orchestrators are thread-safe. Each `run()` invocation is independent. Parallel execution uses Java virtual threads via `StructuredTaskScope`.
