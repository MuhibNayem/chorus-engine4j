dependencies {
    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-llm"))
    implementation(project(":chorus-engine-memory"))
    implementation(project(":chorus-engine-agent"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
}
