dependencies {
    implementation(project(":chorus-core"))
    implementation("org.springframework:spring-context:6.2.5")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.springframework.ai:spring-ai-mcp")
    implementation("org.springframework.ai:spring-ai-model")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
