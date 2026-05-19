dependencies {
    implementation(project(":chorus-core"))
    implementation("org.springframework:spring-context:6.2.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.3")
    implementation("org.slf4j:slf4j-api:2.0.16")

    compileOnly("org.postgresql:postgresql")
    compileOnly("redis.clients:jedis:5.2.0")
    compileOnly("com.zaxxer:HikariCP:6.2.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.postgresql:postgresql")
    testImplementation("redis.clients:jedis:5.2.0")
    testImplementation("com.zaxxer:HikariCP:6.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
