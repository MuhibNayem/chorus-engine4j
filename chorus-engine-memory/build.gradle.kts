dependencies {
    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-tokenizer"))
    implementation(project(":chorus-engine-llm"))

    // JSON serialization
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.0")

    // JDBC connection pooling
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Redis client
    implementation("redis.clients:jedis:5.2.0")

    // Testing
    testImplementation("com.h2database:h2:2.3.232")
}
