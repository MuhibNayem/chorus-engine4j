dependencies {
    api(project(":chorus-engine-core"))
    api(project(":chorus-engine-llm"))

    api("io.projectreactor:reactor-core")
    api("com.fasterxml.jackson.core:jackson-databind")

    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    implementation("ai.djl:api:0.30.0")
    implementation("ai.djl.huggingface:tokenizers:0.30.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("io.micrometer:micrometer-core")

    testImplementation("io.projectreactor:reactor-test")
}
