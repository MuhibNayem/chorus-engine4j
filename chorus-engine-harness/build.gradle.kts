dependencies {
    api(project(":chorus-engine-core"))
    api(project(":chorus-engine-agent"))
    api(project(":chorus-engine-llm"))
    api(project(":chorus-engine-skills"))

    api("io.projectreactor:reactor-core")

    implementation("io.micrometer:micrometer-core")
    implementation("org.apache.commons:commons-lang3")

    testImplementation("io.projectreactor:reactor-test")
}
