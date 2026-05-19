dependencies {
    implementation(project(":chorus-core"))
    implementation(project(":chorus-checkpoint"))
    implementation(project(":chorus-graph"))
    implementation(project(":chorus-swarm"))
    implementation(project(":chorus-tools"))
    implementation(project(":chorus-harness"))
    implementation(project(":chorus-guardrails"))
    implementation(project(":chorus-telemetry"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.ai:spring-ai-commons")
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-vector-store")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}
