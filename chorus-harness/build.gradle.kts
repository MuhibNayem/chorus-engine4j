dependencies {
    implementation(project(":chorus-core"))
    implementation("org.springframework.ai:spring-ai-model")
    implementation("io.projectreactor:reactor-core:3.7.4")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("io.projectreactor:reactor-test:3.7.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
