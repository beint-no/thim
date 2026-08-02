# Thim

Thim is a strict AOT HTML renderer for Java and Kotlin applications. It compiles typed templates into direct Java renderers.

There is no template engine, expression language, reflection, property lookup, message lookup or character encoder in the request path. Static HTML is stored once as UTF-8 bytes; generated code writes those bytes and escaped dynamic values directly to the response.

Thim requires JDK 26 or newer. Its optional MVC adapter targets Spring Framework 7 and Spring Boot 4.

## Use

Put the page model on the template root:

```html
<!doctype html>
<html thim:model="no.example.web.HomePage">
<body>
    <h1 th:text="#{home.title}">Home</h1>
    <p th:text="${greeting}">Greeting</p>
</body>
</html>
```

Return the model directly from a controller. Kotlin data classes and Java records are both supported:

```kotlin
data class HomePage(val greeting: String)

@GetMapping("/")
fun home() = HomePage("Hello")
```

```java
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
    id("no.beint.thim") version "0.3.1"
}
```

Java modules need only the Java and Thim plugins:

```kotlin
plugins {
    java
    id("no.beint.thim") version "0.3.1"
}
```

The plugin supplies the runtime, compiler and Spring adapter and tracks templates and messages as compilation inputs. Compiled template jars publish their registries through Java's service loader, so templates can live in any application module.

```kotlin
thim {
    templates.set(layout.projectDirectory.dir("src/main/resources/templates"))
    messages.set(layout.projectDirectory.dir("src/main/resources"))
    generatedPackage.set("your.group.your_module.thim.generated")
    registryName.set("ThimTemplates")
}
```

Artifacts are published through `https://maven.pkg.github.com/beint-no/thim`. Add that repository to `pluginManagement` and dependency resolution.

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

There is no SpEL or OGNL. Computation belongs in the page model.

## Modules

- `runtime`: dependency-free Java output API
- `compiler`: build-time Java and Kotlin type analysis
- `spring`: Java Spring MVC adapter
- `gradle-plugin`: Java and Kotlin build integration
- `example`: Spring Boot application

See [DESIGN.md](DESIGN.md) for the architecture.
