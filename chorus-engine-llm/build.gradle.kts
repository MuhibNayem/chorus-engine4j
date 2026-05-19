dependencies {
    api(project(":chorus-engine-core"))

    api("io.projectreactor:reactor-core")
    api("org.springframework:spring-webflux")
    api("com.fasterxml.jackson.core:jackson-databind")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("io.micrometer:micrometer-core")
    implementation("org.apache.commons:commons-lang3")

    testImplementation("io.projectreactor:reactor-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
