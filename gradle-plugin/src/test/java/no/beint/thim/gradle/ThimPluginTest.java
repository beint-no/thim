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
                plugins { id 'no.beint.thim.settings' }
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
                .withArguments("thimCssUsageCheck", "--isolated-projects", "--stacktrace")
                .withPluginClasspath()
                .build();

        var report = Files.readString(project.resolve("build/reports/thim/css-usage.json"));
        assertTrue(report.contains("\"defined\": 4"));
        assertTrue(report.contains("\"unused\": 0"));
        assertTrue(report.contains("\"prefixUsed\": 2"));
    }

    @Test
    void isolatedMessageAggregationBuildsConsumersAndReusesConfiguration() throws IOException {
        var project = messageUsageProject("isolated-messages", "sample.Messages.Home.used(); sample.Messages.Home.dead();");
        write(project.resolve("settings.gradle"), """
                plugins { id 'no.beint.thim.settings' }
                rootProject.name = 'isolated-messages'
                include 'catalog', 'consumer'
                """);
        write(project.resolve("build.gradle"), "");
        write(project.resolve("catalog/build.gradle"), "plugins { id 'java-library' }\n");
        write(project.resolve("consumer/build.gradle"), """
                plugins { id 'java' }
                dependencies { implementation project(':catalog') }
                """);
        write(project.resolve("catalog/src/main/java/sample/Messages.java"),
                Files.readString(project.resolve("usage-source/sample/Messages.java")));
        write(project.resolve("catalog/src/main/resources/META-INF/thim/messages/test.usage"),
                Files.readString(project.resolve("usage-output/META-INF/thim/messages/test.usage")));
        var consumer = project.resolve("consumer/src/main/java/sample/Consumer.java");
        var source = Files.readString(project.resolve("usage-source/sample/Consumer.java"));
        write(consumer, source);
        var runner = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("thimMessageUsageCheck", "--isolated-projects", "--stacktrace");

        var first = runner.build();
        assertTrue(first.getOutput().contains(":consumer:compileJava"));
        assertTrue(first.getOutput().contains("Configuration cache entry stored"));
        assertTrue(runner.build().getOutput().contains("Reusing configuration cache"));

        write(consumer, source.replace("sample.Messages.Home.dead();", ""));
        assertTrue(runner.buildAndFail().getOutput().contains("Unused messages in test/catalog: [home.dead]"));
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
    void cssParserExtractsIdsAndCustomPropertiesWithoutHexColors() {
        var parsed = ThimCssUsageCheck.parseCss("""
                :root {
                  --brand: #1a2b3c;
                  --unused-token: 1rem;
                  --wa-color: #fff;
                }
                /* #commented-id { --commented-var: 1px; } */
                #hero, a[href="#ignored-attr"] {
                  color: var(--brand);
                  background: #abcdef;
                  border-color: #123
                }
                @media (min-width: 768px) {
                  #sidebar .nav-link:hover {
                    color: var(--brand, var(--fallback));
                  }
                }
                @keyframes pulse {
                  from { color: #111; }
                  to { color: #222; }
                }
                """);

        assertEquals(Set.of("nav-link"), parsed.classes());
        assertEquals(Set.of("hero", "sidebar"), parsed.ids());
        assertEquals(Set.of("--brand", "--unused-token", "--wa-color"), parsed.variables());
        assertEquals(Set.of("--brand", "--fallback"), parsed.varRefs());
    }

    @Test
    void cssUsageCheckDetectsDeadIdsAndHonorsRuntimeIds() throws IOException {
        var project = dependencyProject("dead-css-id", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                thim { runtimeIds.add('runtime-panel') }
                """);
        write(project.resolve("src/main/resources/static/app.css"), """
                #used-id { color: #112233; }
                #runtime-panel { background: #fff; }
                #dead-id { color: #ff0000; }
                """);
        write(project.resolve("src/main/resources/templates/home.html"), "<div id=\"used-id\"></div>\n");

        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertTrue(result.getOutput().contains("Unused first-party CSS #id selectors:"), result.getOutput());
        assertTrue(result.getOutput().contains("#dead-id (src/main/resources/static/app.css)"), result.getOutput());
        assertFalse(result.getOutput().contains("#used-id"), result.getOutput());
        assertFalse(result.getOutput().contains("#runtime-panel"), result.getOutput());
        assertFalse(result.getOutput().contains("#112233"), result.getOutput());
        assertFalse(result.getOutput().contains("#fff"), result.getOutput());
    }

    @Test
    void cssUsageCheckDetectsUnusedCustomPropertiesAndIgnoresThirdPartyPrefixes() throws IOException {
        var project = dependencyProject("dead-css-vars", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("src/main/resources/static/app.css"), """
                :root {
                  --used-in-css: #111;
                  --used-in-html: #222;
                  --dead-var: #333;
                  --wa-brand: #444;
                  --sl-spacing: 1rem;
                  --adyen-color: #555;
                  --tw-shadow: 0 0;
                  --bs-primary: #666;
                }
                /* var(--dead-var) */
                .card { color: var(--used-in-css); }
                """);
        write(project.resolve("src/main/resources/templates/home.html"), """
                <div class="card" style="background: var(--used-in-html)"></div>
                """);

        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertTrue(result.getOutput().contains("Unused first-party CSS custom properties:"), result.getOutput());
        assertTrue(result.getOutput().contains("--dead-var (src/main/resources/static/app.css)"), result.getOutput());
        assertFalse(result.getOutput().contains("--used-in-css"), result.getOutput());
        assertFalse(result.getOutput().contains("--used-in-html"), result.getOutput());
        assertFalse(result.getOutput().contains("--wa-brand"), result.getOutput());
        assertFalse(result.getOutput().contains("--tw-shadow"), result.getOutput());

        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                thim { failOnUnusedCssVariables.set(false) }
                """);
        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--stacktrace")
                .withPluginClasspath()
                .build();
    }

    @Test
    void reverseCssCheckIsScopedPerModuleWithExternalCssAndPrefixes() throws IOException {
        var project = temporaryDirectory.resolve("reverse-css");
        write(project.resolve("settings.gradle"), """
                plugins { id 'no.beint.thim.settings' }
                rootProject.name = 'reverse-css'
                include 'bedri', 'web-app'
                """);
        write(project.resolve("bedri/build.gradle"), """
                plugins {
                    id 'java'
                    id 'no.beint.thim'
                }
                thim {
                    failOnUnstyledCss.set(true)
                    externalCss.from(file('generated/tailwind.css'))
                }
                tasks.matching { it.name == 'compileThim' }.configureEach { enabled = false }
                """);
        write(project.resolve("web-app/build.gradle"), """
                plugins {
                    id 'java'
                    id 'no.beint.thim'
                }
                thim {
                    checkedCssClassPrefixes.set(['r-'])
                }
                tasks.matching { it.name == 'compileThim' }.configureEach { enabled = false }
                """);
        write(project.resolve("bedri/generated/tailwind.css"), """
                .flex { display: flex; }
                .hover\\:underline:hover { text-decoration: underline; }
                """);
        write(project.resolve("bedri/src/main/resources/static/bedri.css"), ".bedri-hero { padding: 1rem; }\n");
        write(project.resolve("bedri/src/main/resources/templates/home.html"), """
                <div class="flex hover:underline bedri-hero htmx-request"></div>
                <span class="ignored-by-th-class" th:class="${dynamicClass}"></span>
                """);
        write(project.resolve("web-app/src/main/resources/static/app.css"), ".r-card { display: block; }\n");
        write(project.resolve("web-app/src/main/resources/templates/dashboard.html"), """
                <div class="r-card wa-stack wa-gap-m"></div>
                """);

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--isolated-projects", "--stacktrace")
                .withPluginClasspath()
                .build();

        // :bedri must fail if it uses a class from :web-app (not in :bedri's own CSS)
        write(project.resolve("bedri/src/main/resources/templates/home.html"), """
                <div class="flex bedri-hero r-card"></div>
                """);
        var bedriFail = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--isolated-projects", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();
        assertTrue(bedriFail.getOutput().contains("Unstyled HTML classes:"), bedriFail.getOutput());
        assertTrue(bedriFail.getOutput().contains("r-card (bedri/src/main/resources/templates/home.html:1)"), bedriFail.getOutput());

        // Restore :bedri and introduce a missing r-* class in :web-app
        write(project.resolve("bedri/src/main/resources/templates/home.html"), """
                <div class="flex bedri-hero"></div>
                """);
        write(project.resolve("web-app/src/main/resources/templates/dashboard.html"), """
                <div class="r-card r-typo wa-stack"></div>
                """);
        var webFail = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimCssUsageCheck", "--isolated-projects", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();
        assertTrue(webFail.getOutput().contains("r-typo (web-app/src/main/resources/templates/dashboard.html:1)"), webFail.getOutput());
        assertFalse(webFail.getOutput().contains("wa-stack"), webFail.getOutput());
    }

    @Test
    void idReferenceCheckValidatesCrossTemplateAndJsReferences() throws IOException {
        var project = dependencyProject("id-references", """
                id 'java'
                id 'no.beint.thim'
                """);
        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                thim { runtimeIds.add('runtime-toast') }
                """);
        write(project.resolve("src/main/resources/templates/layout.html"), """
                <div id="main-panel"></div>
                <div id="spinner"></div>
                <div id="err-box"></div>
                <div id="menu-pop" popover></div>
                <div th:id="|row-${item.id}|"></div>
                <input th:field="*{email}">
                """);
        write(project.resolve("src/main/resources/templates/partial.html"), """
                <button hx-target="#main-panel"
                        hx-include="#email, closest form, [name='q']"
                        hx-indicator="#spinner"
                        hx-status:422="swap:innerHTML target:#err-box"
                        popovertarget="menu-pop">Go</button>
                <button hx-target="this" hx-indicator="closest .btn">Local</button>
                <button hx-target="#row-42">Dynamic prefix</button>
                <button hx-target="#runtime-toast">Runtime</button>
                """);
        write(project.resolve("src/main/resources/static/app.js"), """
                const el = document.createElement('div');
                el.id = 'js-created';
                el.setAttribute('id', 'js-attr-id');
                document.getElementById('js-created');
                document.querySelector('#js-attr-id');
                document.querySelector('#main-panel .nested');
                document.querySelector('#main-panel:hover');
                // document.getElementById('commented-out')
                """);
        write(project.resolve("src/main/resources/static/vendor/third-party.js"), """
                document.getElementById('vendor-missing-id');
                """);

        var runner = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("thimIdReferenceCheck", "--isolated-projects", "--stacktrace")
                .withPluginClasspath();
        var ok = runner.build();
        assertTrue(ok.getOutput().contains("Configuration cache entry stored"), ok.getOutput());
        assertTrue(runner.build().getOutput().contains("Reusing configuration cache"));

        // Introduce broken references in partial HTML and JS
        write(project.resolve("src/main/resources/templates/partial.html"), """
                <button hx-target="#missing-panel">Go</button>
                <button popovertarget="missing-pop">Open</button>
                """);
        write(project.resolve("src/main/resources/static/app.js"), """
                document.getElementById('missing-js-id');
                document.querySelector('#missing-qs-id');
                """);

        var failed = runner.buildAndFail();
        assertTrue(failed.getOutput().contains("Broken #id references:"), failed.getOutput());
        assertTrue(failed.getOutput().contains("partial.html:1 hx-target=\"#missing-panel\" -> #missing-panel"), failed.getOutput());
        assertTrue(failed.getOutput().contains("partial.html:2 popovertarget=\"missing-pop\" -> #missing-pop"), failed.getOutput());
        assertTrue(failed.getOutput().contains("app.js:1 document.getElementById(\"missing-js-id\") -> #missing-js-id"), failed.getOutput());
        assertTrue(failed.getOutput().contains("app.js:2 querySelector(\"#missing-qs-id\") -> #missing-qs-id"), failed.getOutput());
        assertFalse(failed.getOutput().contains("vendor-missing-id"), failed.getOutput());

        // Opt-out disables failure
        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                thim { failOnBrokenIdReferences.set(false) }
                """);
        runner.build();
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
                id 'org.springframework.boot' version '4.1.1'
                id 'no.beint.thim'
                """);

        assertEquals(List.of("runtime", "spring"), thimDependencies(project));
    }

    @Test
    void springBootAfterThimAddsTheSpringAdapter() throws IOException {
        var project = dependencyProject("boot-after-thim", """
                id 'java'
                id 'no.beint.thim'
                id 'org.springframework.boot' version '4.1.1'
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

    @Test
    void processResourcesExclusionReusesConfigurationCache() throws IOException {
        var project = project("resource-cc", "");
        var runner = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("processResources", "-x", "compileThim", "--configuration-cache", "--stacktrace")
                .withPluginClasspath();

        var first = runner.build();
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        assertFalse(Files.exists(project.resolve("build/resources/main/templates/home.html")));
        assertFalse(Files.exists(project.resolve("build/resources/main/i18n/en/home.yaml")));
        assertTrue(Files.exists(project.resolve("build/resources/main/public.txt")));

        var second = runner.build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
    }

    @Test
    void kotlinPluginScopesThimInputsAndApOptionsToKspKotlinOnly() throws IOException {
        var project = temporaryDirectory.resolve("ksp-scope");
        write(project.resolve("settings.gradle"), "plugins { id 'no.beint.thim.settings' }\nrootProject.name = 'ksp-scope'\n");
        write(project.resolve("src/main/resources/templates/home.html"), "<html></html>\n");
        write(project.resolve("src/main/resources/i18n/en/home.yaml"), "title: Home\n");
        write(project.resolve("build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm'
                    id 'no.beint.thim'
                }

                repositories {
                    mavenCentral()
                }

                afterEvaluate {
                    configurations.implementation.dependencies.removeAll { it.group == 'no.beint.thim' }
                    configurations.ksp.dependencies.removeAll { it.group == 'no.beint.thim' }
                }

                tasks.register('inspectKspTasks') {
                    doLast {
                        def mainTask = tasks.named('kspKotlin').get()
                        def testTask = tasks.named('kspTestKotlin').get()
                        def mainFiles = mainTask.inputs.files.files.collect { it.name }.findAll { it in ['home.html', 'home.yaml'] }.sort()
                        def testFiles = testTask.inputs.files.files.collect { it.name }.findAll { it in ['home.html', 'home.yaml'] }.sort()
                        file('ksp-inspection.txt').text = [
                            'mainFiles=' + mainFiles.join(','),
                            'testFiles=' + testFiles.join(','),
                            'mainTemplates=' + mainTask.kspConfig.apOptions.get().get('thim.templates'),
                            'mainMessages=' + mainTask.kspConfig.apOptions.get().get('thim.messages'),
                            'testTemplatesAbsolute=' + new File(testTask.kspConfig.apOptions.get().get('thim.templates')).isAbsolute(),
                        ].join('\\n')
                    }
                }
                """);

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("inspectKspTasks", "--stacktrace")
                .withPluginClasspath()
                .build();

        assertEquals(
                List.of(
                        "mainFiles=home.html,home.yaml",
                        "testFiles=",
                        "mainTemplates=src/main/resources/templates",
                        "mainMessages=src/main/resources/i18n",
                        "testTemplatesAbsolute=true"
                ),
                Files.readAllLines(project.resolve("ksp-inspection.txt"))
        );
    }

    private Path project(String name, String thimConfiguration) throws IOException {
        var project = temporaryDirectory.resolve(name);
        write(project.resolve("settings.gradle"), "plugins { id 'no.beint.thim.settings' }\nrootProject.name = '" + name + "'\n");
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
        write(project.resolve("settings.gradle"), "plugins { id 'no.beint.thim.settings' }\nrootProject.name = '" + name + "'\n");
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
