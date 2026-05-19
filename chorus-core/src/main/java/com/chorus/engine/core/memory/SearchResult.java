package com.chorus.engine.core.memory;

import java.util.Map;

public record SearchResult(String id, String content, double score, Map<String, Object> metadata) {
}
