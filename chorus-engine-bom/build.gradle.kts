plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

dependencies {
    constraints {
        api(project(":chorus-engine-core"))
        api(project(":chorus-engine-tokenizer"))
        api(project(":chorus-engine-llm"))
        api(project(":chorus-engine-agent"))
        api(project(":chorus-engine-graph"))
        api(project(":chorus-engine-swarm"))
        api(project(":chorus-engine-harness"))
        api(project(":chorus-engine-tools"))
        api(project(":chorus-engine-guardrails"))
        api(project(":chorus-engine-skills"))
        api(project(":chorus-engine-telemetry"))
        api(project(":chorus-engine-mcp"))
        api(project(":chorus-engine-a2a"))
        api(project(":chorus-engine-evals"))
        api(project(":chorus-engine-memory"))
        api(project(":chorus-engine-rag"))
        api(project(":chorus-engine-spring-boot-starter"))
    }
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
        name.set("Chorus Engine — BOM")
        description.set("Bill of Materials for Chorus Engine — Java-native agentic AI framework")
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
