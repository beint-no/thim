# Thim

Thim is a compile-time-safe server-side HTML renderer for Java and Kotlin applications. It validates templates against page models, messages and Spring routes, then generates direct Java renderers. Static HTML is stored as UTF-8 bytes and dynamic values are encoded for their output context.

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
    id("no.beint.thim") version "0.4.19"
}
```

Java modules need only the Java and Thim plugins:

```kotlin
plugins {
    java
    id("no.beint.thim") version "0.4.19"
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
    generateRoutes.set(true)
}
```

The default model package is `<project group>.page`. Nested template names are part of the class name: `error/404.html` resolves to `Error404Page`. Fixed `th:replace` fragments and layouts are linked and inlined during compilation; fragment libraries need no page model.

`strictTemplates` requires every page template to have a matching model. Unused fragments and fragment parameters are reported, and `failOnUnusedFragments` promotes unused-fragment warnings to errors. Enable `failOnUnusedMessages` only when the configured bundles are owned entirely by compiled templates.

`strictModels` requires immutable, render-only data. It rejects mutable or unused properties, `Any`/`Object`, maps, raw or lazy collections, and persistence entities.

Complete documents are checked for duplicate ids and broken `label`, ARIA and local-anchor references. Repeated static ids warn. Templates without an `<html>` root are treated as partials, so document-wide references are not checked.

Use `thimCheck` for fast template validation during development:

```shell
./gradlew thimCheck
./gradlew thimCheck --continuous
```

`thimCheck` runs the same validation as normal compilation. Java modules also write a report to `build/reports/thim/check.json`.

## Typed controller routes

Kotlin applications can opt into controller-side route builders with `generateRoutes.set(true)`. The generated object is placed beside the template registry and replaces a `Templates` suffix with `Routes`: `WebAppTemplates` produces `WebAppRoutes`. Set `routesName` to override it.

```kotlin
val settings = WebAppRoutes.connections(
    additionalQueryParameters = mapOf("workspaceId" to 11, "connectWarning" to "partial"),
)
val campaign = WebAppRoutes.adsGoogleCampaign(campaignId = "42", customerId = "123")
```

Thim emits one function per distinct path pattern. Names come from literal path segments; path variables are omitted from the usual name and receive a `ById`-style suffix when needed to resolve a collision. A path variable is required and retains the controller parameter's scalar or enum type. Query parameters are nullable with a `null` default and are omitted when null. Values are percent-encoded with the same encoder used by `@{...}`.

Spring mapping metadata does not contain arbitrary query parameters. Thim includes parameters declared with `@RequestParam` as named arguments. URL-only parameters that are consumed indirectly, such as flash-message selectors or application-wide request context, can be supplied through `additionalQueryParameters`:

```kotlin
WebAppRoutes.connections(
    additionalQueryParameters = mapOf("workspaceId" to 11, "connectWarning" to "partial"),
)
```

Route generation is opt-in and currently available for Kotlin applications.

## Controller return values

A controller that always renders should return its page model directly. A controller that can render or redirect can use `ThimResult` for an explicit return type while page models remain dependency-free:

```kotlin
fun select(): ThimResult =
    if (failed) {
        ThimResult.Redirect(
            WebAppRoutes.connections(
                additionalQueryParameters = mapOf("connectWarning" to "connectionFailed"),
            ),
        )
    } else {
        ThimResult.Page(ConnectApiPage(/* ... */))
    }
```

`ThimResult.Page.model` has type `Any`, so page models remain dependency-free. The Spring adapter renders `Page` and sends the path in `Redirect` as an HTTP redirect.

Existing handlers declared as Kotlin `Any` or Java `Object` remain supported, but `ThimResult` documents mixed page/redirect outcomes more clearly.

Artifacts are published through `https://maven.pkg.github.com/beint-no/thim`. Add that repository to `pluginManagement` and dependency resolution.

## Template syntax

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

Every dynamic value is encoded for its output context. Use static `@{...}` expressions or `TrustedUrl` for URLs, and use `SafeHtml` only with `th:utext`. Dynamic JavaScript, CSS and event-handler content is rejected.

Missing models, properties, messages and routes; unsafe nullable access; malformed HTML; and unsupported output contexts fail compilation. Prepare computed display values in the page model.

## Modules

- `runtime`: dependency-free Java output API
- `compiler`: build-time Java and Kotlin type analysis
- `spring`: Java Spring MVC adapter
- `gradle-plugin`: Java and Kotlin build integration
- `example`: Spring Boot application

See [DESIGN.md](DESIGN.md) for the architecture.
