package com.chorus.engine.core.skills;

import java.util.List;
import java.util.Map;

public record Skill(
    String name,
    String description,
    String promptTemplate,
    List<String> exampleUtterances,
    Map<String, String> parameters
) {
}
