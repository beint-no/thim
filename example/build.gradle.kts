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
    implementation(kotlin("reflect"))
    implementation(project(":spring"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc:4.1.0")
    ksp(project(":compiler"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

ksp {
    arg("thim.templates", layout.projectDirectory.dir("src/main/resources/templates").asFile.absolutePath)
    arg("thim.messages", layout.projectDirectory.dir("src/main/resources/i18n").asFile.absolutePath)
    arg("thim.defaultLocale", "en")
    arg("thim.supportedLocales", "en,nb")
    arg("thim.package", "no.beint.thim.example.generated")
    arg("thim.registry", "ExampleTemplates")
    arg("thim.generateRoutes", "true")
    arg("thim.modelPackages", "no.beint.thim.example.page")
    arg("thim.strictTemplates", "true")
    arg("thim.failOnUnusedMessages", "true")
    arg("thim.failOnUnusedFragments", "true")
    arg("thim.strictModels", "true")
}

tasks.withType<KspAATask>().configureEach {
    inputs.files(fileTree(layout.projectDirectory.dir("src/main/resources/templates")) {
        include("**/*.html")
    })
        .withPropertyName("thimTemplates")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(layout.projectDirectory.dir("src/main/resources/i18n")) {
        include("**/*.yaml")
    })
        .withPropertyName("thimMessages")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
