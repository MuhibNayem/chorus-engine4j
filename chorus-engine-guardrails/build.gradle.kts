dependencies {
    api(project(":chorus-engine-core"))

    api("io.projectreactor:reactor-core")
    api("com.fasterxml.jackson.core:jackson-databind")

    implementation("org.apache.opennlp:opennlp-tools:2.5.0")
    implementation("io.micrometer:micrometer-core")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    testImplementation("io.projectreactor:reactor-test")
}
