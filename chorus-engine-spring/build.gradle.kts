dependencies {
    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-agent"))
    implementation(project(":chorus-engine-graph"))
    implementation(project(":chorus-engine-swarm"))
    implementation(project(":chorus-engine-llm"))
    implementation(project(":chorus-engine-tools"))
    implementation(project(":chorus-engine-guardrails"))
    implementation(project(":chorus-engine-memory"))
    implementation(project(":chorus-engine-skills"))
    implementation(project(":chorus-engine-telemetry"))
    implementation(project(":chorus-engine-mcp"))
    implementation(project(":chorus-engine-a2a"))

    // Spring Boot is ONLY in this module
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:4.0.0")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:4.0.0")
}
