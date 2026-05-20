# Chorus Engine — Enterprise Roadmap 2026

> **Research Date:** 2026-05-20
> **Scope:** Critical gaps between Chorus Engine's current architecture and production enterprise standards
> **Methodology:** Market analysis of LangGraph/LangSmith, OpenAI, E2B, Modal, Northflank, Arize, Weaviate, Elasticsearch, Azure Cognitive Search, AWS OpenSearch, and the Kubernetes Agent Sandbox CRD (SIG Apps).

---

## Executive Summary

Chorus Engine is architecturally sound (Java 25, sealed types, Flow backpressure, zero Spring AI dependency) but lacks four enterprise-critical capabilities required for production multi-tenant deployments:

| Gap | Market Standard | Chorus Status | Priority |
|---|---|---|---|
| **Sandboxed Tool Execution** | E2B (Firecracker), Modal (gVisor), Northflank (Kata) | ❌ None | P0 |
| **Observability & Tracing** | OpenTelemetry + LangSmith/Arize Phoenix | ⚠️ Telemetry module exists, no auto-tracing | P0 |
| **Cloud Deployment** | Kubernetes Agent Sandbox CRD, Helm, Operator | ❌ None | P1 |
| **Vector Store Coverage** | Weaviate, Elasticsearch, Azure AI Search, OpenSearch | ⚠️ 5 stores, missing 4 enterprise ones | P1 |

**Recommended sequencing:** Sandbox → Observability → Vector Stores → Cloud Deployment. Each phase is designed to be independent and mergeable without breaking existing APIs.

---

## Phase 1: Sandboxed Tool Execution (P0)

### Market Analysis

| Platform | Isolation | Cold Start | Java Support | License |
|---|---|---|---|---|
| **E2B** | Firecracker microVM | ~200ms | ✅ Java SDK | Apache 2.0 |
| **Modal** | gVisor / Kata | ~500ms | ⚠️ Python-first | Proprietary |
| **Northflank** | Kata/Firecracker/gVisor | ~1s | ✅ Containers | Proprietary |
| **Blaxel** | Firecracker microVM | ~25ms resume | ✅ Containers | Proprietary |

**Key insight:** The Kubernetes community (SIG Apps) is standardizing the **Agent Sandbox CRD** — a declarative API for stateful, isolated agent workloads with SandboxWarmPool for sub-second cold starts. This is the future. Chorus should align with this standard.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    ToolRegistry                              │
│                     (existing)                               │
└────────────────────┬────────────────────────────────────────┘
                     │
        ┌────────────┴────────────┐
        │   SandboxToolExecutor   │  ← NEW: replaces direct execution
        └────────────┬────────────┘
                     │
    ┌────────────────┼────────────────┐
    │                │                │
┌───▼────┐    ┌─────▼──────┐   ┌────▼─────┐
│Process │    │   Docker   │   │Firecracker│
│Sandbox │    │  Sandbox   │   │  Sandbox  │  ← future
└────────┘    └────────────┘   └───────────┘
```

### Implementation Plan

**1.1 Abstract Sandbox Runtime (`chorus-engine-sandbox`) — Week 1**

```java
public sealed interface SandboxRuntime permits ProcessSandboxRuntime, DockerSandboxRuntime {
    CompletableFuture<SandboxResult> execute(SandboxRequest request);
    void close();
}

public record SandboxRequest(
    String toolName,
    String code,           // AI-generated or user-provided
    List<Path> readMounts, // allowed read-only paths
    List<Path> writeMounts,// allowed write paths
    Duration timeout,
    ResourceLimits limits
) {}

public record ResourceLimits(
    long maxMemoryBytes,
    long maxCpuMillis,
    long maxFileWriteBytes,
    int maxNetworkConnections
) {}
```

**1.2 Process Sandbox (seccomp-bpf) — Week 2**
- Uses `ProcessBuilder` with `seccomp` via `libseccomp` JNI or JNA
- Drops all Linux capabilities: `capabilities: drop: ALL`
- Read-only root filesystem + tmpfs `/tmp` with `noexec,nosuid,size=limit`
- Network namespace isolation (optional)
- **Zero external dependencies** — pure JDK + native seccomp

**1.3 Docker Sandbox — Week 3**
- Communicates with Docker daemon via Unix socket
- Runs tools in ephemeral containers with `--read-only --tmpfs /tmp:noexec,nosuid`
- Uses `docker run --network none` for air-gapped execution
- Image pre-pulling and warm pool for <1s cold starts

**1.4 Integration with AgentLoop — Week 4**
- Replace `executeToolsParallel()` placeholder with real `SandboxToolExecutor`
- HITL gates apply BEFORE sandbox execution for sensitive tools
- Telemetry: sandbox execution time, memory peak, exit code, IO volume

**Risk Mitigation:**
- `ProcessSandbox` is default (zero infrastructure)
- `DockerSandbox` is opt-in via `ChorusProperties`
- Firecracker/Kata deferred to Phase 4 (Kubernetes Operator)

---

## Phase 2: Observability & Tracing (P0)

### Market Analysis

| Platform | Standard | Self-Host | Java SDK | Cost Focus |
|---|---|---|---|---|
| **LangSmith** | OTel (2025) | Enterprise only | ✅ | Per-seat |
| **Langfuse** | OTel native | ✅ MIT | ✅ | Usage |
| **Arize Phoenix** | OTel native | ✅ Apache 2.0 | ✅ | Free/oss |
| **Datadog LLM Obs** | Proprietary | ❌ | ✅ | Per-host |

**Key insight:** OpenTelemetry is the universal standard. All major platforms ingest OTel traces. Chorus should emit **native OTel spans** and let users route them to any backend (LangSmith, Langfuse, Datadog, or self-hosted Phoenix).

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   AgentEvent Stream                          │
│              (Flow.Publisher<AgentEvent>)                    │
└────────────────────┬────────────────────────────────────────┘
                     │
        ┌────────────┴────────────┐
        │   OpenTelemetryTracer    │  ← NEW: auto-tracing bridge
        └────────────┬────────────┘
                     │ OTLP / In-Memory
    ┌────────────────┼────────────────┐
    │                │                │
┌───▼────┐    ┌─────▼──────┐   ┌────▼─────┐
|LangSmith│    │  Langfuse  │   │ Datadog  │
└─────────┘    └────────────┘   └──────────┘
```

### Implementation Plan

**2.1 Auto-Tracing Bridge (`chorus-engine-telemetry` extension) — Week 1-2**

Extend the existing `telemetry` module to automatically create OTel spans from `AgentEvent`s:

| AgentEvent | OTel Span | Attributes |
|---|---|---|
| `RoundStart` | `agent.round` | `round_index`, `model` |
| `StreamToken` | `llm.stream_token` | `token_index`, `latency_ms` |
| `ToolCallStart` | `tool.execute` | `tool_name`, `sandbox_type` |
| `ToolCallDone` | `tool.execute` (end) | `duration_ns`, `exit_code` |
| `Error` | `agent.error` | `error_type`, `fatal` |
| `Done` | `agent.run` (end) | `total_rounds`, `total_tokens` |

**2.2 LLM Client Instrumentation — Week 2**
- Wrap all `LlmClient` implementations with `TracingLlmClient` decorator
- Capture: prompt tokens, completion tokens, TTFT (time-to-first-token), model name, provider
- Use `TextMapPropagator` to inject trace context into async streams

**2.3 RAG Pipeline Instrumentation — Week 3**
- `RetrievalStage` → `rag.retrieval` span with `stage_name`, `top_k`, `latency_ms`
- `GenerationController` → `rag.generation` span with `generation_id`, `token_count`
- `ContextAccumulator` → `rag.context` span with `chunk_count`, `total_tokens`

**2.4 Export Configuration — Week 3**
```yaml
# application.yml (ChorusProperties)
chorus:
  telemetry:
    exporter: otlp          # otlp | console | none
    endpoint: http://localhost:4317
    protocol: grpc          # grpc | http/protobuf
    headers:
      x-api-key: ${LANGSMITH_API_KEY}
```

**Risk Mitigation:**
- Tracing is **opt-in** via `@ConditionalOnProperty`
- Zero overhead when disabled (no-op `Tracer`)
- Existing `AgentEvent` consumers unaffected

---

## Phase 3: Production Vector Store Adapters (P1)

### Market Analysis

| Store | Type | Java Client | Managed Offering | Hybrid Search |
|---|---|---|---|---|
| **Weaviate** | Vector-native | ✅ v6 (Dec 2025) | Weaviate Cloud | ✅ BM25 + vector |
| **Elasticsearch** | Search + Vector | ✅ Official | Elastic Cloud | ✅ text + vector |
| **OpenSearch** | Search + Vector | ✅ Official | AWS OpenSearch | ✅ text + vector |
| **Azure AI Search** | Search + Vector | ✅ azure-search-documents | Azure Managed | ✅ semantic + vector |

**Key insight:** Enterprise buyers want "the vector store they already have." Elasticsearch and OpenSearch are already deployed in most enterprises for log search. Adding vector capabilities to existing clusters is cheaper than provisioning a new Pinecone/Milvus.

### Implementation Plan

**3.1 WeaviateVectorStore (`chorus-engine-rag`) — Week 1-2**
- Uses Weaviate Java Client v6 (released Dec 2025)
- Schema auto-creation with `HNSW` index
- Supports hybrid search (BM25 + vector) via `HybridQuery`
- Tenant isolation via Weaviate's native multi-tenancy

**3.2 ElasticsearchVectorStore (`chorus-engine-rag`) — Week 2-3**
- Uses Elasticsearch Java API Client 8.x
- Dense vector field with `cosine` similarity
- Supports `knn` search + `text` query hybrid
- Index templates for automatic mapping

**3.3 OpenSearchVectorStore (`chorus-engine-rag`) — Week 3-4**
- Uses OpenSearch Java Client 2.x
- Nearly identical API to Elasticsearch but separate client
- AWS SigV4 authentication support for managed OpenSearch

**3.4 AzureCognitiveSearchVectorStore (`chorus-engine-rag`) — Week 4-5**
- Uses `azure-search-documents` SDK
- Semantic ranker + vector search hybrid
- Supports managed identity authentication

**Interface Compliance:**
All implementations follow the existing `VectorStore` sealed interface:
```java
public sealed interface VectorStore permits
    PgVectorStore, PineconeVectorStore, QdrantVectorStore,
    MilvusVectorStore, InMemoryVectorStore, TenantAwareVectorStore,
    WeaviateVectorStore, ElasticsearchVectorStore,
    OpenSearchVectorStore, AzureCognitiveSearchVectorStore {}
```

---

## Phase 4: Managed Cloud Deployment (P1)

### Market Analysis

| Platform | Approach | Stateful | Isolation | Chorus Fit |
|---|---|---|---|---|
| **LangGraph Platform** | Managed SaaS | ✅ Checkpointing | Shared tenant | ❌ Closed source |
| **Kubernetes Agent Sandbox** | CRD + WarmPool | ✅ Sandbox CRD | gVisor/Kata | ✅ Open standard |
| **AWS Bedrock AgentCore** | Managed runtime | ✅ | VPC | ❌ AWS-only |
| **Google Vertex AI** | Agent Engine | ✅ | gVisor | ❌ GCP-only |

**Key insight:** Kubernetes is the universal deployment target for enterprises. The SIG Apps Agent Sandbox CRD (March 2026) provides exactly what Chorus needs: stateful sandbox lifecycle, warm pools for sub-second startup, and stable network identity for multi-agent communication.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  Kubernetes Cluster                          │
│                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  ChorusAgent │  │  ChorusAgent │  │    SandboxWarmPool   │ │
│  │   (Stateful) │  │   (Stateful) │  │   (Agent Sandbox)   │ │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘ │
│         │                │                     │            │
│         └────────────────┴─────────────────────┘            │
│                          │                                   │
│                   ┌──────▼──────┐                            │
│                   │  Chorus CRD  │  ← Agent, Sandbox, Tool   │
│                   │  Controller  │    definitions             │
│                   └─────────────┘                            │
└─────────────────────────────────────────────────────────────┘
```

### Implementation Plan

**4.1 Helm Chart — Week 1-2**
- StatefulSet for `AgentLoop` instances (1 per agent definition)
- PersistentVolumeClaim for checkpoint storage
- ConfigMap for `ChorusProperties`
- Secret for LLM API keys, GPG signing keys
- Optional: Redis for distributed `EmbeddingRegistry`

**4.2 Chorus Operator CRDs — Week 3-5**

```yaml
apiVersion: chorus.dev/v1
kind: ChorusAgent
metadata:
  name: customer-support-agent
spec:
  agentId: support-bot
  systemPromptRef:
    configMapName: support-prompts
    key: system.txt
  model: gpt-4o
  maxRounds: 10
  tools:
    - name: search_kb
      sandbox: process
    - name: create_ticket
      sandbox: none
  checkpointStore:
    type: postgres
    connectionSecretRef: pg-credentials
  telemetry:
    exporter: otlp
    endpoint: http://otel-collector:4317
  replicas: 3
  resources:
    requests:
      memory: "512Mi"
      cpu: "500m"
    limits:
      memory: "2Gi"
      cpu: "2000m"
```

**4.3 SandboxWarmPool Integration — Week 5-6**
- Align with Kubernetes Agent Sandbox CRD (SIG Apps)
- Pre-warm `ProcessSandbox` and `DockerSandbox` instances
- Sub-second handoff on tool execution requests

**4.4 Multi-Agent Service Mesh — Week 7-8**
- A2A protocol over mTLS between agent pods
- Service discovery via Kubernetes DNS
- Circuit breaker + retry at the mesh level (Istio/Linkerd optional)

---

## Integration Matrix

| Feature | Core | LLM | Agent | RAG | Memory | Sandbox | Telemetry | K8s |
|---|---|---|---|---|---|---|---|---|
| **Sandbox** | — | — | ✅ | — | — | core | — | — |
| **OTel Tracing** | — | ✅ | ✅ | ✅ | ✅ | ✅ | core | — |
| **Vector Stores** | — | — | — | ✅ | — | — | — | — |
| **K8s Operator** | — | — | ✅ | — | ✅ | ✅ | ✅ | core |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Java 25 preview APIs change | Medium | High | Pin to specific GraalVM release; abstract preview APIs behind interfaces |
| seccomp JNI complexity | Medium | Medium | Start with `ProcessBuilder` + `SecurityManager` fallback; defer seccomp to v2 |
| OTel dependency bloat | Low | Medium | Use `opentelemetry-api` only (no SDK in library); user brings SDK |
| Weaviate Java v6 breaking changes | Low | Low | Version-range dependency; integration tests catch breakage |
| K8s Agent Sandbox CRD churn | Medium | Low | Design operator to be CRD-agnostic; support both native and Sandbox CRD |

---

## Success Criteria

- [ ] Sandbox: `shell_exec` tool runs in isolated process with no host filesystem access
- [ ] Observability: Full agent run trace visible in LangSmith/Langfuse with <5% overhead
- [ ] Vector Stores: All 4 new stores pass the same integration test suite as PgVectorStore
- [ ] Cloud: `helm install chorus-engine ./chart` deploys a working 3-replica agent

---

## References

1. [Kubernetes Agent Sandbox CRD — SIG Apps, March 2026](https://kubernetes.io/blog/2026/03/20/running-agents-on-kubernetes-with-agent-sandbox/)
2. [E2B Open-Source Sandbox — Firecracker microVMs](https://e2b.dev)
3. [Modal Sandboxes — gVisor + GPU](https://modal.com)
4. [LangSmith OpenTelemetry Support — March 2025](https://docs.smith.langchain.com/otel)
5. [Arize Phoenix — Open-Source LLM Observability](https://phoenix.arize.com)
6. [Weaviate Java Client v6 — Dec 2025](https://weaviate.io/developers/weaviate/client-libraries/java)
7. [Elasticsearch Java API Client 8.x](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html)
8. [Azure AI Search — Vector Search GA](https://learn.microsoft.com/azure/search/vector-search-overview)
9. [OpenTelemetry for GenAI — OpenLLMetry](https://github.com/traceloop/openllmetry)
10. [Northflank Agent Execution — MicroVM Isolation](https://northflank.com/blog/code-execution-environment-for-autonomous-agents)
