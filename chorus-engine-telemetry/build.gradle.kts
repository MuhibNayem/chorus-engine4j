dependencies {
    api(project(":chorus-engine-core"))

    api("io.opentelemetry:opentelemetry-api")
    api("io.opentelemetry:opentelemetry-sdk")
    api("io.opentelemetry:opentelemetry-sdk-trace")
    api("io.opentelemetry:opentelemetry-exporter-otlp")
    api("io.opentelemetry:opentelemetry-extension-trace-propagators")
    api("io.projectreactor:reactor-core")

    implementation("io.opentelemetry:opentelemetry-sdk-metrics")
    implementation("io.opentelemetry:opentelemetry-sdk-logs")
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.28.0-alpha")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    testImplementation("io.projectreactor:reactor-test")
}
