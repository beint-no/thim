plugins {
    `java-library`
    id("thim.publishing")
}

dependencies {
    api(project(":runtime"))
    api("org.springframework:spring-webmvc:7.0.9")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:4.1.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework:spring-test:7.0.9")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
