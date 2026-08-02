# Thim

Thim is a strict, Kotlin-first AOT renderer for a deliberately small subset of Thymeleaf template syntax.

Templates are parsed and type-checked by KSP, then turned into direct Java renderers. The runtime never parses templates, resolves property names, invokes methods reflectively or evaluates expressions, and generated renderers do not depend on Kotlin.

Thim requires JDK 26 or newer, Kotlin 2.4 and Spring Framework 7 when using the optional MVC adapter.

## Language

Thim currently accepts:

- `${property}` and null-safe property paths
- `th:text`
- `th:each`
- `th:if` and `th:unless`
- property, message, static URL and quoted-literal values on ordinary `th:*` attributes
- conditional HTML boolean attributes
- literal `#{message}` expressions with typed arguments

Legacy expression evaluation and semantic attributes such as `th:attr`, `th:field`, `th:object`, `th:replace`, `th:switch`, `th:utext` and `th:with` are compilation errors. Missing properties, unsafe nullable access, missing messages, locale drift, duplicate or unused messages, invalid message arguments and unsupported expressions also fail compilation.

```kotlin
@Thim("home")
data class HomePage(
    val greeting: String,
    val features: List<Feature>,
)

@GetMapping("/")
fun home() = HomePage("Hello", features)
```

The generated `TemplateSet` is connected to Spring MVC with `ThimWebMvcConfigurer`. Controllers return typed page models directly; there is no view name or string-keyed model.

Run the example with:

```shell
./gradlew :example:bootRun
```

## Relationship to Thymeleaf

Thim is an independent clean-room implementation. It contains no Thymeleaf source code and has no runtime dependency on Thymeleaf. It implements a source-compatible subset of familiar Thymeleaf HTML attributes so existing templates can migrate incrementally. It is not affiliated with the Thymeleaf project and does not claim full Thymeleaf compatibility.
