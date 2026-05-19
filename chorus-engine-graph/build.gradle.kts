dependencies {
    api(project(":chorus-engine-core"))
    api(project(":chorus-engine-agent"))
    api(project(":chorus-engine-llm"))

    api("io.projectreactor:reactor-core")
    api("org.springframework:spring-webflux")
    api("com.fasterxml.jackson.core:jackson-databind")

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.postgresql:postgresql")
    implementation("io.r2dbc:r2dbc-spi")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")

    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
