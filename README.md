# @chorus/engine

Enterprise-grade headless agent execution engine for Chorus. Orchestrates multi-agent swarms, LLM inference loops, human-in-the-loop gates, skill synthesis, and robust error recovery — all without a UI dependency.

```bash
npm install @chorus/engine
```

## Requirements

- Node.js ≥20
- TypeScript 5.7+ (for consumers using TS)
- ESM (`"type": "module"`)

## Quick Start

```typescript
import { createEngine, createSwarm, runAgentLoop } from "@chorus/engine";

// 1. Create a provider
const provider = createProvider("openai", { apiKey: process.env.OPENAI_API_KEY });

// 2. Create a swarm of specialized agents
const swarm = createSwarm({
  agents: [
    { name: "researcher", systemPrompt: "You are a research specialist." },
    { name: "coder", systemPrompt: "You are a code generation specialist." },
  ],
});

// 3. Run the loop
const result = await runAgentLoop({
  provider,
  model: "gpt-4o",
  messages: [{ role: "user", content: "Build a REST API in Express" }],
  tools: [/* ... */],
  swarm,
});

for await (const event of result.stream) {
  if (event.type === "token") process.stdout.write(event.token);
}
```

## Subpath Exports

| Export | Purpose |
|--------|---------|
| `@chorus/engine` | Core engine, swarm, loop, settings |
| `@chorus/engine/agent` | Agent loop, HITL gates, retry logic |
| `@chorus/engine/swarm` | Multi-agent orchestration |
| `@chorus/engine/llm` | LLM provider abstraction |
| `@chorus/engine/tools` | Tool registry, MCP client |
| `@chorus/engine/evals` | Evaluation harness |
| `@chorus/engine/mcp` | Model Context Protocol client |
| `@chorus/engine/harness` | Enterprise task routing & worker execution |

## Enterprise Harness

The harness module provides production-grade task routing and worker execution:

```typescript
import { routeTaskSemantic, executeWorkers } from "@chorus/engine/harness";

// Semantic routing with confidence scores
const route = await routeTaskSemantic({ text: "Debug the auth module" });
console.log(route.kind);      // "debug"
console.log(route.confidence); // 0.92

// Execute workers with iterative refinement
const results = await executeWorkers({
  assignments: swarm.assignWorkers(route),
  taskText: route.text,
  provider,
  model: "gpt-4o",
  executionMode: "pipeline", // discovery → planning → execution
  onEvent: (e) => console.log(e.type),
});
```

See [`docs/HARNESS.md`](docs/HARNESS.md) and [`docs/ROUTER.md`](docs/ROUTER.md) for details.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Chorus Engine                         │
├─────────────┬─────────────┬─────────────┬───────────────────┤
│   Agent     │   Swarm     │   Harness   │     LLM           │
│   Loop      │  Router     │  Workers    │   Provider        │
├─────────────┴─────────────┴─────────────┴───────────────────┤
│  HITL Gates │ Checkpointer│  Telemetry  │  Skill Synthesis  │
│  Retry      │  MCP Tools  │  Evals      │  Semantic Router  │
└─────────────────────────────────────────────────────────────┘
```

- **Agent Loop**: ReAct loop with streaming, tool calling, checkpointing
- **Swarm**: Multi-agent delegation with scope-based ownership
- **Harness**: Semantic task routing + phased worker execution
- **LLM Provider**: Unified interface across OpenAI, Anthropic, local models
- **HITL Gates**: Session-scoped approvals with configurable timeout
- **Skill Synthesis**: Auto-extracts reusable patterns from successful trajectories

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design.

## Development

```bash
# Install dependencies
npm install

# Typecheck
npm run typecheck

# Run tests
npm test

# Build
npm run build
```

## License

MIT
