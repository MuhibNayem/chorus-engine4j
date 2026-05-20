package com.chorus.engine.springboot.tool;

import org.jspecify.annotations.NonNull;

import java.lang.reflect.Type;

/**
 * Describes how a single method parameter is bound from the JSON args map
 * that the LLM populates.
 *
 * @param name        JSON property name
 * @param type        Java type of the parameter
 * @param required    whether the LLM must provide it
 * @param specialKind whether this is a framework-reserved parameter
 *                    ({@code CancellationToken} or raw args map)
 */
public record ParamBinding(
    @NonNull String name,
    @NonNull Type type,
    boolean required,
    @NonNull SpecialKind specialKind
) {

    public enum SpecialKind {
        /** Normal parameter bound from the JSON args map. */
        ARG,
        /** Receives the raw {@code Map<String, Object>} args. */
        RAW_ARGS,
        /** Receives the {@code CancellationToken}. */
        CANCELLATION_TOKEN
    }

    public boolean isSpecial() {
        return specialKind != SpecialKind.ARG;
    }
}
