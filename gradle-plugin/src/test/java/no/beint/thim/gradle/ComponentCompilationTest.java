package no.beint.thim.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ComponentCompilationTest {
    @TempDir Path directory;

    static Stream<Arguments> invalidComponents() {
        return Stream.of(
            Arguments.of("wrong-props", "<button th:component=\"ui:button\" th:props=\"sample.ButtonProps\" th:text=\"${label}\"/>", "<ui:button th:props=\"${title}\"/>", "THIM-COMPONENT-PROPS-TYPE"),
            Arguments.of("nullable-props", "<button th:component=\"ui:button\" th:props=\"sample.ButtonProps\" th:text=\"${label}\"/>", "<ui:button th:props=\"${nullableButton}\"/>", "THIM-COMPONENT-PROPS-TYPE"),
            Arguments.of("definition-scope", "<button th:component=\"ui:button\" th:props=\"sample.ButtonProps\" th:text=\"${title}\"/>", "<ui:button th:props=\"${button}\"/>", "THIM-PROPERTY-UNKNOWN"),
            Arguments.of("caller-scope", "<button th:component=\"ui:button\" th:props=\"sample.ButtonProps\"><th:block th:slot=\"default\"/></button>", "<ui:button th:props=\"${button}\"><span th:text=\"${label}\"/></ui:button>", "THIM-PROPERTY-UNKNOWN"),
            Arguments.of("no-props-leak", "<button th:component=\"ui:button\" th:text=\"${title}\"/>", "<ui:button/>", "THIM-COMPONENT-SCOPE"),
            Arguments.of("boolean-type", "<button th:component=\"ui:button\" th:props=\"sample.ButtonProps\" th:disabled=\"${label}\"/>", "<ui:button th:props=\"${button}\"/>", "THIM-BOOLEAN"),
            Arguments.of("unsafe-url", "<a th:component=\"ui:button\" th:props=\"sample.ButtonProps\" th:href=\"${label}\"/>", "<ui:button th:props=\"${button}\"/>", "THIM-URL"),
            Arguments.of("unsafe-html", "<div th:component=\"ui:button\" th:props=\"sample.ButtonProps\" th:utext=\"${label}\"/>", "<ui:button th:props=\"${button}\"/>", "THIM-RAW-HTML-TYPE"),
            Arguments.of("missing-props-class", "<button th:component=\"ui:button\" th:props=\"sample.Missing\"/>", "<ui:button th:props=\"${button}\"/>", "THIM-COMPONENT-PROPS-TYPE")
        );
    }

    @ParameterizedTest @MethodSource("invalidComponents")
    void invalidCompositionFailsTheApplicationBuild(String name, String definition, String call, String diagnostic) throws Exception {
        var project = fixture(name, definition, call);
        var result = runner(project).buildAndFail();
        assertTrue(result.getOutput().contains(diagnostic), result.getOutput());
        assertTrue(result.getOutput().contains("home.html:" ) || result.getOutput().contains("ui/components.html:"), result.getOutput());
    }

    @Test void javaRecordPropsCompileAndRenderWithoutAComponentRuntime() throws Exception {
        var project = fixture("valid", "<button th:component=\"ui:button\" th:props=\"sample.ButtonProps\" th:disabled=\"${disabled}\"><span th:text=\"${label}\"/><th:block th:slot=\"default\"/></button>",
                "<ui:button th:props=\"${button}\"><span th:text=\"${title}\"/></ui:button>");
        runner(project).withArguments("compileJava", "--stacktrace").build();
        try (var files = Files.walk(project.resolve("build/generated/thim/main/java"))) {
            var renderer = files.filter(p -> p.toString().endsWith("Part" + Math.floorMod("sample.HomePage".hashCode(), 32) + ".java")).findFirst().orElseThrow();
            var code = Files.readString(renderer);
            assertTrue(code.contains("model.button()"), code);
            assertTrue(code.contains("model.title()"), code);
            assertFalse(code.contains("ComponentBinding"), code);
            assertFalse(code.contains("reflect"), code);
        }
    }

    private Path fixture(String name, String definition, String call) throws Exception {
        var project = directory.resolve(name);
        var classpath = System.getProperty("thim.test.processorClasspath").replace("\\", "\\\\").replace("'", "\\'");
        write(project.resolve("settings.gradle"), "plugins { id 'no.beint.thim.settings' }\nrootProject.name = '" + name + "'\n");
        write(project.resolve("build.gradle"), """
            plugins { id 'java'; id 'no.beint.thim' }
            repositories { mavenCentral() }
            thim { modelPackages.set(['sample']); strictModels.set(false) }
            configurations.implementation.dependencies.removeAll { it.group == 'no.beint.thim' }
            dependencies { implementation files('%s'.split(java.io.File.pathSeparator)) }
            afterEvaluate { configurations.thimProcessor.dependencies.clear(); dependencies.add('thimProcessor', files('%s'.split(java.io.File.pathSeparator))) }
            """.formatted(classpath, classpath));
        write(project.resolve("src/main/java/sample/HomePage.java"), "package sample; public record HomePage(ButtonProps button, @org.jetbrains.annotations.Nullable ButtonProps nullableButton, String title) {}\n");
        write(project.resolve("src/main/java/sample/ButtonProps.java"), "package sample; public record ButtonProps(String label, boolean disabled) {}\n");
        write(project.resolve("src/main/java/org/jetbrains/annotations/Nullable.java"), "package org.jetbrains.annotations; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.PARAMETER}) public @interface Nullable {}\n");
        write(project.resolve("src/main/resources/templates/home.html"), "<html><body th:title=\"#{home.title}\"><span th:text=\"${title}\"/>" + call + "</body></html>");
        write(project.resolve("src/main/resources/templates/ui/components.html"), definition);
        write(project.resolve("src/main/resources/i18n/en/home.yaml"), "title: Home\n");
        return project;
    }
    private GradleRunner runner(Path project) {
        return GradleRunner.create().withProjectDir(project.toFile()).withArguments("compileThim", "--stacktrace").withPluginClasspath();
    }
    private void write(Path path, String source) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path, source); }
}
