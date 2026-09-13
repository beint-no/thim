# Typed HTML components

This is a breaking replacement for fragment composition. `th:fragment`, `th:replace`,
`th:insert` and `th:include` fail compilation, including in templates excluded by
`strictTemplates=false`. There is no compatibility renderer or second composition path.
Consumers must migrate before upgrading to the release containing this change.

## Contract and invocation

A component occupies one HTML file. Its name is an explicit, module-local identifier;
file paths do not become public component names. Names use lowercase kebab-case.

```kotlin
package example.ui

data class NoticeProps(
    val title: String,
    val tone: Tone = Tone.INFO,
)

enum class Tone { INFO, WARNING, DANGER }
```

```html
<!-- components/notice.html -->
<ui:component name="notice" props="example.ui.NoticeProps">
    <aside class="notice" th:data-tone="${tone}">
        <h2 th:text="${title}"></h2>
        <ui:slot required></ui:slot>
        <footer><ui:slot name="actions"></ui:slot></footer>
    </aside>
</ui:component>
```

Prepare a `NoticeProps` property in the page model, then use it:

```html
<ui:notice props="${notice}">
    <p th:text="${description}"></p>
    <ui:fill name="actions">
        <a th:href="${helpUrl}" th:text="#{help}"></a>
    </ui:fill>
</ui:notice>
```

`props` is a property-path expression whose non-null type must be assignable to the
component's declared model. The compiler checks the component body against that declared
type independently of callers, including when the component is unused. Java records
work too. Contracts must be concrete, non-generic classes. Existing strict model checks
apply to component models as well as page models.

Kotlin/Java owns property construction: required constructor arguments, enum values,
nullability, defaults and IDE refactoring work without a second type declaration in HTML.
No props object is constructed by the generated renderer. A call evaluates its supplied
model once and uses a typed local variable. It does not copy properties or invoke a
runtime component factory.

This intentionally takes a whole model rather than accepting individual attribute
arguments. That adds model preparation for small data-bearing components, but avoids a
second constructor language, duplicated defaults and template-side business logic.
Messages can remain in the component body or caller slots; dynamic URLs retain
`TrustedUrl` or `@{...}` validation at their output sites. Components are not restricted
to static HTML: their body can contain interactive web components, forms and other
ordinary HTML supported by Thim.

## Slots and scope

- `<ui:slot>` declares the default slot. Ordinary children of the call fill it.
- `<ui:slot name="actions">` declares a named slot. Direct `<ui:fill name="actions">`
  children of the call supply it. An explicit empty fill suppresses fallback content.
- `required` is a valueless attribute. Omitting a required slot is a compile error.
- An optional slot may contain fallback markup, evaluated in the component's scope.
- Unknown slots, duplicate fills and duplicate slot declarations are errors. Each slot
  has one insertion site; repeat inside a loop when repetition is intentional.
- The component body sees only its own model and local loops. It cannot accidentally
  capture page properties, a caller's loop variable or a caller's selected form object.
- Supplied markup retains its caller's model, loop bindings and form selection. Nested
  forwarding preserves those bindings; markup is represented as compiler nodes, never
  `SafeHtml`, string replacement or a runtime closure.

Forward a slot by placing its declaration inside a child component call:

A wrapper declares `WarningProps(val notice: NoticeProps)` and forwards content this way:

```html
<ui:component name="warning" props="example.ui.WarningProps">
    <ui:notice props="${notice}">
        <ui:slot required></ui:slot>
        <ui:fill name="actions"><ui:slot name="actions"></ui:slot></ui:fill>
    </ui:notice>
</ui:component>
```

The `ui:*` elements disappear from rendered HTML. They are unrelated to browser custom
elements or shadow-DOM `<slot>` elements. Whitespace inside supplied content is retained;
formatting whitespace surrounding named fills is ignored when there is no default body.

## Layouts, static components and attributes

A component without data omits `props` on both its declaration and calls:

```html
<ui:component name="site-layout">
    <!doctype html>
    <html>
    <head><meta charset="utf-8"><ui:slot name="head" required></ui:slot></head>
    <body><main><ui:slot required></ui:slot></main></body>
    </html>
</ui:component>
```

```html
<ui:site-layout>
    <ui:fill name="head"><title th:text="#{home.title}"></title></ui:fill>
    <h1 th:text="${heading}"></h1>
</ui:site-layout>
```

Calls accept only `props`, `th:each`, `th:if` and `th:unless`. The latter three execute
in the caller's scope, in Thim's normal loop/condition order. Null models are rejected;
Boolean conditions do not introduce nullable smart casts. Supply a non-null model, or
iterate a prepared zero-or-one list for optional data-bearing components.

Arbitrary HTML attributes on a component call are errors. There is no implicit root,
attribute spread, silent loss of classes, or event-handler forwarding. A component that
accepts an id, URL, class or variant declares it in its model and writes it explicitly
at the appropriate element. Multi-root components follow the same rule.

Slots describe structured markup, not HTML content categories. The compiler cannot
prove that every slot use is accessible or semantically valid HTML. Existing document,
URL, form and contextual escaping checks remain in force on the composed page.

## Migration

1. Turn each fragment into a standalone `<ui:component name="…">` file. Remove its
   `th:fragment` wrapper attribute. The declaration itself produces no markup.
2. Replace scalar argument lists with a named Kotlin data class or Java record. A
   fragment already accepting one object can use that object's type directly. Update
   expressions to read its properties directly, e.g. `${feature.name}` becomes `${name}`.
3. Replace `th:replace="~{file :: fragment(...)}"` with a `<ui:name props="${…}">` call.
   Conditions and loops belong on that call or a surrounding `th:block`.
4. Replace fragment-valued parameters with default/named slots and inline their caller
   content. Use a component for markup reused at several call sites.
5. Replace ambient layout variables with explicit model properties or caller slots.
   This is a compiler-enforced boundary, so some old templates require deliberate model
   design rather than a mechanical search-and-replace.
6. Rename `failOnUnusedFragments` to `failOnUnusedComponents`. It defaults to true;
   false permits unused components with warnings but still validates their bodies.
7. Run the consumer's build and check rendered pages, translations, form submissions
   and partial responses. Do not change the library version until that migration is ready.

The component registry is local to the compilation's template source directory. There
are no dynamic names, recursive components or component calls across compiled module
registries. Model types can come from dependencies. Independently rendered HTMX responses
continue to use ordinary typed page templates; they are not a second composition syntax.

ReAI, Eteo and Utin are deliberately outside this PR. Their upgrades require separate
consumer PRs after evaluation; none is silently opted into this breaking change.

## Implementation and tradeoffs

`ComponentLinker` validates declarations, names, slots and cycles, then links structured
`ComponentNode` and `SlotNode` values. Renderer generation binds typed local scopes; it
never rewrites expression strings. Definitions are validated separately, using a separate
static byte store so validation cannot bloat the published renderer resource.

The output still consists of direct Java writes and shared static UTF-8 bytes. Components
and slots have no runtime lookup, browser upgrade, wrapper element, reflection, slot object
or closure allocation. Large bodies use the existing renderer partitioning, carrying the
component model and caller bindings into generated helper methods.

The old fragments already compiled away. Faster browser rendering is therefore not an
expected benefit of this library change alone. The main gains are isolated contracts,
readable calls, reliable scope and one composition mechanism. Compiler work and generated
code shape change; benchmarks must establish any performance improvement rather than
assuming it from shorter syntax. Preparing additional model objects can cost allocations
in consumer code, outside the renderer, and full inlining still grows generated code
with the number of calls.

## Measurements and verification

Measured on an Apple M5 Max, macOS, OpenJDK 26.0.2.1, against baseline `27e4016`.
JMH used two forks, three one-second warmups and five one-second measurements per fork,
with the GC profiler. Raw results are in
[benchmark/results/typed-components](benchmark/results/typed-components).

| Measurement | Before | Components |
| --- | ---: | ---: |
| Link/expand 100 calls | 0.0683 ms/op | 0.0390 ms/op |
| Allocation for 100 calls | 345,088 B/op | 201,048 B/op |
| Link/expand 1,000 calls | 0.6561 ms/op | 0.3845 ms/op |
| Allocation for 1,000 calls | 3,191,846 B/op | 1,986,248 B/op |
| Render 50 catalog items | 7.914 µs/op | 7.950 µs/op |
| Renderer allocation | 1,224 B/op | 1,224 B/op |

Linking the synthetic fixtures was about 41–43% faster with 38–42% less allocation.
These are **phase measurements**, not total compiler/build times: parsing, KSP type
resolution, independent component-body validation and Java compilation are excluded.
The old benchmark includes unused fragment-parameter scanning; the new one checks unused
components and moves property checking into typed renderer validation. Do not interpret
the phase improvement as an equivalent build-time saving.

The catalog compares the previous handwritten loop body with the same body in a typed
component, using the same prepared data and rendered whitespace. The renderer timings
are effectively unchanged within measurement error (before ±0.157 µs; after ±0.050 µs).
The component adds no measured renderer allocation. Data preparation is outside timing;
consumers creating extra component models must account for that separately.

Reproduce on the respective checkout after `./gradlew :benchmark:jmhJar`:

```shell
java -jar benchmark/build/libs/benchmark-0.11.0-jmh.jar \
  'TemplateCompilerBenchmark.linkComponents|RendererBenchmark.catalogFifty' \
  -wi 3 -i 5 -f 2 -prof gc -rf json -rff results.json
```

For baseline `27e4016`, replace `linkComponents` with `expandFragments`. The jar name
reflects the current development version; use the built version after a release bump.

Verification includes the full Gradle build, existing renderer output/performance checks,
and temporary checks (not committed) for:

- Kotlin contracts and Java records, escaped text, trusted URLs and caller/component loop
  variable collisions through nested slot forwarding and generated helper methods.
- Missing, unknown and duplicate slots; recursion; malformed declarations; unknown calls
  and attributes; and removed fragment syntax.
- Compiler rejection of wrong/nullable models, ambient caller-property capture, unsafe
  URL types, invalid unused component bodies and duplicate ids in a composed document.

The example application and catalog benchmark are migrated. The catalog output is byte-for-byte identical in English and Norwegian. The example starts successfully and its migrated feature list was checked in the built-in browser; the test server was stopped afterward. No consumer application is
upgraded or deployed by this PR. These measurements do not claim browser or low-end-device
improvements from changing an already compiled template composition mechanism.
