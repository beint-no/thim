# Thim

Thim is a strict AOT HTML renderer for Java and Kotlin applications. It compiles typed templates into direct Java renderers.

There is no template engine, expression language, reflection, property lookup, message lookup or character encoder in the request path. Static HTML is stored once as UTF-8 bytes; generated code writes those bytes and escaped dynamic values directly to the response.

Thim requires JDK 26 or newer. Its optional MVC adapter targets Spring Framework 7 and Spring Boot 4.

## Use

Name the page model after its template. `home.html` resolves to `HomePage`:

```html
<!doctype html>
<html>
<body>
    <h1 th:text="#{home.title}">Home</h1>
    <p th:text="${greeting}">Greeting</p>
</body>
</html>
```

Return the model directly from a controller. Kotlin data classes and Java records are both supported:

```kotlin
package no.example.page

data class HomePage(val greeting: String)

@GetMapping("/")
fun home() = HomePage("Hello")
```

```java
package no.example.page;

record HomePage(String greeting) {}

@GetMapping("/")
HomePage home() {
    return new HomePage("Hello");
}
```

Apply the plugin after the Kotlin JVM plugin in Kotlin modules:

```kotlin
plugins {
    kotlin("jvm")
    id("no.beint.thim") version "0.4.3"
}
```

Java modules need only the Java and Thim plugins:

```kotlin
plugins {
    java
    id("no.beint.thim") version "0.4.3"
}
```

The plugin supplies the runtime, compiler and Spring adapter and tracks templates and messages as compilation inputs. Compiled template jars publish their registries through Java's service loader, so templates can live in any application module.

```kotlin
thim {
    templates.set(layout.projectDirectory.dir("src/main/resources/templates"))
    messages.set(layout.projectDirectory.dir("src/main/resources"))
    generatedPackage.set("your.group.your_module.thim.generated")
    registryName.set("ThimTemplates")
    modelPackages.set(listOf("no.example.page"))
    strictTemplates.set(true)
    failOnUnusedMessages.set(true)
    strictModels.set(true)
    failOnUnusedProperties.set(true)
}
```

The default model package is `<project group>.page`. Nested template names are part of the class name: `error/404.html` resolves to `Error404Page`. Fixed `th:replace` fragments and layouts are linked and inlined during compilation; fragment libraries need no page model.

`strictTemplates` rejects every non-fragment template without a matching page model. `failOnUnusedMessages` is suitable when the configured message bundles are owned entirely by the compiled templates. Leave it disabled for a bundle also used by backend code unless that usage is checked separately.

`strictModels` opts page models into a closed-world contract for render-only data. A strict model and every type reachable from its properties must be immutable data prepared for rendering: mutable properties and setters are rejected, along with `Any`/`Object` properties, map-shaped data, raw collections, and `th:each` over collections that are not materialized (`List`, `Set`, `Collection`, or an array). Types annotated with a persistence annotation are rejected so entities and lazy collections never reach a renderer; the default list covers JPA (`@Entity`, `@Embeddable`, `@MappedSuperclass` in both `jakarta.persistence` and `javax.persistence`) and can be replaced through `forbiddenModelAnnotations`. Model properties no expanded template reads are reported as warnings; `failOnUnusedProperties` turns them into errors. Keep it disabled for models whose properties are also consumed by backend code.

Expanded pages with an `<html>` root are additionally validated as complete documents. Duplicate ids are rejected, including the ids `th:field` generates, and `label for`, `aria-labelledby`, `aria-describedby`, `aria-controls`, and local `href="#..."` anchors must reference an id that exists in the page. A static id or a `th:field` inside `th:each` is reported as a warning because repeated output is likely to duplicate the id; use `data-*` attributes or an id on the container instead. Templates without an `<html>` root are HTMX partials rendered into an existing document, so their references are not validated. Ids are always static: `th:id` is not part of the language, keeping every id provable at compile time.

Use `thimCheck` for fast template validation during development:

```shell
./gradlew thimCheck
./gradlew thimCheck --continuous
```

`thimCheck` uses the same parser, fragment expansion, type analysis, message validation and safety checks as normal compilation. Java modules run the production compiler into isolated check outputs and write a cacheable machine-readable report to `build/reports/thim/check.json`. Kotlin modules delegate to the KSP task configured by the plugin, so diagnostics match ordinary Kotlin compilation.

Artifacts are published through `https://maven.pkg.github.com/beint-no/thim`. Add that repository to `pluginManagement` and dependency resolution.

## Language

Thim accepts:

- `${property}` and null-safe property paths
- `th:text`
- `th:each`
- `th:if` and `th:unless`
- fixed, build-time `th:fragment` and `th:replace` composition
- property, message, static URL and quoted-literal values on ordinary `th:*` attributes
- `no.beint.thim.TrustedUrl` properties on URL attributes such as `th:href`, `th:src` and `th:action`
- conditional HTML boolean attributes
- literal `#{message}` expressions with typed arguments
- `${#locale.language}` for a language attribute

Every dynamic output position is classified during compilation and rendered with the encoder for its context. Dynamic output in JavaScript, CSS and event-handler contexts — `th:on*`, `th:style` and `th:text` on `<script>` or `<style>` — is rejected. Dynamic URL values must be a static `@{...}` URL or an explicit `TrustedUrl` property; `SafeHtml` opts into raw HTML through `th:utext` only and is rejected everywhere else.

Missing models, properties and messages; unused template-owned messages; unsafe nullable access; locale drift; duplicate messages; invalid message arguments; malformed HTML; unsupported output contexts; and unsupported expressions fail compilation.

There is no SpEL or OGNL. Computation belongs in the page model.

## Modules

- `runtime`: dependency-free Java output API
- `compiler`: build-time Java and Kotlin type analysis
- `spring`: Java Spring MVC adapter
- `gradle-plugin`: Java and Kotlin build integration
- `example`: Spring Boot application

See [DESIGN.md](DESIGN.md) for the architecture.
