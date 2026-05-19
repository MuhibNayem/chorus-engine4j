dependencies {
    implementation(project(":chorus-core"))
    implementation("io.opentelemetry:opentelemetry-api:1.49.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.49.0")
    implementation("io.opentelemetry:opentelemetry-semconv:1.30.0-alpha")
    implementation("io.micrometer:micrometer-core:1.14.6")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.6")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
}
