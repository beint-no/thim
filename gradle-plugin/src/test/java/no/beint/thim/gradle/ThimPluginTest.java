package no.beint.thim.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThimPluginTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void strictModulesDoNotPackageCompilerInputs() throws IOException {
        var project = project("strict", "");

        runProcessResources(project);

        assertFalse(Files.exists(project.resolve("build/resources/main/templates/home.html")));
        assertFalse(Files.exists(project.resolve("build/resources/main/i18n/en/home.yaml")));
        assertTrue(Files.exists(project.resolve("build/resources/main/public.txt")));
    }

    @Test
    void migrationModulesKeepRuntimeTemplatesButNotCompiledCatalogs() throws IOException {
        var project = project("migration", "thim { strictTemplates.set(false) }");

        runProcessResources(project);

        assertTrue(Files.exists(project.resolve("build/resources/main/templates/home.html")));
        assertFalse(Files.exists(project.resolve("build/resources/main/i18n/en/home.yaml")));
        assertTrue(Files.exists(project.resolve("build/resources/main/public.txt")));
    }

    private Path project(String name, String thimConfiguration) throws IOException {
        var project = temporaryDirectory.resolve(name);
        write(project.resolve("settings.gradle"), "rootProject.name = '" + name + "'\n");
        write(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'no.beint.thim'
                }

                repositories {
                    mavenCentral()
                }

                %s

                tasks.matching { it.name == 'compileThim' }.configureEach {
                    enabled = false
                }
                """.formatted(thimConfiguration));
        write(project.resolve("src/main/resources/templates/home.html"), "<html></html>\n");
        write(project.resolve("src/main/resources/i18n/en/home.yaml"), "title: Home\n");
        write(project.resolve("src/main/resources/public.txt"), "public\n");
        return project;
    }

    private void runProcessResources(Path project) {
        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("processResources", "--stacktrace")
                .withPluginClasspath()
                .build();
    }

    private void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
}
