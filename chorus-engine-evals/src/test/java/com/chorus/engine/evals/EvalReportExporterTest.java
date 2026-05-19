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

    // --- Expanded tests ---

    @Test
    void exportJunitXmlEscapesSpecialCharacters() {
        EvalReport report = new EvalReport("suite & test <run>", 1, 0, 0.0, 0.0,
            Duration.ZERO,
            List.of(
                new EvalResult("case \"1\"", false, 0.0, "out & about", "it's > 0")
            ));

        String xml = exporter.exportJunitXml(report);

        assertThat(xml).contains("suite &amp; test &lt;run&gt;");
        assertThat(xml).contains("case &quot;1&quot;");
        assertThat(xml).contains("Actual: out & about");
        assertThat(xml).doesNotContain("suite & test <run>");
        assertThat(xml).doesNotContain("case \"1\"");
    }

    @Test
    void exportJunitXmlEscapesApostrophe() {
        EvalReport report = new EvalReport("it's", 1, 1, 1.0, 1.0,
            Duration.ZERO,
            List.of(new EvalResult("c1", true, 1.0, "ok", null)));

        String xml = exporter.exportJunitXml(report);

        assertThat(xml).contains("it&apos;s");
    }

    @Test
    void exportMarkdownTruncatesLongActualOutput() {
        String longOutput = "a".repeat(60);
        EvalReport report = new EvalReport("trunc-test", 1, 1, 1.0, 1.0,
            Duration.ZERO,
            List.of(new EvalResult("c1", true, 1.0, longOutput, null)));

        String md = exporter.exportMarkdown(report);

        assertThat(md).contains("a".repeat(50) + "...");
        assertThat(md).doesNotContain(longOutput);
    }

    @Test
    void exportMarkdownEscapesPipeInOutput() {
        EvalReport report = new EvalReport("pipe-test", 1, 1, 1.0, 1.0,
            Duration.ZERO,
            List.of(new EvalResult("c1", true, 1.0, "a|b|c", null)));

        String md = exporter.exportMarkdown(report);

        assertThat(md).contains("a\\|b\\|c");
    }

    @Test
    void exportEmptyReport() {
        EvalReport emptyReport = new EvalReport("empty", 0, 0, 0.0, 0.0,
            Duration.ZERO, List.of());

        String xml = exporter.exportJunitXml(emptyReport);
        assertThat(xml).contains("tests=\"0\"");
        assertThat(xml).contains("failures=\"0\"");
        assertThat(xml).doesNotContain("<failure");

        String md = exporter.exportMarkdown(emptyReport);
        assertThat(md).contains("| Total Cases | 0 |");
        assertThat(md).contains("| Passed | 0 |");
    }

    @Test
    void exportJsonWithNullReasoning(@TempDir Path temp) throws Exception {
        EvalReport report = new EvalReport("null-reason", 1, 1, 1.0, 1.0,
            Duration.ZERO,
            List.of(new EvalResult("c1", true, 1.0, "ok", null)));

        Path path = temp.resolve("report.json");
        exporter.exportJson(report, path);
        String content = java.nio.file.Files.readString(path);

        assertThat(content).contains("null-reason");
        assertThat(content).contains("c1");
        assertThat(content).contains("\"reasoning\" : null");
    }
}
