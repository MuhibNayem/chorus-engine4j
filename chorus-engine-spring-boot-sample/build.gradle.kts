plugins {
    id("java")
    id("application")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters", "--enable-preview"))
    options.release.set(25)
}

application {
    // SampleApplication is the native-image main: it supports both CLI mode (default)
    // and web mode (--spring.profiles.active=web). ClaudeCodeApplication is invoked
    // from SpringCliRunner when not in web mode.
    mainClass.set("com.chorus.engine.sample.SampleApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass.set("com.chorus.engine.sample.SampleApplication")
}

// Lock native image AOT to NONE web type.
// Spring AOT evaluates @ConditionalOnWebApplication once at compile time — it cannot be
// re-evaluated at runtime when switching via --spring.profiles.active. Compiling with
// web-application-type=none excludes Servlet/MVC beans (resourceHandlerMapping, etc.)
// from the native image, preventing "No ServletContext set" crashes in CLI mode.
// Web mode is supported at runtime via JVM deployment (bootRun / bootJar), not native.
tasks.named<JavaExec>("processAot") {
    systemProperty("spring.main.web-application-type", "none")
}

tasks.named("processTestAot") { enabled = false }

dependencies {
    implementation(project(":chorus-engine-spring-boot-starter"))
    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-llm"))
    implementation(project(":chorus-engine-agent"))
    implementation(project(":chorus-engine-tools"))
    implementation(project(":chorus-engine-telemetry"))
    implementation(project(":chorus-engine-memory"))
    implementation(project(":chorus-engine-tokenizer"))
    implementation(project(":chorus-engine-rag"))
    implementation(project(":chorus-engine-graph"))
    implementation(project(":chorus-engine-swarm"))
    implementation(project(":chorus-engine-guardrails"))
    implementation(project(":chorus-engine-skills"))
    implementation(project(":chorus-engine-evals"))
    implementation(project(":chorus-engine-harness"))
    implementation(project(":chorus-engine-mcp"))
    implementation(project(":chorus-engine-a2a"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.0")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("chorus-code")
            sharedLibrary.set(false)
            buildArgs.addAll(
                "--enable-preview",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces"
            )
        }
    }
    testSupport.set(false)
}
