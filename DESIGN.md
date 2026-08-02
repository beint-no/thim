# Design

## Objective

Thim optimizes for four properties:

1. template mistakes fail the application build;
2. request-time work is explicit generated Java code;
3. Java and Kotlin page models keep their source-level types;
4. the runtime and language remain small enough to audit completely.

## Compilation

1. The Gradle plugin tracks HTML, message bundles and model sources.
2. KSP resolves a page-model class from the template filename and configured model packages.
3. The compiler links fixed layouts and fragments, then validates properties, nullability, localized messages and supported directives.
4. It emits readable Java renderers and one package-local resource containing static UTF-8 content.
5. Each template jar publishes its generated registry through Java's service loader.

Kotlin modules use the KSP Gradle integration. Java modules run KSP2 directly against Java sources, including records and bean accessors. Both paths call the same compiler and generate the same runtime code.

## Runtime

The runtime, generated renderers, Spring adapter and Gradle plugin are Java. A request creates one buffered `HtmlOutput`, copies static byte ranges and encodes escaped dynamic values directly into that buffer. It creates no intermediate escaped strings and performs no character conversion for static content.

The generated code consists of ordinary named classes, methods and branches. Generating class files directly would produce equivalent VM instructions while making output harder to inspect and maintain.

## Language

The template language is intentionally limited to statically resolvable property paths, conditions, iteration, attributes, URLs and messages. It has no runtime evaluator, reflective dispatch, coercion rules or dynamic template selection.

Layouts and fragments link fixed templates and typed values during compilation. Composition is erased from the request path and cannot introduce runtime template lookup or a general expression language.

## Frameworks and JDK

The compiler and runtime do not depend on Spring. The MVC adapter recognizes page-model return types and writes the response. A non-Spring application can call its generated `TemplateSet` directly.

Thim targets released JDK 26 APIs. It uses no preview or incubator feature, so applications do not need `--enable-preview` and generated classes remain compatible with future JVM AOT and startup improvements.
