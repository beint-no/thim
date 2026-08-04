import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.tasks.PathSensitivity

plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.4.10"
    id("com.google.devtools.ksp")
    id("org.springframework.boot")
}

kotlin {
    jvmToolchain(26)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}
tasks.withType<KotlinJvmCompile>().configureEach {
    jvmTargetValidationMode.set(JvmTargetValidationMode.IGNORE)
}

dependencies {
    implementation(project(":spring"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc:4.1.0")
    ksp(project(":compiler"))
}

ksp {
    arg("thim.templates", layout.projectDirectory.dir("src/main/resources/templates").asFile.absolutePath)
    arg("thim.messages", layout.projectDirectory.dir("src/main/resources").asFile.absolutePath)
    arg("thim.package", "no.beint.thim.example.generated")
    arg("thim.registry", "ExampleTemplates")
    arg("thim.modelPackages", "no.beint.thim.example.page")
    arg("thim.strictTemplates", "true")
    arg("thim.failOnUnusedMessages", "true")
}

tasks.withType<KspAATask>().configureEach {
    inputs.files(fileTree(layout.projectDirectory.dir("src/main/resources/templates")) {
        include("**/*.html")
    })
        .withPropertyName("thimTemplates")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(layout.projectDirectory.dir("src/main/resources")) {
        include("**/*.properties")
    })
        .withPropertyName("thimMessages")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
