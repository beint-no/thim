plugins {
    `java-gradle-plugin`
    id("thim.publishing")
}

gradlePlugin {
    plugins {
        create("thimSettings") {
            id = "no.beint.thim.settings"
            implementationClass = "no.beint.thim.gradle.ThimSettingsPlugin"
            displayName = "Thim build validation"
            description = "Collect isolated project inputs for build-wide Thim validation"
        }
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    val pluginClasspath = files(tasks.jar, configurations.runtimeClasspath)
    inputs.files(pluginClasspath).withPropertyName("settingsPluginClasspath")
    systemProperty("thim.settingsPluginClasspath", pluginClasspath.asPath)
    useJUnitPlatform()
}
