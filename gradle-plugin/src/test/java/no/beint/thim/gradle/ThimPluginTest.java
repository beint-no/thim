package no.beint.thim.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void validationDefaultsAreStrictAndCodeGenerationRemainsOptIn() throws IOException {
        var project = dependencyProject("strict-defaults", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                tasks.register('writeThimDefaults') {
                    doLast {
                        file('thim-defaults.txt').text = [
                            thim.strictTemplates.get(),
                            thim.strictModels.get(),
                            thim.failOnUnusedMessages.get(),
                            thim.failOnUnusedFragments.get(),
                            thim.validateRoutes.get(),
                            thim.generateMessages.get(),
                            thim.generateRoutes.get()
                        ].join('\\n')
                    }
                }
                """);

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("writeThimDefaults", "--stacktrace")
                .withPluginClasspath()
                .build();

        assertEquals(
                List.of("true", "true", "true", "true", "true", "false", "false"),
                Files.readAllLines(project.resolve("thim-defaults.txt"))
        );
    }

    @Test
    void migrationModulesKeepRuntimeTemplatesButNotCompiledCatalogs() throws IOException {
        var project = project("migration", "thim { strictTemplates.set(false) }");

        runProcessResources(project);

        assertTrue(Files.exists(project.resolve("build/resources/main/templates/home.html")));
        assertFalse(Files.exists(project.resolve("build/resources/main/i18n/en/home.yaml")));
        assertTrue(Files.exists(project.resolve("build/resources/main/public.txt")));
    }

    @Test
    void plainJavaProjectsReceiveOnlyTheRuntime() throws IOException {
        var project = dependencyProject("plain-java", """
                id 'java'
                id 'no.beint.thim'
                """);

        assertEquals(List.of("runtime"), thimDependencies(project));
    }

    @Test
    void springBootBeforeThimAddsTheSpringAdapter() throws IOException {
        var project = dependencyProject("boot-before-thim", """
                id 'java'
                id 'org.springframework.boot' version '4.1.0'
                id 'no.beint.thim'
                """);

        assertEquals(List.of("runtime", "spring"), thimDependencies(project));
    }

    @Test
    void springBootAfterThimAddsTheSpringAdapter() throws IOException {
        var project = dependencyProject("boot-after-thim", """
                id 'java'
                id 'no.beint.thim'
                id 'org.springframework.boot' version '4.1.0'
                """);

        assertEquals(List.of("runtime", "spring"), thimDependencies(project));
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

    private Path dependencyProject(String name, String plugins) throws IOException {
        var project = temporaryDirectory.resolve(name);
        write(project.resolve("settings.gradle"), "rootProject.name = '" + name + "'\n");
        write(project.resolve("build.gradle"), """
                plugins {
                %s
                }

                repositories {
                    mavenCentral()
                }

                tasks.matching { it.name == 'compileThim' }.configureEach {
                    enabled = false
                }

                tasks.register('writeThimDependencies') {
                    doLast {
                        def names = configurations.implementation.dependencies
                            .findAll { it.group == 'no.beint.thim' }
                            .collect { it.name }
                            .sort()
                        file('thim-dependencies.txt').text = names.join('\\n')
                    }
                }
                """.formatted(plugins.indent(4)));
        return project;
    }

    private List<String> thimDependencies(Path project) throws IOException {
        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("writeThimDependencies", "--stacktrace")
                .withPluginClasspath()
                .build();
        return Files.readAllLines(project.resolve("thim-dependencies.txt"));
    }

    private void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
}
