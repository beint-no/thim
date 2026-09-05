package no.beint.thim.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThimSettingsPluginTest {
    @TempDir
    Path project;

    @Test
    void kotlinConsumersCanCompileWithSeparatePluginClasspaths() throws IOException {
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.configuration-cache=true\n");
        var classpath = Arrays.stream(System.getProperty("thim.settingsPluginClasspath").split(File.pathSeparator))
                .map(path -> "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", "));
        Files.writeString(project.resolve("settings.gradle.kts"), """
                pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
                buildscript { dependencies { classpath(files(%s)) } }
                apply(plugin = "no.beint.thim.settings")
                rootProject.name = "separate-plugin-classpaths"
                include("consumer")
                """.formatted(classpath));
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { kotlin("jvm") version "2.4.10" apply false }
                """);
        Files.createDirectories(project.resolve("consumer/src/main/kotlin"));
        Files.writeString(project.resolve("consumer/build.gradle.kts"), """
                plugins { kotlin("jvm") }
                repositories { mavenCentral() }
                """);
        Files.writeString(project.resolve("consumer/src/main/kotlin/Consumer.kt"), "class Consumer(val message: String)\n");
        var runner = GradleRunner.create().withProjectDir(project.toFile())
                .withArguments(":consumer:classes", "thimMessageUsageCheck", "--isolated-projects", "--stacktrace");

        assertTrue(runner.build().getOutput().contains("BUILD SUCCESSFUL"));
        assertTrue(runner.build().getOutput().contains("Reusing configuration cache"));
        assertTrue(Files.isRegularFile(project.resolve("consumer/build/classes/kotlin/main/Consumer.class")));
    }
}
