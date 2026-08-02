# Design

## Objective

Thim optimizes for four properties in order:

1. template mistakes fail the application build;
2. request-time work is explicit generated Java code;
3. Kotlin page models retain their real nullability and generic types;
4. the runtime and language remain small enough to understand completely.

Compatibility is useful only where it does not weaken those properties. Familiar `th:*` syntax is cheap to retain because it disappears during compilation. The dynamic Thymeleaf execution model is deliberately not retained.

## Compilation pipeline

1. The Gradle plugin declares HTML and message bundles as relative task inputs.
2. KSP finds templates containing `@thim-model` declarations and resolves those Kotlin types.
3. The compiler parses HTML, resolves every supported expression, validates nullability and validates every localized message bundle.
4. It emits readable Java renderer source and one package-local binary resource containing all static UTF-8 content.
5. `javac` produces ordinary named classes. Each template jar publishes its registry through Java's service-provider mechanism.
6. A request constructs one buffered `HtmlOutput`, writes static byte ranges and encodes escaped dynamic values directly into that buffer.

Template-only and message-only edits therefore invalidate compilation. This is essential: relying only on KSP source inputs would incorrectly leave generated renderers up to date.

## Java and Kotlin boundary

The runtime, generated renderers, Spring adapter and Gradle plugin are Java. Kotlin is present only in the compiler because the applications being targeted use Kotlin page models.

Rewriting the KSP processor in Java would not remove Kotlin from its build-time classpath: KSP exposes a Kotlin symbol model and ships Kotlin APIs. It would also have no effect on runtime throughput or footprint. Keeping the compiler implementation in Kotlin makes the type analysis smaller while preserving a Java-only request path.

A pure post-compilation Java analyzer was prototyped with the JDK Class-File API. Ordinary JVM signatures expose method descriptors and top-level annotations, but they do not preserve all Kotlin type information such as nullable generic elements. Recovering that information requires Kotlin metadata. Losing it would make `${items}` and loop element access less safe precisely where compile-time analysis is most valuable.

## Why generated Java instead of direct class files

The standardized Class-File API is excellent for parsing, transforming and generating class files. It does not make equivalent bytecode execute faster than bytecode emitted by `javac`. Direct generation here would add stack-map, descriptor, debugging and verification responsibilities while making generated output harder to inspect.

Readable generated Java gives the JIT and Leyden the same named classes, methods and branches. The Class-File API remains a possible future tool for inspecting application binaries or creating a final link-time index, not for the normal renderer generator.

## Output design

The first implementation used `Appendable`, `BufferedWriter` and `OutputStreamWriter`. That caused static HTML to occupy Java string constants and required every character to pass through a charset encoder on every request.

The current design stores static HTML in a `.bin` resource and loads it once per generated registry. Dynamic values are escaped and UTF-8 encoded directly into a 16 KiB byte buffer. It creates no intermediate escaped strings and bypasses charset conversion for static content.

An isolated JDK 26 benchmark rendering 100,000 rows with Norwegian characters, emoji and all common HTML escapes measured a median 15.5 million rows/second for `HtmlOutput` versus 5.48 million for the former Writer pipeline, or 2.83 times the throughput. This is a focused encoder benchmark rather than an HTTP throughput claim.

## Language boundary

Keeping the supported Thymeleaf attribute names has no runtime cost and makes incremental migration practical. Keeping Thymeleaf expression semantics would require a runtime evaluator, coercion rules, reflective dispatch and a much larger compatibility surface.

The model declaration is explicit because conventions such as `HomePage` mapping to `home.html` become ambiguous in real codebases containing pagination types, API response pages and nested modules. Placing the declaration in the template avoids modifying controllers or models and makes the template's input contract visible where expressions are written.

Giving up more source compatibility is worthwhile only for features that remain statically composable. A future layout/include facility should therefore link fixed templates and typed slots at compile time. It should not reproduce dynamic fragment selection or a general expression language.

## Spring boundary

The compiler and runtime do not depend on Spring. The MVC adapter discovers every generated `TemplateSet` on the application classpath, recognizes page-model return types and writes the response. This supports templates compiled in any number of feature modules without an application-level registry.

Spring Boot auto-configuration lives in the Spring adapter rather than generated application code. A non-Boot application can instantiate `ThimWebMvcConfigurer` or its generated `TemplateSet` directly.

## JDK evolution

Thim targets released JDK 26 APIs and enables no preview or incubator feature. Preview dependencies would force every consuming application to compile and run with `--enable-preview`, complicate deployment, and can change or disappear between releases.

JDK 27 should be evaluated after its final release. No currently proposed JDK 27 feature changes the renderer's fundamental cost model. Project Leyden improvements are complementary: generated renderer classes and their static resources are normal application artifacts and can benefit from AOT class loading, linking, cached objects and future AOT compilation without a Thim-specific format.

The durable optimization boundary is therefore source generation plus simple byte output, not a dependency on a particular preview feature or VM implementation.
