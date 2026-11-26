package no.beint.thim.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerRegressionTest {
    @TempDir
    Path directory;

    @Test
    void compilesAndRendersKotlinModelsUnderStrictLintWithoutWarnings() throws Exception {
        var project = kotlinProject("regressions", true);
        write(project.resolve("src/main/kotlin/sample/Models.kt"), """
                package sample

                import no.beint.thim.FormErrors
                import no.beint.thim.SafeHtml

                data class Box<T>(val value: T)

                data class Item(val name: String, val active: Boolean)

                data class FormInput(val email: String)

                data class FieldProps(val label: String)

                data class FeaturePage(
                    val showIntro: Boolean,
                    val hideBanner: Boolean,
                    val items: List<Item>,
                    val itemBox: Box<Item>,
                    val labelBox: Box<String>,
                    val richHtml: SafeHtml?,
                    val emptyHtml: SafeHtml?,
                    val is2faEnabled: Boolean,
                    val isActive: Boolean,
                    val isPending: Boolean?,
                    val isLabel: String,
                    val island: Boolean,
                    val form: FormInput,
                    val fieldProps: FieldProps,
                    val errors: FormErrors,
                ) {
                    private val unusedPrivate: String = "hidden"
                }

                data class LargeGenericPage(
                    val boxes: List<Box<Item>>,
                )
                """);

        write(project.resolve("src/main/resources/templates/fragments/parts.html"), """
                <p th:fragment="banner(text)" class="banner" th:text="${text}"></p>
                <li th:fragment="row(label)" class="row" th:text="${label}"></li>
                """);

        write(project.resolve("src/main/resources/templates/ui/field.html"), """
                <div th:component="ui:wrapper"><th:block th:slot="default"></th:block></div>
                <div th:component="ui:email-field" th:props="sample.FieldProps">
                    <label for="email" th:text="${label}"></label>
                    <input type="email" th:field="*{email}">
                    <span class="error" th:errors="*{email}"></span>
                </div>
                """);

        write(project.resolve("src/main/resources/templates/feature.html"), """
                <!DOCTYPE html>
                <html>
                <body>
                    <div th:if="${showIntro}" th:replace="~{fragments/parts :: banner(${labelBox.value})}"></div>
                    <div th:unless="${hideBanner}" th:replace="~{fragments/parts :: banner(${itemBox.value.name})}"></div>
                    <ul>
                        <li th:each="item : ${items}" th:if="${item.active}" th:replace="~{fragments/parts :: row(${item.name})}"></li>
                    </ul>
                    <div class="rich" th:utext="${richHtml}"></div>
                    <div class="empty" th:utext="${emptyHtml}"></div>
                    <p class="flags" th:data-role=" 'admin panel' ">
                        <span th:if="${is2faEnabled}">2fa</span>
                        <span th:if="${isActive}">active</span>
                        <span th:text="${isPending}"></span>
                        <span th:text="${isLabel}"></span>
                        <span th:if="${island}">island</span>
                    </p>
                    <form th:object="${form}">
                        <ui:wrapper>
                            <ui:email-field th:props="${fieldProps}"></ui:email-field>
                        </ui:wrapper>
                    </form>
                </body>
                </html>
                """);

        var repeated = new StringBuilder();
        for (var i = 0; i < 320; i++) {
            repeated.append("<span th:text=\"${box.value.name}\"></span>\n");
        }
        write(project.resolve("src/main/resources/templates/large-generic.html"), """
                <div>
                    <section th:each="box : ${boxes}">
                %s
                    </section>
                </div>
                """.formatted(repeated));

        write(project.resolve("src/main/kotlin/sample/Main.kt"), """
                package sample

                import no.beint.thim.FieldError
                import no.beint.thim.FormErrors
                import no.beint.thim.SafeHtml
                import no.beint.thim.TemplateRenderer
                import java.util.Locale

                fun main() {
                    val renderer = TemplateRenderer.fromServiceLoader()
                    val feature = FeaturePage(
                        showIntro = true,
                        hideBanner = false,
                        items = listOf(Item("One", true), Item("Skipped", false), Item("Two", true)),
                        itemBox = Box(Item("BoxedItem", true)),
                        labelBox = Box("IntroText"),
                        richHtml = SafeHtml("<b>Bold</b>"),
                        emptyHtml = null,
                        is2faEnabled = true,
                        isActive = true,
                        isPending = false,
                        isLabel = "open",
                        island = true,
                        form = FormInput("ada@example.com"),
                        fieldProps = FieldProps("Email"),
                        errors = FormErrors(listOf(FieldError("email", "Invalid email", "bad@"))),
                    )
                    val featureHtml = renderer.renderToString(feature, Locale.ENGLISH)
                    check("<p class=\\"banner\\">IntroText</p>" in featureHtml) { featureHtml }
                    check("<p class=\\"banner\\">BoxedItem</p>" in featureHtml) { featureHtml }
                    check("<li class=\\"row\\">One</li>" in featureHtml) { featureHtml }
                    check("Skipped" !in featureHtml) { featureHtml }
                    check("<li class=\\"row\\">Two</li>" in featureHtml) { featureHtml }
                    check("<div class=\\"rich\\"><b>Bold</b></div>" in featureHtml) { featureHtml }
                    check("<div class=\\"empty\\"></div>" in featureHtml) { featureHtml }
                    check("data-role=\\"admin panel\\"" in featureHtml) { featureHtml }
                    check("<span>2fa</span>" in featureHtml) { featureHtml }
                    check("<span>active</span>" in featureHtml) { featureHtml }
                    check("<span>false</span>" in featureHtml) { featureHtml }
                    check("<span>open</span>" in featureHtml) { featureHtml }
                    check("<span>island</span>" in featureHtml) { featureHtml }
                    check("value=\\"bad@\\"" in featureHtml) { featureHtml }
                    check("<span class=\\"error\\">Invalid email</span>" in featureHtml) { featureHtml }

                    val largeHtml = renderer.renderToString(
                        LargeGenericPage(listOf(Box(Item("Alpha", true)), Box(Item("Beta", false)))),
                        Locale.ENGLISH,
                    )
                    check(largeHtml.split("<span>Alpha</span>").size - 1 == 320) { "Expected 320 Alpha spans" }
                    check(largeHtml.split("<span>Beta</span>").size - 1 == 320) { "Expected 320 Beta spans" }
                }
                """);

        write(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle")) + """

                tasks.register('runCheck', JavaExec) {
                    dependsOn classes
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'sample.MainKt'
                }
                """);

        GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("runCheck", "--stacktrace")
                .withPluginClasspath()
                .build();

        var largeFile = Math.floorMod("sample.LargeGenericPage".hashCode(), 32);
        var generated = Files.readString(
                project.resolve("build/generated/ksp/main/java/regressions/thim/generated/ThimTemplatesPart" + largeFile + ".java")
        );
        assertTrue(generated.contains("renderPart0("), generated);
        assertTrue(generated.contains("sample.Box<sample.Item> item0"), generated);
        assertFalse(generated.contains("Object item0Value"), generated);
    }

    @Test
    void emitsClearDiagnosticForInternalKotlinProperty() throws Exception {
        var project = kotlinProject("internal-prop", false);
        write(project.resolve("src/main/kotlin/sample/HomePage.kt"), """
                package sample

                class HomePage(internal val secret: String)
                """);
        write(project.resolve("src/main/resources/templates/home.html"), "<p th:text=\"${secret}\"></p>\n");

        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("kspKotlin", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertTrue(result.getOutput().contains("THIM-PROPERTY-INTERNAL"), result.getOutput());
        assertTrue(result.getOutput().contains("secret"), result.getOutput());
    }

    @Test
    void skipsPrivateKotlinPropertiesDuringResolution() throws Exception {
        var project = kotlinProject("private-prop", false);
        write(project.resolve("src/main/kotlin/sample/HomePage.kt"), """
                package sample

                class HomePage(private val secret: String, val visible: String)
                """);
        write(project.resolve("src/main/resources/templates/home.html"), "<p th:text=\"${secret}\"></p>\n");

        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("kspKotlin", "--stacktrace")
                .withPluginClasspath()
                .buildAndFail();

        assertTrue(result.getOutput().contains("THIM-PROPERTY-UNKNOWN"), result.getOutput());
        assertFalse(result.getOutput().contains("did you mean 'secret'"), result.getOutput());
    }

    @Test
    void validatesAndGeneratesRoutesAcrossSiblingModules() throws Exception {
        var classpath = System.getProperty("thim.test.processorClasspath").replace("\\", "\\\\").replace("'", "\\'");
        var project = directory.resolve("multi-module-routes");
        write(project.resolve("gradle.properties"), "ksp.project.isolation.enabled=true\n");
        write(project.resolve("settings.gradle"), """
                plugins { id 'no.beint.thim.settings' }
                rootProject.name = 'multi-module-routes'
                include 'feature', 'app'
                """);
        write(project.resolve("feature/build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation 'org.springframework:spring-web:7.0.9'
                }

                tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
                    compilerOptions {
                        javaParameters.set(true)
                    }
                }
                """);
        write(project.resolve("feature/src/main/kotlin/feature/QuizController.kt"), """
                package feature

                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.PathVariable
                import org.springframework.web.bind.annotation.PostMapping
                import org.springframework.web.bind.annotation.RequestMapping
                import org.springframework.web.bind.annotation.RequestParam

                @RequestMapping("/quiz")
                class QuizController {
                    @GetMapping("/{id}")
                    fun show(@PathVariable id: Long, @RequestParam(required = false) page: Int?): String = "ok"

                    @PostMapping("/{id}/submit")
                    fun submit(@PathVariable id: Long): String = "ok"
                }
                """);
        write(project.resolve("app/build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm'
                    id 'no.beint.thim'
                }

                repositories {
                    mavenCentral()
                }

                thim {
                    modelPackages.set(['sample'])
                    generateRoutes.set(true)
                }

                dependencies {
                    implementation project(':feature')
                    implementation 'org.springframework:spring-web:7.0.9'
                }

                afterEvaluate {
                    configurations.implementation.dependencies.removeAll { it.group == 'no.beint.thim' }
                    configurations.ksp.dependencies.removeAll { it.group == 'no.beint.thim' }
                    dependencies {
                        implementation files('%s'.split(java.io.File.pathSeparator))
                        ksp files('%s'.split(java.io.File.pathSeparator))
                    }
                }
                """.formatted(classpath, classpath));
        write(project.resolve("app/src/main/kotlin/sample/HomePage.kt"), """
                package sample

                data class HomePage(val quizId: Long)
                """);
        write(project.resolve("app/src/main/resources/templates/home.html"), """
                <!DOCTYPE html>
                <html>
                <body>
                    <a th:href="@{/quiz/{id}(id=${quizId})}">Start</a>
                    <form th:action="@{/quiz/{id}/submit(id=${quizId})}" method="post">
                        <button type="submit">Submit</button>
                    </form>
                </body>
                </html>
                """);

        var runner = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments(":app:classes", "--isolated-projects", "--stacktrace")
                .withPluginClasspath();
        var first = runner.build();
        assertTrue(first.getOutput().contains(":app:thimExtractExternalRoutes"), first.getOutput());
        var routesFile = project.resolve("app/build/generated/ksp/main/kotlin/app/thim/generated/ThimRoutes.kt");
        assertTrue(Files.isRegularFile(routesFile), "Expected generated ThimRoutes.kt");
        var generatedRoutes = Files.readString(routesFile);
        assertTrue(generatedRoutes.contains("fun `quiz`("), generatedRoutes);
        assertTrue(generatedRoutes.contains("`id`: kotlin.Long"), generatedRoutes);
        assertTrue(generatedRoutes.contains("`page`: kotlin.Int? = null"), generatedRoutes);
        assertTrue(generatedRoutes.contains("fun `quizSubmit`("), generatedRoutes);

        // Unknown route in sibling module fails compilation
        write(project.resolve("app/src/main/resources/templates/home.html"), """
                <!DOCTYPE html>
                <html>
                <body>
                    <a th:href="@{/quiz/{id}/missing(id=${quizId})}">Broken</a>
                </body>
                </html>
                """);
        var unknownFail = runner.buildAndFail();
        assertTrue(unknownFail.getOutput().contains("THIM-URL-UNKNOWN-ROUTE"), unknownFail.getOutput());

        // Wrong HTTP method against sibling module route fails compilation
        write(project.resolve("app/src/main/resources/templates/home.html"), """
                <!DOCTYPE html>
                <html>
                <body>
                    <form th:action="@{/quiz/{id}(id=${quizId})}" method="post">
                        <button type="submit">Wrong method</button>
                    </form>
                </body>
                </html>
                """);
        var methodFail = runner.buildAndFail();
        assertTrue(methodFail.getOutput().contains("THIM-URL-METHOD"), methodFail.getOutput());
    }

    private Path kotlinProject(String name, boolean strictModels) throws Exception {
        var classpath = System.getProperty("thim.test.processorClasspath").replace("\\", "\\\\").replace("'", "\\'");
        var project = directory.resolve(name);
        write(project.resolve("settings.gradle"), "plugins { id 'no.beint.thim.settings' }\nrootProject.name = '" + name + "'\n");
        write(project.resolve("build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm'
                    id 'no.beint.thim'
                }

                repositories {
                    mavenCentral()
                }

                thim {
                    modelPackages.set(['sample'])
                    strictModels.set(%s)
                }

                tasks.withType(JavaCompile).configureEach {
                    options.compilerArgs.addAll(['-Xlint:all', '-Werror'])
                }

                afterEvaluate {
                    configurations.implementation.dependencies.removeAll { it.group == 'no.beint.thim' }
                    configurations.ksp.dependencies.removeAll { it.group == 'no.beint.thim' }
                    dependencies {
                        implementation files('%s'.split(java.io.File.pathSeparator))
                        ksp files('%s'.split(java.io.File.pathSeparator))
                    }
                }
                """.formatted(strictModels, classpath, classpath));
        return project;
    }

    private void write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }
}
