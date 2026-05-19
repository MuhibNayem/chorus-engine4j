# chorus-engine-skills

Semantic skill discovery, routing, and dynamic loading.

## Purpose

The `skills` module enables agents to discover and invoke capabilities dynamically. A "skill" is a reusable agent configuration (system prompt + tool set + parameters) that can be loaded from JSON, indexed by semantic embedding, and routed to at runtime based on task similarity.

## Key APIs

| Class | Purpose |
|---|---|
| `Skill` | Immutable record representing a skill: id, name, description, systemPrompt, toolNames, config, tags. |
| `SkillRegistry` | In-memory registry of loaded skills. Lookup by id, name, or tag. |
| `SkillRouter` | Routes incoming tasks to the most relevant skill using embedding-based semantic similarity. |
| `SemanticSkillIndex` | Builds and queries a vector index of skill descriptions for fast nearest-neighbor lookup. |
| `SkillLoader` | Loads skills from directories, classpath, or remote URLs. Supports `.json` skill definition files. |

## Skill JSON Format

```json
{
  "id": "web-researcher",
  "name": "Web Researcher",
  "description": "Searches the web and synthesizes findings into structured reports",
  "systemPrompt": "You are a meticulous research assistant...",
  "toolNames": ["web_search", "summarize"],
  "config": {
    "maxSearchResults": 10,
    "citationStyle": "apa"
  },
  "tags": ["research", "web", "summary"]
}
```

## Usage Example

```java
import com.chorus.engine.skills.*;

// Load skills
SkillLoader loader = new SkillLoader();
List<Skill> skills = loader.loadFromDirectory(Path.of("skills/"));

// Build registry and router
SkillRegistry registry = new SkillRegistry(skills);
SemanticSkillIndex index = new SemanticSkillIndex(embeddingClient, skills);
SkillRouter router = new SkillRouter(index, registry);

// Route a task
Optional<Skill> match = router.route("Find recent papers on transformer architectures");
match.ifPresent(skill -> {
    System.out.println("Using skill: " + skill.name());
    // Configure agent with skill.systemPrompt() and skill.toolNames()
});
```

## Dynamic Skill Loading

Skills can be loaded at runtime from:
- Local directory: `loader.loadFromDirectory(Path.of("skills"))`
- Classpath: `loader.loadFromClasspath("skills/web-researcher.json")`
- Remote URL: `loader.loadFromUrl("https://example.com/skills/researcher.json")`

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`
- `chorus-engine-agent`
- `chorus-engine-tools`
- `chorus-engine-rag`
- Jackson

## Thread Safety

`SkillRegistry` and `SemanticSkillIndex` are thread-safe after construction. `SkillLoader` is stateless.
