package com.chorus.engine.evals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Collection of evaluation test cases.
 */
public final class EvalDataset {

    private final String name;
    private final List<EvalCase> cases;

    private EvalDataset(@NonNull String name, @NonNull List<EvalCase> cases) {
        this.name = name;
        this.cases = List.copyOf(cases);
    }

    public static @NonNull EvalDataset of(@NonNull String name, @NonNull List<EvalCase> cases) {
        return new EvalDataset(name, cases);
    }

    public @NonNull String name() {
        return name;
    }

    public @NonNull List<EvalCase> cases() {
        return cases;
    }

    public @NonNull EvalDataset filter(@NonNull Predicate<EvalCase> predicate) {
        return new EvalDataset(name, cases.stream().filter(predicate).toList());
    }

    /**
     * Load an EvalDataset from a JSON string.
     * Expected format:
     * <pre>{@code
     * {
     *   "name": "my-dataset",
     *   "cases": [
     *     {"id": "1", "input": "...", "expectedOutput": "...", "metadata": {...}}
     *   ]
     * }
     * }</pre>
     */
    public static @NonNull EvalDataset fromJson(@NonNull String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            DatasetDto dto = mapper.readValue(json, DatasetDto.class);
            if (dto.name == null || dto.name.isBlank()) {
                throw new IllegalArgumentException("Dataset name is required");
            }
            List<EvalCase> cases = dto.cases.stream()
                .map(c -> new EvalCase(
                    c.id != null ? c.id : "",
                    c.input != null ? c.input : "",
                    c.expectedOutput != null ? c.expectedOutput : "",
                    c.metadata != null ? c.metadata : java.util.Map.of()
                ))
                .collect(Collectors.toList());
            return new EvalDataset(dto.name, cases);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse dataset JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Load an EvalDataset from a JSON file.
     */
    public static @NonNull EvalDataset fromFile(@NonNull Path path) {
        try {
            return fromJson(Files.readString(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read dataset file: " + path, e);
        }
    }

    // Internal DTOs for Jackson deserialization
    private static class DatasetDto {
        public String name;
        public List<CaseDto> cases;
    }

    private static class CaseDto {
        public String id;
        public String input;
        public String expectedOutput;
        public java.util.Map<String, Object> metadata;
    }
}
