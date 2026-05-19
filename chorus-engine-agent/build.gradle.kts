dependencies {
    api(project(":chorus-engine-core"))
    api(project(":chorus-engine-llm"))
    api(project(":chorus-engine-tools"))
    api(project(":chorus-engine-guardrails"))
    api(project(":chorus-engine-memory"))

    api("io.projectreactor:reactor-core")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("jakarta.validation:jakarta.validation-api")

    implementation("org.apache.commons:commons-lang3")
    implementation("io.micrometer:micrometer-core")

    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}
