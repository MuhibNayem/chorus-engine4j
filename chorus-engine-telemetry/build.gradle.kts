dependencies {
    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-llm"))
    implementation(project(":chorus-engine-agent"))
    implementation(project(":chorus-engine-tools"))
    implementation(project(":chorus-engine-rag"))
    implementation(project(":chorus-engine-guardrails"))
    implementation(project(":chorus-engine-swarm"))

    // OpenTelemetry is optional at runtime
    compileOnly("io.opentelemetry:opentelemetry-api:1.43.0")
    compileOnly("io.opentelemetry:opentelemetry-sdk:1.43.0")
    compileOnly("io.opentelemetry:opentelemetry-exporter-otlp:1.43.0")

    // SLF4J optional for structured logging integration
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    testImplementation("io.opentelemetry:opentelemetry-api:1.43.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.43.0")
    testImplementation("io.opentelemetry:opentelemetry-exporter-otlp:1.43.0")
}
