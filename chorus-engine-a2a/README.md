# chorus-engine-a2a

Agent-to-Agent (A2A) protocol implementation with multi-tenancy and signed Agent Cards.

## Purpose

The `a2a` module implements Google's Agent-to-Agent protocol — a standard for agents to discover and communicate with each other. It supports agent card publication, task delegation, and secure inter-agent communication with multi-tenancy and cryptographic verification.

## Key APIs

| Class | Purpose |
|---|---|
| `A2aClient` | Client for calling remote agents via A2A protocol. Handles task creation, status polling, and artifact retrieval. |
| `A2aServer` | Server that exposes an agent's capabilities via Agent Cards and handles incoming task requests. |
| `A2aHttpHandler` | HTTP request handler for A2A protocol endpoints. Integrates with any HTTP server (Spring Boot, raw JDK HttpServer, etc.). |
| `AgentCard` | Published capability descriptor: agent name, description, skills, endpoints, authentication requirements, and cryptographic signature. |
| `Part` | Sealed message content type: `TextPart`, `FilePart`, `DataPart`. Used in task inputs and outputs. |
| `Task` | A2A task representation with status (`submitted`, `working`, `input-required`, `completed`, `canceled`, `failed`). |
| `Skill` | Declared capability advertised in the Agent Card. |

## Agent Card Example

```json
{
  "name": "Research Assistant",
  "description": "Searches the web and synthesizes findings",
  "url": "https://agents.example.com/research",
  "version": "1.0.0",
  "capabilities": {
    "streaming": true,
    "pushNotifications": false
  },
  "skills": [
    {
      "id": "web-search",
      "name": "Web Search",
      "description": "Search the public web for information",
      "tags": ["search", "web"]
    }
  ],
  "authentication": {
    "schemes": ["Bearer"]
  }
}
```

## Client Usage

```java
import com.chorus.engine.a2a.client.A2aClient;
import com.chorus.engine.a2a.task.Task;

A2aClient client = new A2aClient();

// Discover an agent
AgentCard card = client.fetchAgentCard(URI.create("https://agents.example.com/research/.well-known/agent.json"));

// Send a task
Task task = client.sendTask(card.url(), "What are the latest developments in fusion energy?");

// Poll for completion
while (!task.status().isTerminal()) {
    Thread.sleep(1000);
    task = client.getTask(card.url(), task.id());
}

// Retrieve artifacts
task.artifacts().forEach(a -> System.out.println(a.parts()));
```

## Multi-Tenancy

Agent Cards can include tenant identifiers. The server routes incoming tasks based on tenant context, enabling shared agent infrastructure with isolation.

## Dependencies

- `chorus-engine-core`
- Jackson

## Thread Safety

`A2aClient` is thread-safe. `A2aServer` handles concurrent requests.
