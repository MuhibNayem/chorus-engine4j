package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepoIntelligenceDetectorTest {

    @Test
    void detectGradleJavaProject(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("build.gradle.kts"), "plugins { java }");
        Files.writeString(temp.resolve("README.md"), "# Project");
        Files.createDirectories(temp.resolve("src/main/java/com/example"));
        Files.writeString(temp.resolve("src/main/java/com/example/Main.java"), "class Main {}");

        RepoIntelligence intel = new RepoIntelligenceDetector(temp).detect();
        assertThat(intel.languages()).contains("Java", "Kotlin");
        assertThat(intel.packageManager()).isEqualTo("gradle");
        assertThat(intel.importantFiles()).contains("README.md", "build.gradle.kts");
        assertThat(intel.sourceFileCount()).isGreaterThanOrEqualTo(1);
        assertThat(intel.summary()).contains("Java", "source files");
    }

    @Test
    void detectNodeProject(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("package.json"), "{\"scripts\":{\"test\":\"jest\",\"build\":\"tsc\"}}");
        Files.writeString(temp.resolve("package-lock.json"), "{}");
        Files.writeString(temp.resolve("tsconfig.json"), "{}");

        RepoIntelligence intel = new RepoIntelligenceDetector(temp).detect();
        assertThat(intel.languages()).contains("TypeScript", "JavaScript");
        assertThat(intel.packageManager()).isEqualTo("npm");
        assertThat(intel.commands()).contains("npm run test", "npm run build");
    }

    @Test
    void detectEmptyDirectory(@TempDir Path temp) {
        RepoIntelligence intel = new RepoIntelligenceDetector(temp).detect();
        assertThat(intel.languages()).isEmpty();
        assertThat(intel.packageManager()).isNull();
        assertThat(intel.sourceFileCount()).isEqualTo(0);
        assertThat(intel.summary()).contains("unknown");
    }

    @Test
    void detectsTestSignals(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        RepoIntelligence intel = new RepoIntelligenceDetector(temp).detect();
        assertThat(intel.testSignals()).contains("junit");
    }
}
