package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportExporterTest {

    EvalReportExporter exporter = new EvalReportExporter();

    EvalReport sampleReport() {
        return new EvalReport("test-suite", 2, 1, 0.5, 0.75,
            Duration.ofSeconds(5),
            List.of(
                new EvalResult("c1", true, 1.0, "correct", null),
                new EvalResult("c2", false, 0.5, "wrong", "partial match")
            ));
    }

    @Test
    void exportJson(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("report.json");
        exporter.exportJson(sampleReport(), path);
        assertThat(path).exists();
        String content = java.nio.file.Files.readString(path);
        assertThat(content).contains("test-suite");
        assertThat(content).contains("c1");
    }

    @Test
    void exportJunitXml() {
        String xml = exporter.exportJunitXml(sampleReport());
        assertThat(xml).contains("<?xml version=");
        assertThat(xml).contains("test-suite");
        assertThat(xml).contains("tests=\"2\"");
        assertThat(xml).contains("failures=\"1\"");
        assertThat(xml).contains("<failure");
        assertThat(xml).contains("c2");
    }

    @Test
    void exportMarkdown() {
        String md = exporter.exportMarkdown(sampleReport());
        assertThat(md).contains("# Evaluation Report: test-suite");
        assertThat(md).contains("| Total Cases | 2 |");
        assertThat(md).contains("PASS");
        assertThat(md).contains("FAIL");
    }
}
