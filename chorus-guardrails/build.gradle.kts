dependencies {
    implementation(project(":chorus-core"))
    implementation("org.springframework:spring-context:6.2.5")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
