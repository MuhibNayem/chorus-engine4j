plugins {
    java
    `java-library`
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.springframework.boot") version "4.0.0" apply false
}

allprojects {
    group = "com.chorus"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
        withJavadocJar()
    }

    dependencies {
        // Null safety annotations only — zero runtime dependency
        compileOnly("org.jspecify:jspecify:1.0.0")

        // Testing
        testImplementation(platform("org.junit:junit-bom:5.11.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core:3.26.3")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf(
            "-parameters",
            "--enable-preview"
        ))
        options.release.set(25)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
        maxHeapSize = "2g"
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
    }
}
