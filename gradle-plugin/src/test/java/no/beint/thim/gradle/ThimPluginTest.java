package no.beint.thim.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;

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
    void validationAndMessageGenerationDefaultsAreStrict() throws IOException {
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
                            thim.generateRoutes.get(),
                            thim.failOnUnusedCss.get()
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
                List.of("true", "true", "true", "true", "true", "true", "false", "true"),
                Files.readAllLines(project.resolve("thim-defaults.txt"))
        );
    }

    @Test
    void checkFailsOnUnusedCssByDefault() throws IOException {
        var project = dependencyProject("dead-css", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("src/main/resources/static/app.css"), """
                .used { color: green; }
                .dead { color: red; }
                """);
        write(project.resolve("src/main/resources/templates/home.html"), "<div class=\"used\"></div>\n");

        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("check", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertTrue(result.getOutput().contains("Unused first-party CSS classes:"));
        assertTrue(result.getOutput().contains("dead (src/main/resources/static/app.css)"));
        assertTrue(Files.readString(project.resolve("build/reports/thim/css-usage.json"))
                .contains("\"name\": \"dead\""));
    }

    @Test
    void cssUsageIsAggregatedAcrossModulesAndDynamicPrefixes() throws IOException {
        var project = dependencyProject("shared-css", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("settings.gradle"), """
                rootProject.name = 'shared-css'
                include 'feature'
                """);
        write(project.resolve("feature/build.gradle"), "plugins { id 'java' }\n");
        write(project.resolve("src/main/resources/static/app.css"), """
                .root-used { color: green; }
                .feature-used { color: blue; }
                .r-spark-1 { height: 1px; }
                .r-spark-2 { height: 2px; }
                """);
        write(project.resolve("src/main/resources/templates/home.html"), "<div class=\"root-used\"></div>\n");
        write(project.resolve("feature/src/main/java/sample/Feature.java"), """
                package sample;
                final class Feature {
                    String css(int index) { return "feature-used r-spark-" + index; }
                }
                """);

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .build();

        var report = Files.readString(project.resolve("build/reports/thim/css-usage.json"));
        assertTrue(report.contains("\"defined\": 4"));
        assertTrue(report.contains("\"unused\": 0"));
        assertTrue(report.contains("\"prefixUsed\": 2"));
    }

    @Test
    void unusedCssCheckHasAnExplicitModuleOptOut() throws IOException {
        var project = dependencyProject("css-opt-out", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                thim { failOnUnusedCss.set(false) }
                """);
        write(project.resolve("src/main/resources/static/app.css"), ".dead { color: red; }\n");

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .build();
    }

    @Test
    void runtimeCssClassesAreAnExactAllowlist() throws IOException {
        var project = dependencyProject("runtime-css", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                thim { runtimeCssClasses.add('external-widget__button') }
                """);
        write(project.resolve("src/main/resources/static/app.css"), """
                .external-widget__button { color: green; }
                .external-widget__unused { color: red; }
                """);

        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertFalse(result.getOutput().contains("external-widget__button ("));
        assertTrue(result.getOutput().contains("external-widget__unused ("));
    }

    @Test
    void cssParserUnescapesVariantsAndIgnoresCommentsAndStrings() {
        var classes = ThimCssUsageCheck.classNames("""
                /* .commented */
                .r-max-md\\:r-font-size-l, .plain:hover { content: ".not-a-selector"; }
                """);

        assertEquals(Set.of("r-max-md:r-font-size-l", "plain"), classes);
    }

    @Test
    void usageScannerIgnoresCommentedClassNames() {
        var tokens = new java.util.LinkedHashSet<String>();
        var prefixes = new java.util.LinkedHashSet<String>();

        ThimCssUsageCheck.collectLiterals("""
                // "line-comment"
                /* "block-comment" */
                <!-- class="html-comment" -->
                const classes = `live r-chart-${index > 0 ? "r-positive" : "r-negative"}`;
                """, tokens, prefixes);

        assertEquals(Set.of("live", "r-chart-", "r-positive", "r-negative"), tokens);
        assertEquals(Set.of("r-chart-"), prefixes);
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

    @Test
    void messageUsageCheckFindsGeneratedFactoryCallsAndRejectsDeadKeys() throws IOException {
        var project = messageUsageProject("dead-message", "sample.Messages.Home.used();");

        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimMessageUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertTrue(result.getOutput().contains("Unused messages in test/catalog: [home.dead]"));
    }

    @Test
    void messageUsageCheckFindsInlinedAnnotationReferences() throws IOException {
        var project = messageUsageProject(
                "message-reference",
                "String reference = \"{thim:sample.Messages:home.dead}\"; sample.Messages.Home.used();"
        );

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimMessageUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .build();
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

    private Path messageUsageProject(String name, String consumerStatement) throws IOException {
        var project = dependencyProject(name, """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                tasks.matching { it.name == 'thimMessageUsageCheck' }.configureEach {
                    projectOutputs.setFrom(files('usage-output'))
                }
                """);
        var source = project.resolve("usage-source");
        var output = project.resolve("usage-output");
        write(source.resolve("sample/Messages.java"), """
                package sample;
                public final class Messages {
                    public static final class Home {
                        public static String used() { return "used"; }
                        public static String dead() { return "dead"; }
                    }
                }
                """);
        write(source.resolve("sample/Consumer.java"), """
                package sample;
                public final class Consumer {
                    public static void consume() { %s }
                }
                """.formatted(consumerStatement));
        Files.createDirectories(output);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(
                null,
                null,
                null,
                "-d", output.toString(),
                source.resolve("sample/Messages.java").toString(),
                source.resolve("sample/Consumer.java").toString()
        ));
        write(output.resolve("META-INF/thim/messages/test.usage"), """
                thim-message-usage\t1
                catalog\t%s
                enforce\ttrue
                api\tsample/Messages
                definition\t%s\tsample/Messages$Home\tused\t%s
                definition\t%s\tsample/Messages$Home\tdead\t%s
                """.formatted(
                encoded("test/catalog"),
                encoded("home.used"),
                encoded("{thim:sample.Messages:home.used}"),
                encoded("home.dead"),
                encoded("{thim:sample.Messages:home.dead}")
        ));
        return project;
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
}
