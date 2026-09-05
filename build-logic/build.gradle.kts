plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.37.0")
}

kotlin {
    jvmToolchain(26)
}
