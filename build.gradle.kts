plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("me.champeau.jmh") version "0.7.3" apply false
}

tasks.register("printReleaseVersion") {
    val releaseVersion = version.toString()
    doLast { println(releaseVersion) }
}
