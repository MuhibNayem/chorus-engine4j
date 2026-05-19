package com.chorus.engine.core.a2a;

import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card — discovery document published at /.well-known/agent-card.json
 * Describes an agent's capabilities, authentication, and endpoint.
 */
public record AgentCard(
    String name,
    String description,
    String url,
    String version,
    List<Capability> capabilities,
    Authentication authentication,
    Map<String, Object> metadata
) {
    public record Capability(String type, String description, List<String> modalities) {}
    public record Authentication(String type, String issuer, List<String> scopes) {}
}
