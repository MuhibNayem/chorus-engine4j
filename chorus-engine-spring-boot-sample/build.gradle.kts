plugins {
    id("java")
    id("application")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters", "--enable-preview"))
    options.release.set(25)
}

application {
    mainClass.set("com.chorus.engine.sample.SampleApplication")
}

dependencies {
    implementation(project(":chorus-engine-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("chorus-engine-sample")
            sharedLibrary.set(false)
            buildArgs.addAll(
                "--enable-preview",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces"
            )
        }
    }
    testSupport.set(false)
}
