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

include("runtime", "compiler", "spring", "gradle-plugin", "settings-plugin", "example", "benchmark")


gradle.lifecycle.beforeProject {
    group = "no.beint.thim"
    version = "0.12.1"
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(27))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(27)
        }
    }
}
