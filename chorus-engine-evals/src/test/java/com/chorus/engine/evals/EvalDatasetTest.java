package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

        assertEquals("test-dataset", dataset.name());
        assertEquals(2, dataset.cases().size());
        assertEquals("1", dataset.cases().get(0).id());
        assertEquals("world", dataset.cases().get(0).expectedOutput());
        assertEquals("a", dataset.cases().get(0).metadata().get("tag"));
    }

    @Test
    void fromJsonMissingNameThrows() {
        String json = "{\"cases\": []}";
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.fromJson(json));
    }

    @Test
    void fromFileValid() throws Exception {
        Path temp = Files.createTempFile("dataset", ".json");
        String json = """
            {"name": "file-test", "cases": [{"id": "1", "input": "a", "expectedOutput": "b"}]}
            """;
        Files.writeString(temp, json);

        EvalDataset dataset = EvalDataset.fromFile(temp);

        assertEquals("file-test", dataset.name());
        assertEquals(1, dataset.cases().size());

        Files.deleteIfExists(temp);
    }

    @Test
    void filterDataset() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "a", "b", Map.of("tag", "easy")),
            new EvalCase("2", "c", "d", Map.of("tag", "hard"))
        ));

        EvalDataset filtered = dataset.filter(c -> "easy".equals(c.metadata().get("tag")));

        assertEquals(1, filtered.cases().size());
        assertEquals("1", filtered.cases().get(0).id());
    }

    @Test
    void casesAreImmutable() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "a", "b", Map.of())
        ));

        assertThrows(UnsupportedOperationException.class, () -> dataset.cases().add(
            new EvalCase("2", "c", "d", Map.of())
        ));
    }
}
