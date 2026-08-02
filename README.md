# Thim

Thim is a strict AOT HTML renderer for Kotlin applications. It accepts a deliberately small, source-compatible subset of Thymeleaf attributes and generates direct Java renderers during compilation.

There is no template engine, expression language, reflection, property lookup, message lookup or character encoder in the request path. Static HTML is stored once as UTF-8 bytes; generated code writes those bytes and escaped dynamic values directly to the response.

Thim requires JDK 26 or newer. The optional MVC adapter targets Spring Framework 7 and Spring Boot 4.

## Use

Declare the page model in its template:

```html
<!--/* @thim-model no.example.web.HomePage */-->
<!doctype html>
<h1 th:text="#{home.title}">Home</h1>
<p th:text="${greeting}">Greeting</p>
```

The declaration is a Thymeleaf parser-level comment, so the same template remains usable by Thymeleaf while it is being migrated. Thim removes it from rendered output.

Return an ordinary Kotlin value. No annotation, view-name string or string-keyed model is needed:

```kotlin
data class HomePage(val greeting: String)

@GetMapping("/")
fun home() = HomePage("Hello")
```

Apply the plugin after the Kotlin JVM plugin:

```kotlin
plugins {
    kotlin("jvm")
    id("no.beint.thim") version "0.2.0-experimental.1"
}
```

The plugin supplies the runtime, compiler and Spring adapter, tracks templates and messages as compilation inputs, and generates Spring Boot auto-configuration. Its defaults are:

```kotlin
thim {
    templates.set(layout.projectDirectory.dir("src/main/resources/templates"))
    messages.set(layout.projectDirectory.dir("src/main/resources"))
    generatedPackage.set("your.group.your_module.thim.generated")
    registryName.set("ThimTemplates")
}
```

Artifacts are currently published through `https://maven.pkg.github.com/beint-no/thim`. Add that repository to both `pluginManagement` and `dependencyResolutionManagement` until the plugin is published to the Gradle Plugin Portal and Maven Central.

## Language

Thim accepts:

- `${property}` and null-safe property paths
- `th:text`
- `th:each`
- `th:if` and `th:unless`
- property, message, static URL and quoted-literal values on ordinary `th:*` attributes
- conditional HTML boolean attributes
- literal `#{message}` expressions with typed arguments
- `${#locale.language}` for a language attribute

Missing models, properties and messages; unsafe nullable access; locale drift; duplicate messages; invalid message arguments; malformed HTML; and unsupported expressions fail compilation.

There is intentionally no SpEL or OGNL. Attributes such as `th:attr`, `th:field`, `th:object`, `th:replace`, `th:switch`, `th:utext` and `th:with` are compilation errors in typed templates. Computation belongs in the page model.

## Modules

- `runtime`: three dependency-free Java types
- `compiler`: the Kotlin-aware KSP compiler; build time only
- `spring`: the Java Spring MVC return-value adapter
- `gradle-plugin`: the Java build integration
- `example`: a complete Spring Boot application

See [DESIGN.md](DESIGN.md) for the architecture, performance work and compatibility trade-offs.

## Relationship to Thymeleaf

Thim is an independent clean-room implementation. It contains no Thymeleaf source code and has no runtime dependency on Thymeleaf. It implements a source-compatible subset of familiar Thymeleaf HTML attributes so an application can migrate template by template. It is not affiliated with the Thymeleaf project and does not claim full Thymeleaf compatibility.
