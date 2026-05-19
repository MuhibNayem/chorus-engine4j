dependencies {
    api(project(":chorus-engine-core"))
    api(project(":chorus-engine-agent"))
    api(project(":chorus-engine-graph"))
    api(project(":chorus-engine-llm"))
    api(project(":chorus-engine-telemetry"))

    api("io.projectreactor:reactor-core")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.0.0.202409031743-r")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    implementation("io.micrometer:micrometer-core")

    testImplementation("io.projectreactor:reactor-test")
}
