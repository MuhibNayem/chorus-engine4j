dependencies {
    implementation(project(":chorus-core"))
    implementation(project(":chorus-graph"))
    implementation(project(":chorus-checkpoint"))
    implementation("io.projectreactor:reactor-core:3.7.4")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("io.projectreactor:reactor-test:3.7.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
