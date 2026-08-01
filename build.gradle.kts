plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
}

allprojects {
    group = "no.beint.thim"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(26))
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(26)
        }
    }
}
