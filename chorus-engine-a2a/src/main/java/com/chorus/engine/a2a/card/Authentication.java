package com.chorus.engine.a2a.card;

import org.jspecify.annotations.NonNull;

import java.util.List;

public record Authentication(@NonNull List<String> schemes) {
    public Authentication {
        schemes = List.copyOf(schemes);
    }
}
