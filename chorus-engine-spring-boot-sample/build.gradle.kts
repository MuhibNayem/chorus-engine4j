plugins {
    id("java")
    id("application")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native")
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
            buildArgs.add("--enable-preview")
            buildArgs.add("--no-fallback")
        }
    }
}
