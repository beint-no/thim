pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "thim"

include("runtime", "compiler", "spring", "gradle-plugin", "example", "benchmark")


gradle.lifecycle.beforeProject {
    group = "no.beint.thim"
    version = "0.11.0"
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(26))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(26)
        }
    }
}
