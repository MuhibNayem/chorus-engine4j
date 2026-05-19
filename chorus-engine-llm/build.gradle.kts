dependencies {
    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-tokenizer"))

    // JSON parsing — only external dep in LLM module
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.0")
}
