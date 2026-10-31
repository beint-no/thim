plugins {
    `java-gradle-plugin`
    id("thim.publishing")
}

dependencies {
    implementation(project(":settings-plugin"))
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${providers.gradleProperty("kspVersion").get()}")
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("thim") {
            id = "no.beint.thim"
            implementationClass = "no.beint.thim.gradle.ThimPlugin"
            displayName = "Thim compiler"
            description = "Compile typed HTML templates into direct Java renderers"
        }
    }
}

val toolVersions = tasks.register<WriteProperties>("toolVersions") {
    destinationFile.set(layout.buildDirectory.file("generated/tool-versions.properties"))
    property("kotlin", providers.gradleProperty("kotlinVersion"))
    property("ksp", providers.gradleProperty("kspVersion"))
}

tasks.processResources {
    from(toolVersions) {
        into("no/beint/thim/gradle")
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}
