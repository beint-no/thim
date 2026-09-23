import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("thim.publishing")
}

kotlin {
    jvmToolchain(27)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_26)
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12")
    implementation("org.snakeyaml:snakeyaml-engine:3.1.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":runtime"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
