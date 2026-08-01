plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":runtime"))
    api("org.springframework:spring-webmvc:7.0.8")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
