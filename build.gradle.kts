import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "no.beint.thim"
    version = "0.4.10"
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
                            .orElse("")
                            .get()
                    }
                }
            }
        }
    }

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
            publishToMavenCentral()
            signAllPublications()

            when {
                plugins.hasPlugin("java-gradle-plugin") -> this.configure(
                    GradlePlugin(
                        javadocJar = JavadocJar.Empty(),
                        sourcesJar = SourcesJar.Sources(),
                    ),
                )

                plugins.hasPlugin("org.jetbrains.kotlin.jvm") -> this.configure(
                    KotlinJvm(
                        javadocJar = JavadocJar.Empty(),
                        sourcesJar = SourcesJar.Sources(),
                    ),
                )

                plugins.hasPlugin("java-library") -> this.configure(
                    JavaLibrary(
                        javadocJar = JavadocJar.Empty(),
                        sourcesJar = SourcesJar.Sources(),
                    ),
                )
            }

            pom {
                name.set("Thim ${project.name}")
                description.set(
                    when (project.name) {
                        "runtime" -> "Dependency-free Java output API for Thim templates."
                        "compiler" -> "Build-time Java and Kotlin template compiler for Thim."
                        "spring" -> "Spring MVC adapter for Thim templates."
                        "gradle-plugin" -> "Gradle plugin for compiling Thim templates."
                        else -> "Strict AOT HTML renderer for Java and Kotlin applications."
                    },
                )
                inceptionYear.set("2026")
                url.set("https://github.com/beint-no/thim")
                licenses {
                    license {
                        name.set(
                            providers.gradleProperty("thim.pom.license.name")
                                .orElse("Apache-2.0"),
                        )
                        url.set(
                            providers.gradleProperty("thim.pom.license.url")
                                .orElse("https://www.apache.org/licenses/LICENSE-2.0.txt"),
                        )
                    }
                }
                developers {
                    developer {
                        id.set(
                            providers.gradleProperty("thim.pom.developer.id")
                                .orElse("beint-no"),
                        )
                        name.set(
                            providers.gradleProperty("thim.pom.developer.name")
                                .orElse("Beint"),
                        )
                        url.set(
                            providers.gradleProperty("thim.pom.developer.url")
                                .orElse("https://github.com/beint-no"),
                        )
                    }
                }
                scm {
                    url.set("https://github.com/beint-no/thim")
                    connection.set("scm:git:https://github.com/beint-no/thim.git")
                    developerConnection.set("scm:git:ssh://git@github.com/beint-no/thim.git")
                }
            }
        }
    }
}
