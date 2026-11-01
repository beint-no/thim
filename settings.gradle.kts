pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
    val kspVersion = providers.gradleProperty("kspVersion").get()
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.spring") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "thim"

include("runtime", "compiler", "spring", "gradle-plugin", "settings-plugin", "example", "benchmark")


gradle.lifecycle.beforeProject {
    group = "no.beint.thim"
    version = "0.13.3"
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(27))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(27)
        }
    }
}
