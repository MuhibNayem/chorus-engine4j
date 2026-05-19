# Chorus Engine Spring Boot Sample

A minimal Spring Boot application demonstrating the Chorus Engine Spring Boot Starter.

## Running

Set your OpenAI API key and run:

```bash
export OPENAI_API_KEY=sk-...
./gradlew :chorus-engine-spring-boot-sample:bootRun
```

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/agent/run` | POST | Run agent with messages |
| `/api/agent/stream` | POST | SSE stream of agent events |
| `/api/agent/health` | GET | Agent health check |
| `/api/rag/query` | POST | RAG query |
| `/api/rag/ingest` | POST | Ingest document |
| `/api/swarm/run` | POST | Run swarm session |
| `/api/swarm/handoff` | POST | Dynamic handoff |
| `/actuator/health` | GET | Spring Boot health |

## Configuration

See `application.yml` for all available Chorus Engine properties.
