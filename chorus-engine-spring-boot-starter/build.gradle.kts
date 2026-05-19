plugins {
    id("java-library")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))

    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-llm"))
    implementation(project(":chorus-engine-agent"))
    implementation(project(":chorus-engine-tools"))
    implementation(project(":chorus-engine-rag"))
    implementation(project(":chorus-engine-graph"))
    implementation(project(":chorus-engine-swarm"))
    implementation(project(":chorus-engine-guardrails"))
    implementation(project(":chorus-engine-memory"))
    implementation(project(":chorus-engine-skills"))
    implementation(project(":chorus-engine-evals"))
    implementation(project(":chorus-engine-harness"))
    implementation(project(":chorus-engine-mcp"))
    implementation(project(":chorus-engine-a2a"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.0.0")
}
