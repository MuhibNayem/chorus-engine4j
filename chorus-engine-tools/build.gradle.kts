dependencies {
    api(project(":chorus-engine-core"))

    api("io.projectreactor:reactor-core")
    api("com.fasterxml.jackson.core:jackson-databind")

    implementation("org.apache.commons:commons-lang3")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.0.0.202409031743-r")

    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}
