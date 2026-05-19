dependencies {
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.addons:reactor-extra")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api("org.slf4j:slf4j-api")
    api("jakarta.validation:jakarta.validation-api")

    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.awaitility:awaitility")
}
