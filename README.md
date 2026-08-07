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
    generateRoutes.set(true)
}
```

The default model package is `<project group>.page`. Nested template names are part of the class name: `error/404.html` resolves to `Error404Page`. Fixed `th:replace` fragments and layouts are linked and inlined during compilation; fragment libraries need no page model.

`strictTemplates` rejects every non-fragment template without a matching page model. Fragments never reached from any compiled page are reported as warnings, and `failOnUnusedFragments` turns them into errors once Thim owns every template; fragment parameters the fragment body never reads always warn. `failOnUnusedMessages` is suitable when the configured message bundles are owned entirely by the compiled templates. Leave it disabled for a bundle also used by backend code unless that usage is checked separately.

`strictModels` opts page models into a closed-world contract for render-only data. A strict model and every type reachable from its properties must be immutable data prepared for rendering: mutable properties and setters are rejected, along with `Any`/`Object` properties, map-shaped data, raw collections, and `th:each` over collections that are not materialized (`List`, `Set`, `Collection`, or an array). Types annotated with a persistence annotation are rejected so entities and lazy collections never reach a renderer; the default list covers JPA (`@Entity`, `@Embeddable`, `@MappedSuperclass` in both `jakarta.persistence` and `javax.persistence`) and can be replaced through `forbiddenModelAnnotations`. Model properties no expanded template reads are reported as warnings; `failOnUnusedProperties` turns them into errors. Keep it disabled for models whose properties are also consumed by backend code.

Expanded pages with an `<html>` root are additionally validated as complete documents. Duplicate ids are rejected, including the ids `th:field` generates, and `label for`, `aria-labelledby`, `aria-describedby`, `aria-controls`, and local `href="#..."` anchors must reference an id that exists in the page. A static id or a `th:field` inside `th:each` is reported as a warning because repeated output is likely to duplicate the id; use `data-*` attributes or an id on the container instead. Templates without an `<html>` root are HTMX partials rendered into an existing document, so their references are not validated. A dynamic `th:id` is allowed for data-driven targets, but it never satisfies these checks: a static reference must resolve to a static or `th:field`-generated id, and duplication among static ids stays an error regardless of what `th:id` renders.

Use `thimCheck` for fast template validation during development:

```shell
./gradlew thimCheck
./gradlew thimCheck --continuous
```

`thimCheck` uses the same parser, fragment expansion, type analysis, message validation and safety checks as normal compilation. Java modules run the production compiler into isolated check outputs and write a cacheable machine-readable report to `build/reports/thim/check.json`. Kotlin modules delegate to the KSP task configured by the plugin, so diagnostics match ordinary Kotlin compilation.

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

Route generation is opt-in so an upgrade within the 0.4 line cannot break an existing build because two application paths derive the same Kotlin name. The generated source is aggregating and declares every controller source in `RouteCatalog.files` as a KSP dependency, so incremental compilation always rebuilds it from the complete mapping table. Route builders are currently Kotlin-only; Java applications continue to receive the Java template registry.

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

`ThimResult.Page.model` deliberately has type `Any`: requiring a marker interface would add a Thim dependency to every page model without giving KSP visibility into construction sites. The Spring adapter unwraps a page result into the normal compiled renderer and handles a redirect with `HttpServletResponse.sendRedirect`.

Using Kotlin `Any` or Java `Object` as the declared return type remains supported for source compatibility. Spring selects a return-value handler using a `MethodParameter` that reports the runtime value's class: a page model reaches `ThimReturnValueHandler`, while a `String` reaches Spring's view-name handler. If the value is null, Spring falls back to the declared `Object` type. Generated registries therefore deliberately reject `Object.class` in `supportsReturnType`; that guard prevents Thim from attempting to render a missing value and does not disable non-null `Any` handlers.

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
