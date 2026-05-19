package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalDatasetTest {

    @Test
    void fromJsonValid() {
        String json = """
            {
              "name": "test-dataset",
              "cases": [
                {"id": "1", "input": "hello", "expectedOutput": "world", "metadata": {"tag": "a"}},
                {"id": "2", "input": "foo", "expectedOutput": "bar"}
              ]
            }
            """;

        EvalDataset dataset = EvalDataset.fromJson(json);

        assertThat(dataset.name()).isEqualTo("test-dataset");
        assertThat(dataset.cases()).hasSize(2);
        assertThat(dataset.cases().get(0).id()).isEqualTo("1");
        assertThat(dataset.cases().get(0).expectedOutput()).isEqualTo("world");
        assertThat(dataset.cases().get(0).metadata()).containsEntry("tag", "a");
    }

    @Test
    void fromJsonMissingNameThrows() {
        String json = "{\"cases\": []}";
        assertThatThrownBy(() -> EvalDataset.fromJson(json))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Dataset name is required");
    }

    @Test
    void fromFileValid() throws Exception {
        Path temp = Files.createTempFile("dataset", ".json");
        String json = """
            {"name": "file-test", "cases": [{"id": "1", "input": "a", "expectedOutput": "b"}]}
            """;
        Files.writeString(temp, json);

        EvalDataset dataset = EvalDataset.fromFile(temp);

        assertThat(dataset.name()).isEqualTo("file-test");
        assertThat(dataset.cases()).hasSize(1);

        Files.deleteIfExists(temp);
    }

    @Test
    void filterDataset() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "a", "b", Map.of("tag", "easy")),
            new EvalCase("2", "c", "d", Map.of("tag", "hard"))
        ));

        EvalDataset filtered = dataset.filter(c -> "easy".equals(c.metadata().get("tag")));

        assertThat(filtered.cases()).hasSize(1);
        assertThat(filtered.cases().get(0).id()).isEqualTo("1");
    }

    @Test
    void casesAreImmutable() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "a", "b", Map.of())
        ));

        assertThatThrownBy(() -> dataset.cases().add(
            new EvalCase("2", "c", "d", Map.of())
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    // --- Expanded tests ---

    @Test
    void fromJsonInvalidFormatThrows() {
        String json = "{not valid json";
        assertThatThrownBy(() -> EvalDataset.fromJson(json))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to parse dataset JSON");
    }

    @Test
    void fromJsonMissingCasesFieldThrows() {
        String json = "{\"name\": \"test\"}";
        assertThatThrownBy(() -> EvalDataset.fromJson(json))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromJsonNullValuesInsideCaseDtos() {
        String json = """
            {
              "name": "null-test",
              "cases": [
                {"id": null, "input": null, "expectedOutput": null, "metadata": null}
              ]
            }
            """;

        EvalDataset dataset = EvalDataset.fromJson(json);

        assertThat(dataset.cases()).hasSize(1);
        EvalCase c = dataset.cases().get(0);
        assertThat(c.id()).isEmpty();
        assertThat(c.input()).isEmpty();
        assertThat(c.expectedOutput()).isEmpty();
        assertThat(c.metadata()).isEmpty();
    }

    @Test
    void fromJsonEmptyNameStringThrows() {
        String json = "{\"name\": \"   \", \"cases\": []}";
        assertThatThrownBy(() -> EvalDataset.fromJson(json))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Dataset name is required");
    }

    @Test
    void filterReturnsEmptyResult() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "a", "b", Map.of("tag", "easy")),
            new EvalCase("2", "c", "d", Map.of("tag", "hard"))
        ));

        EvalDataset filtered = dataset.filter(c -> "nonexistent".equals(c.metadata().get("tag")));

        assertThat(filtered.cases()).isEmpty();
        assertThat(filtered.name()).isEqualTo("test");
    }
}
