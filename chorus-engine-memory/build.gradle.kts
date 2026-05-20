
plugins {
    id("com.vanniktech.maven.publish")
}

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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = project.name,
        version = rootProject.version.toString()
    )

    pom {
        name.set("Chorus Engine — ${project.name}")
        description.set("Java-native agentic AI framework")
        inceptionYear.set("2025")
        url.set("https://github.com/MuhibNayem/chorus-engine4j")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("muhibnayem")
                name.set("Muhib Nayem")
                url.set("https://github.com/MuhibNayem")
            }
        }

        scm {
            url.set("https://github.com/MuhibNayem/chorus-engine4j")
            connection.set("scm:git:git://github.com/MuhibNayem/chorus-engine4j.git")
            developerConnection.set("scm:git:ssh://git@github.com:MuhibNayem/chorus-engine4j.git")
        }
    }
}
