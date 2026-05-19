package com.chorus.engine.evals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports evaluation reports to various formats.
 */
public final class EvalReportExporter {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public void exportJson(@NonNull EvalReport report, @NonNull Path path) throws IOException {
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }

    public @NonNull String exportJunitXml(@NonNull EvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuites name=\"").append(escapeXml(report.datasetName())).append("\" ");
        sb.append("tests=\"").append(report.totalCases()).append("\" ");
        sb.append("failures=\"").append(report.totalCases() - report.passed()).append("\" ");
        sb.append("time=\"").append(report.duration().toMillis() / 1000.0).append("\">\n");
        sb.append("  <testsuite name=\"eval\" tests=\"").append(report.totalCases()).append("\" ");
        sb.append("failures=\"").append(report.totalCases() - report.passed()).append("\" ");
        sb.append("time=\"").append(report.duration().toMillis() / 1000.0).append("\">\n");

        for (EvalResult result : report.results()) {
            sb.append("    <testcase name=\"").append(escapeXml(result.caseId())).append("\" ");
            sb.append("time=\"0\">\n");
            if (!result.passed()) {
                sb.append("      <failure message=\"Score: ").append(result.score()).append("\">\n");
                sb.append("        <![CDATA[\n");
                sb.append("Actual: ").append(result.actualOutput()).append("\n");
                if (result.reasoning() != null) {
                    sb.append("Reasoning: ").append(result.reasoning()).append("\n");
                }
                sb.append("        ]]>\n");
                sb.append("      </failure>\n");
            }
            sb.append("    </testcase>\n");
        }

        sb.append("  </testsuite>\n");
        sb.append("</testsuites>\n");
        return sb.toString();
    }

    public @NonNull String exportMarkdown(@NonNull EvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Evaluation Report: ").append(report.datasetName()).append("\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Total Cases | ").append(report.totalCases()).append(" |\n");
        sb.append("| Passed | ").append(report.passed()).append(" |\n");
        sb.append("| Pass Rate | ").append(String.format("%.1f%%", report.passRate() * 100)).append(" |\n");
        sb.append("| Avg Score | ").append(String.format("%.3f", report.avgScore())).append(" |\n");
        sb.append("| Duration | ").append(report.duration()).append(" |\n\n");
        sb.append("## Results\n\n");
        sb.append("| Case | Pass | Score | Output |\n");
        sb.append("|------|------|-------|--------|\n");
        for (EvalResult r : report.results()) {
            sb.append("| ").append(r.caseId()).append(" | ");
            sb.append(r.passed() ? "PASS" : "FAIL").append(" | ");
            sb.append(String.format("%.3f", r.score())).append(" | ");
            String output = r.actualOutput().length() > 50
                ? r.actualOutput().substring(0, 50) + "..."
                : r.actualOutput();
            sb.append(output.replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private @NonNull String escapeXml(@NonNull String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
