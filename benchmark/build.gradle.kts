import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    id("me.champeau.jmh")
}

kotlin {
    jvmToolchain(26)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_26)
}

dependencies {
    implementation(project(":runtime"))
    ksp(project(":compiler"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "standardOut", "standardError")
    }
}

ksp {
    arg("thim.templates", layout.projectDirectory.dir("src/main/resources/templates").asFile.absolutePath)
    arg("thim.messages", layout.projectDirectory.dir("src/main/resources/i18n").asFile.absolutePath)
    arg("thim.defaultLocale", "en")
    arg("thim.supportedLocales", "en,nb")
    arg("thim.package", "no.beint.thim.benchmark.generated")
    arg("thim.registry", "BenchmarkTemplates")
    arg("thim.generateMessages", "true")
    arg("thim.messagesName", "BenchmarkMessages")
    arg("thim.generateRoutes", "false")
    arg("thim.validateRoutes", "false")
    arg("thim.modelPackages", "no.beint.thim.benchmark.page")
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

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    timeOnIteration.set("1s")
    warmup.set("1s")
}
