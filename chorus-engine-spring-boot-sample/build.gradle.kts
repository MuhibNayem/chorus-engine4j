plugins {
    id("java")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":chorus-engine-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
