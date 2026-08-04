plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

dependencies {
    api(project(":runtime"))
    api("org.springframework:spring-webmvc:7.0.8")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:4.1.0")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
}
