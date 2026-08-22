# Thim

Thim is a compile-time-safe server-side HTML renderer for Java and Kotlin applications. It validates templates against page models, messages and Spring routes, then generates direct Java renderers. Static HTML is stored as UTF-8 bytes and dynamic values are encoded for their output context.

The marketing site is at [beint-no.github.io/thim](https://beint-no.github.io/thim/).

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
    id("no.beint.thim") version "0.7.5"
}
```

Java modules need only the Java and Thim plugins:

```kotlin
plugins {
    java
    id("no.beint.thim") version "0.7.5"
}
```

The plugin supplies the dependency-free runtime and the build-time compiler, and tracks templates and messages as compilation inputs. When the Spring Boot plugin is present it also adds the Spring MVC adapter automatically, regardless of plugin application order. Plain Spring applications can opt in explicitly with `implementation("no.beint.thim:spring:0.7.5")`. Compiled template jars publish their registries through Java's service loader, so templates can live in any application module.

```kotlin
thim {
    generatedPackage.set("your.group.your_module.thim.generated")
    registryName.set("ThimTemplates")
    modelPackages.set(listOf("no.example.page"))
    failOnUnusedMessages.set(true)
    generateRoutes.set(true)
}
```

Thim deliberately owns its default source layout: templates go in `src/main/resources/templates`, and message catalogs go in `src/main/resources/i18n`. Supported locales are inferred from the locale directories. Set `defaultLocale` only when it is not `en`. Override a source directory only for a migration or generated-source workflow.

The default model package is `<project group>.page`. Nested template names are part of the class name: `error/404.html` resolves to `Error404Page`. Fixed `th:replace` fragments and layouts are linked and inlined during compilation; fragment libraries need no page model.

Every page template must have a matching model by default. Set `strictTemplates` to `false` only while Thim and a runtime template engine intentionally share a template directory. Unused fragments and fragment parameters are reported, and `failOnUnusedFragments` promotes unused-fragment warnings to errors. Enable `failOnUnusedMessages` only when the configured bundles are owned entirely by compiled templates.

Page models are strict by default: they must be immutable, render-only data. Thim rejects mutable or unused properties, `Any`/`Object`, maps, `MutableList`/`MutableSet` and other mutable collection types, raw or lazy collections, and persistence entities.

In strict mode, templates and message catalogs are compiler inputs and the Gradle plugin omits them from runtime resources. With `strictTemplates=false`, templates remain available to the runtime engine during migration; message catalogs are still compiled into renderers and omitted.

For gradual migration, keep the conventional template directory shared and configure only the exception:

```kotlin
thim {
    strictTemplates.set(false)
}
```

Migrate one controller and page model at a time. Copy the messages used by that page into the YAML catalog; keep legacy catalog entries temporarily when the old engine or an existing catalog linter still needs them. Once every page is compiled, remove the runtime engine and the `strictTemplates` override.

Complete documents are checked for duplicate ids and broken `label`, ARIA and local-anchor references. Repeated static ids warn. Templates without an `<html>` root are treated as partials, so document-wide references are not checked.

Use `thimCheck` for fast template validation during development:

```shell
./gradlew thimCheck
./gradlew thimCheck --continuous
```

`thimCheck` runs the same validation as normal compilation. Java modules also write a report to `build/reports/thim/check.json`.

## Message catalogs

Thim compiles localized YAML catalogs into the generated renderer. SnakeYAML Engine is a compiler dependency only; parsing, key lookup and pattern interpretation never happen at request time.

The directory name is a canonical BCP 47 language tag. The relative YAML filename and nested mappings form the message namespace:

```text
src/main/resources/i18n/
├── en/
│   └── home.yaml
└── nb/
    └── home.yaml
```

Thus `home.yaml` owns the `home.*` namespace, while `account/profile.yaml` owns `account.profile.*`. Namespace collisions, inconsistent locale trees and invalid locale names fail compilation.

```yaml
# en/home.yaml
title: Thim {version}
introduction: |-
  Compile templates and translations together.
  Ship no runtime template engine.
inbox:
  _plural: unreadCount
  one: One unread message
  other: "{unreadCount} unread messages"
salutation:
  _select: audience
  MEMBER: Welcome back, {name}
  other: Welcome, {name}
```

Use named model properties in the template:

```html
<title th:text="#{home.title(version=${version})}">Thim</title>
<p th:text="#{home.inbox(unreadCount=${unreadCount})}">Unread messages</p>
```

`_plural` accepts the locale's reachable subset of `zero`, `one`, `two`, `few`, `many` and the required `other` category. Its argument must be a non-null integral property. `_select` requires a non-null string or enum and also requires `other`; enum variants must name real enum constants. Selections can be nested. All interpolated values are HTML-escaped; catalogs cannot produce raw HTML. Write `{{` or `}}` for a literal brace.

Every discovered locale must contain the same relative `.yaml` files, message keys and argument contracts. The default locale defines the contract. Missing translations, extra keys, misspelled placeholders, incompatible argument types and unused messages (when enabled) fail compilation. At runtime Thim chooses an exact available language tag, then an available language-only tag, then the default locale.

### Typed backend messages

Applications can use the same compiled catalog outside templates without runtime string keys or a second message bundle:

```kotlin
thim {
    generateMessages.set(true)
    messagesName.set("WebAppMessages")
}
```

Thim generates one factory per catalog key. The first namespace component becomes a nested class, and remaining components form the method name:

```kotlin
val title = WebAppMessages.Home.title(version).resolve(locale)
val inbox = WebAppMessages.Home.inbox(unreadCount).resolve(locale)
```

Keys, argument names, argument kinds, locale branches, selects and plural rules are all compiled. Renaming a key or changing its arguments therefore breaks consumers at compilation rather than at runtime. Factories return `CompiledMessage`, which lets application code supply its current locale through a small framework-specific adapter. Resolution returns plain text, not HTML; generated template renderers still escape the result for its output context.

Argument-free messages also expose compile-time constant references for APIs such as Jakarta Bean Validation annotations, where Java only permits constant annotation arguments. For example, `message = WebAppMessages.Validation.requiredReference` produces a generated `{thim:validation.required}` reference. Framework integration can recognize it with `WebAppMessages.isReference(...)` and resolve it through `WebAppMessages.resolveReference(..., locale)`. Removing the catalog entry or adding arguments removes the generated constant and breaks the consumer at compilation.

The generated class defaults to the registry name with a `Templates` suffix replaced by `Messages`, so `WebAppTemplates` produces `WebAppMessages`. Set `messagesName` to override it. Enabling this public backend API exports the full catalog, so its entries count as used when `failOnUnusedMessages` is enabled.

Catalogs use a deliberately small YAML 1.2 profile: mappings and string scalars only. The failsafe schema means plain `no`, `true`, `12` and `2026-08-08` remain text. Duplicate keys, tags, anchors, aliases, sequences, multiple documents, empty catalogs and non-YAML files are rejected. Block scalars are supported for multiline copy. Only the lowercase `.yaml` extension is accepted.

Integer cardinal rules derived from [Unicode CLDR 49](https://unicode.org/cldr/charts/49/supplemental/language_plural_rules.html) are embedded for `af`, `bg`, `bs`, `ca`, `cs`, `cy`, `da`, `de`, `el`, `en`, `eo`, `es`, `et`, `eu`, `fi`, `fo`, `fr`, `ga`, `gd`, `gl`, `hr`, `hu`, `is`, `it`, `lt`, `lv`, `nb`, `nl`, `nn`, `no`, `pl`, `pt`, `ro`, `sk`, `sl`, `sq`, `sr`, `sv` and `sw`. A catalog that uses `_plural` with another language fails compilation rather than guessing.

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

Artifacts and the Gradle plugin marker are published to Maven Central. Add `mavenCentral()` to `pluginManagement` and dependency resolution.

## Template syntax

Thim accepts:

- `${property}` and null-safe property paths (not `Object` methods such as `toString`)
- `th:text` of String, number, Boolean, enum, UUID, or `java.time` values
- `th:each`
- `th:if` and `th:unless`
- fixed, build-time `th:fragment` and `th:replace` composition
- property, message, static URL and quoted-literal values on ordinary `th:*` attributes
- `no.beint.thim.TrustedUrl` properties on URL attributes such as `th:href`, `th:src` and `th:action` (not `javascript:` or blank values)
- conditional HTML boolean attributes
- literal `#{message(argument=${property})}` expressions with typed, named arguments
- `lang` on `<html>` from the request locale when the attribute is omitted

Every dynamic value is encoded for its output context. Use static `@{...}` expressions or `TrustedUrl` for URLs, and use `SafeHtml` only with `th:utext`. Dynamic JavaScript, CSS and event-handler content is rejected.

Missing models, properties, messages and routes; unsafe nullable access; malformed HTML; nested forms; duplicate fragments; and unsupported output contexts fail compilation. Prepare computed display values in the page model.

## Modules

- `runtime`: dependency-free Java output API
- `compiler`: build-time Java and Kotlin type analysis
- `spring`: Java Spring MVC adapter
- `gradle-plugin`: Java and Kotlin build integration
- `example`: Spring Boot application
- `benchmark`: generated-renderer fixtures, regression tests, and JMH benches

## Performance tests

The `benchmark` module renders compiled inbox, catalog, and mostly-static pages, plus the `HtmlOutput` hot paths. `./gradlew build` runs the JUnit suite: it checks output, measures median nanoseconds per render after warmup, and fails if a case is slower than the committed ceiling in `benchmark/src/test/resources/render-baselines.properties`.

Ceilings are intentionally loose so CI noise does not fail the build. Tighten them after a real improvement. Print the measured times with:

```shell
./gradlew :benchmark:test --info
```

For a longer local run that is better at guiding speed work:

```shell
./gradlew :benchmark:jmh
```

See [DESIGN.md](DESIGN.md) for the architecture.
