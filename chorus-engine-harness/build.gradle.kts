dependencies {
    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-llm"))
    implementation(project(":chorus-engine-agent"))
    implementation(project(":chorus-engine-swarm"))
    implementation(project(":chorus-engine-skills"))
    implementation(project(":chorus-engine-tools"))
    implementation(project(":chorus-engine-memory"))
    implementation(project(":chorus-engine-telemetry"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.0")
}
