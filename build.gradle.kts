import org.gradle.api.publish.PublishingExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
}

allprojects {
    group = "no.beint.thim"
    version = "0.4.0"
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

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/beint-no/thim")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orElse("beint-no")
                            .get()
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orElse(providers.environmentVariable("GH_TOKEN"))
                            .orNull
                    }
                }
            }
        }
    }
}
