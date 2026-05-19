dependencies {
    implementation(project(":chorus-core"))
    implementation(project(":chorus-checkpoint"))
    implementation(project(":chorus-graph"))
    implementation(project(":chorus-swarm"))
    implementation(project(":chorus-tools"))
    implementation(project(":chorus-harness"))
    implementation(project(":chorus-guardrails"))
    implementation(project(":chorus-telemetry"))
    implementation(project(":chorus-spring-boot-starter"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test:3.7.4")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.ai:spring-ai-ollama")
    testImplementation("org.springframework.ai:spring-ai-client-chat")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
