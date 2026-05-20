
plugins {
    id("com.vanniktech.maven.publish")
    id("java-library")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))

    implementation(project(":chorus-engine-core"))
    implementation(project(":chorus-engine-llm"))
    implementation(project(":chorus-engine-agent"))
    implementation(project(":chorus-engine-tools"))
    implementation(project(":chorus-engine-rag"))
    implementation(project(":chorus-engine-graph"))
    implementation(project(":chorus-engine-swarm"))
    implementation(project(":chorus-engine-guardrails"))
    implementation(project(":chorus-engine-memory"))
    implementation(project(":chorus-engine-skills"))
    implementation(project(":chorus-engine-evals"))
    implementation(project(":chorus-engine-harness"))
    implementation(project(":chorus-engine-mcp"))
    implementation(project(":chorus-engine-a2a"))
    implementation(project(":chorus-engine-telemetry"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.0")
    testImplementation("org.springframework.boot:spring-boot-starter-web:4.0.0")
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
