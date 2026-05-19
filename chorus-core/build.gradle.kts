dependencies {
    implementation("io.projectreactor:reactor-core:3.7.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.springframework.ai:spring-ai-commons")
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-vector-store")
    implementation("org.springframework:spring-web:6.2.5")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    testImplementation("io.projectreactor:reactor-test:3.7.4")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
