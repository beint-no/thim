plugins {
    `java-gradle-plugin`
    `maven-publish`
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.10")
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

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}
