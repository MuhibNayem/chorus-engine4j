package com.chorus.engine.core.event;

import java.util.Map;

public record HitlRequest(
    String id,
    String name,
    Map<String, Object> args,
    String description
) {}
