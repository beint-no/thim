# Thim performance audit — 5 September 2026

Thim already has a sound performance architecture: templates and messages are compiled,
static HTML is stored as UTF-8 bytes, and the runtime has no external dependencies.
The clearest improvements are removing repeated work inside the compiler.

## Scope and evidence

- Library baseline: `f0fe2fe`, the current main checkout. ReAI declares Thim `0.10.0`;
  that release is tagged at `f2f21c2`. The parser, fragment expander, and integer formatter
  being optimized are unchanged between those two commits.
- Application source inspected: ReAI `ae87edd9e`, including its Thim configuration,
  templates, and servlet/email rendering use sites. ReAI itself was not changed.
- Corpus: all 388 HTML files in ReAI's `web-app/src/main/resources/templates`.
  The independent fragment benchmark uses the 136 templates without fragment
  declarations. It does not resolve page models, so this is a subset of ReAI's pages.
- Measurement machine: Apple M5 Max, arm64, OpenJDK `26.0.2.1`.
- JMH: two separate JVM forks, three one-second warmup iterations and five one-second
  measurement iterations per fork, with the GC profiler. Compiler runs use a 512 MiB
  initial heap and 2 GiB maximum. The integer comparison uses three forks and five
  warmup iterations. Before and after runs were sequential on a shared developer machine.
- Corpus sources are read before timing. Results exclude disk reads, KSP symbol
  resolution, Java/Kotlin compilation, Gradle configuration, and packaging.

## Improvements implemented

| Operation | Before | After | Change |
| --- | ---: | ---: | ---: |
| Parse all 388 templates | 495.49 ms | 23.89 ms | 20.7× faster; 95.2% less time |
| Expand the 136-page subset and check unused parameters | 11.47 ms | 7.97 ms | 30.5% less time |
| Allocation during that expansion | 31.97 MiB | 11.43 MiB | 64.3% less allocation |
| Format 64 ten-digit integers | 1,602.78 ns | 802.32 ns | 2.0× faster; 49.9% less time |

These are JMH means. The reported 99.9% confidence margins were ±14.46 ms and
±1.19 ms for parsing, ±1.23 ms and ±0.20 ms for expansion, and ±6.36 ns and ±2.89 ns
for the batch of integers. They describe these operations, not total ReAI build time
or request latency. Integer rendering remains effectively allocation-free in the GC profiler.

### Index source locations once

`TemplateParser.location` previously scanned from character zero for every element,
attribute, and diagnostic. For a large template this makes source-location work
quadratic. The parser now builds an array of line starts once and uses binary search
for each location: O(source length + location count × log(line count)).

Diagnostic positions retain their one-based line numbers and UTF-16 columns, including
CRLF input and multiline tags. The index adds about 0.21 MiB of allocation across the
whole corpus; that is a small, bounded tradeoff for removing the repeated scans.

### Avoid redundant fragment substitution

`FragmentExpander` now returns an attribute unchanged when there are no bindings or no
`${...}` expressions. Identifier regular expressions are compiled once per name and
reused within that expander. Binding values and expanded trees are not cached, so
different calls to the same fragment still receive their own arguments.

The cache belongs to one compilation; it cannot retain KSP symbols or application
state across builds.

### Write integer digits directly into the output buffer

`HtmlOutput.text(long)` now counts digits with comparisons, reserves the required
buffer space, and writes digits backwards using division by the constant ten.
This removes the variable divisor and repeated buffer-capacity checks from the normal
per-digit path. It creates no temporary strings or arrays.

`Long.MIN_VALUE` retains its existing constant-byte path. A buffered fallback preserves
support for custom buffers smaller than the number being written. The public API and
rendered bytes are unchanged. The integer regression ceiling was tightened from
80,000 ns to 40,000 ns, retaining substantial headroom for slower CI machines.

The existing whole-renderer fixtures were also measured. Small differences on this shared
machine are not a basis for claiming an application-level latency improvement; the
reproducible runtime gain here is the integer-output operation.

The measured full-renderer means before/after were 8.48/7.92 µs for the 50-item catalog,
2.20/2.17 µs for the English inbox, 2.56/2.88 µs for the Norwegian inbox, and
122/120 ns for the static page. The confidence intervals overlap in every case;
the Norwegian after-run was particularly noisy (±0.82 µs). Scores, confidence margins,
and allocations are recorded in [the benchmark results](benchmark/results/2026-09-05-audit.json).

## Validation

- The clean library build, generated-renderer checks, Spring adapter tests, Gradle
  plugin tests, and runtime performance ceilings pass: 96 tests, no failures or skips.
  A repeated build reuses the configuration cache and leaves all tasks up to date.
- New regression coverage checks multiline diagnostics, first/last-line errors,
  supplementary Unicode columns, repeated fragment calls, and binding isolation.
  Numeric output is compared with JDK string formatting for extrema, powers of ten,
  and deterministic random values through every buffer size from 4 to 32 bytes.
- A separate before/after corpus check produced the same SHA-256 over the complete
  parsed trees and expanded subset, including attributes and source locations:
  `e3f90868720f9c6c5498861c97e6e5e6ca3a16a7bf23cb5211acfce28b25465c`.
- The initial audit did not include a full ReAI build or production measurements.
  The subsequent 0.10.1 release review below adds consumer build and rendering checks.

## Further opportunities

| Priority | Finding | Next step |
| --- | --- | --- |
| Highest follow-up | Generated renderers share one Java source and static resource, with aggregating KSP dependencies. ReAI's existing local output contains 314 renderer classes in a 13.49 MB source file, plus a 1.51 MB static resource. | Implemented as per-template sources and resources after 0.11.2; see the per-template section below. |
| Medium | Each expression property lookup can walk KSP properties and supertypes again, including properties from shared layouts. | Profile symbol-resolution time, then consider a cache confined to one processor invocation. Keep missing-property diagnostics and generic/inherited property behavior intact. |
| Medium | Generated `supports`, `supportsReturnType`, request-data checks, and render dispatch use linear checks. | Add a benchmark with hundreds of page models and the full Spring handler path before replacing dispatch with a map or `ClassValue`. Preserve custom `TemplateSet` behavior and subclass handling. |
| Medium | Servlet rendering allocates an 8 KiB body buffer and a 1 KiB output buffer, then buffers the whole response. | Measure full pages and small HTMX responses through the Spring adapter. Tune sizing only with allocation and latency evidence. Streaming changes failure handling and content-length behavior, so it needs separate design work. |
| Lower | CSS and message-usage checks scan production sources/classes across all modules. | Measure their actual task time and cache hit rates in ReAI. Both tasks are already cacheable; reducing scope must preserve cross-module validation. |

The existing local generated-file sizes are supporting evidence of compilation scope,
not a fresh compilation of ReAI. No whole-build speedup is claimed from those sizes.

KSP supports per-output aggregating versus isolating dependencies, but shared outputs
propagate invalidation between sources. Changing an annotation to `aggregating=false`
alone would not safely solve Thim's shared-output design. See the
[KSP incremental-processing documentation](https://kotlinlang.org/docs/ksp-incremental.html).
Likewise, Gradle cache improvements require checking actual task inputs and relocatability;
see [Gradle's build-cache concepts](https://docs.gradle.org/current/userguide/build_cache_concepts.html).
Thim and ReAI already enable build caching, configuration caching, and parallel execution.

## Reproducing the compiler measurements

Build the benchmark jar, then use the corpus benchmark added by this change:

```sh
./gradlew :benchmark:jmhJar
java -jar benchmark/build/libs/benchmark-0.10.0-jmh.jar TemplateCompilerBenchmark \
  -p templatesDirectory=/absolute/path/to/reai/web-app/src/main/resources/templates \
  -p elements=100 -f 2 -wi 3 -i 5 -jvmArgs '-Xms512m -Xmx2g' \
  -prof gc -rf json -rff /tmp/thim-after-compiler.json
```

For the baseline, build `:compiler:jar` at `f0fe2fe` in a separate worktree and place
that compiler jar before the new benchmark jar on the Java classpath:

```sh
java -cp /absolute/path/to/baseline/compiler-0.10.0.jar:benchmark/build/libs/benchmark-0.10.0-jmh.jar \
  org.openjdk.jmh.Main TemplateCompilerBenchmark \
  -p templatesDirectory=/absolute/path/to/reai/web-app/src/main/resources/templates \
  -p elements=100 -f 2 -wi 3 -i 5 -jvmArgs '-Xms512m -Xmx2g' \
  -prof gc -rf json -rff /tmp/thim-before-compiler.json
```

`elements` applies only to the synthetic fixture and is fixed to one value for corpus
runs. With no corpus parameter, the benchmark uses synthetic 100- and 1,000-fragment
fixtures, so it can also run without a ReAI checkout.

The integer comparison uses the same benchmark jar at each revision:

```sh
java -jar /absolute/path/to/benchmark-0.10.0-jmh.jar HtmlOutputBenchmark.integers \
  -f 3 -wi 5 -i 5 -prof gc -rf json -rff /tmp/thim-integers.json
```

## 0.10.1 release review

A second review checked the integer formatter's sign handling, maximum digit count,
small-buffer fallback, flush boundaries, and I/O exception propagation, plus parser
locations and fragment binding isolation. It found no regression in those changes.
One additional allocation improvement reuses the immutable source location for an
opening tag instead of calculating and allocating it twice.

The release candidate passes 98 library tests. Additional cases mix raw bytes, UTF-8,
escaped characters, `int`, and `long` at every starting offset in buffers of 4–24 bytes,
and check that destination failures propagate unchanged. Public signatures and class
inventories are identical to published 0.10.0 for all 12 runtime classes and 12 Spring
adapter classes.

Each consumer was built cleanly first with published 0.10.0, then with 0.10.1 staged
in an isolated local Maven repository. Existing validation settings remained enabled.

| Consumer source revision | Application build | Generated files compared | Result |
| --- | --- | ---: | --- |
| ReAI `785cb1e6d` | `clean :web-app:build` | 18 | Identical |
| Utin `3621bb95` | `clean :web-app:build` | 7 | Identical |
| Eteo `48f63163` | `clean :web-app:build` | 10 | Identical |
| Ecomtools `7cb741f` | `clean :customerservice:build` | 6 | Identical |

The comparison includes all generated Java, Kotlin, binary static content, and service
and message-usage metadata from each rendering module, not just the initial parser corpus.

Packaged consumer smoke checks render `AuthLoginPage` (ReAI), `LoginPage` (Utin),
`IndexPage` (Eteo), and `ReviewSubmissionConfirmationPage` (Ecomtools). Each is rendered
with both boolean fixture variants and locales `en`, `nb`, and `no`. All 24 cases
produce identical HTML hashes with the published 0.10.0 runtime/Spring jars and the
packaged 0.10.1 jars. These checks load the actual executable application jars and
service-loader registries without starting application jobs or contacting business APIs.

The example Spring Boot application was also started over HTTP. English and Norwegian
pages, UTF-8 content lengths, submitted form errors, escaping, and the health endpoint
passed. These checks cover the changed library behavior; they are not a claim of
exhaustive application behavior or production traffic testing.

## 0.10.2 final review

The final pass reviewed message generation, output formatting, servlet rendering,
KSP property lookup, and the Gradle CSS/message validation tasks. One further change
justifies a release: constant translations now return their compiled string literal
instead of creating a `StringBuilder` and another string on every resolution.

The optimization applies only when every locale contains a single text part or an
empty pattern. Parameterized messages, plurals, and selections retain their existing
generated bodies. Locale selection, regional fallback, reference factories, string
escaping, and null-locale validation are preserved. Runtime and Spring adapter class
files are byte-identical to published 0.10.1.

JMH on the same Apple M5 Max/JDK 26 machine used two JVM forks, three one-second warmup
iterations, five one-second measurements, and the GC profiler. Before and after runs
used the same benchmark and fixtures with their respective generated message classes.

| Lookup | Before | After | Allocated bytes before / after |
| --- | ---: | ---: | ---: |
| Constant message, English | 4.306 ± 0.026 ns | 0.648 ± 0.013 ns | 48 / approximately 0 |
| Constant message, Norwegian | 4.297 ± 0.058 ns | 0.821 ± 0.020 ns | 48 / approximately 0 |
| Constant reference, English | 9.421 ± 0.079 ns | 11.841 ± 3.939 ns | 72 / 24 |
| Constant reference, Norwegian | 9.946 ± 0.757 ns | 9.028 ± 0.634 ns | 72 / 24 |

The margins are JMH's 99.9% confidence margins. Reference timings varied between
forks and do not establish a latency improvement; the allocation reduction is stable.
Parameterized message allocation stays at 80 bytes in this fixture, with overlapping
timing intervals before and after. These are individual lookups, not page latency or
whole-build measurements. The sub-nanosecond constant lookup reflects successful JIT
optimization of a simple generated factory and should not be extrapolated to all call sites.
Raw results are in [before](benchmark/results/2026-09-05-messages-before.json) and
[after](benchmark/results/2026-09-05-messages-after.json).

For a ReAI catalog containing 8,793 message factories, generated Java shrank from
7,850,983 to 6,897,779 bytes (12.1%). Compiled class files shrank from 4,847,362 to
4,562,756 bytes (5.9%), with the same 244 classes. A comparison of every factory,
public signature, and reference field produced the same digest before and after:
`9ff6493228810baf2485223a13e476f017397c26d816560c9631b1b49508a129`.
It covers 184,653 message resolutions, 56,546 reference resolutions, and 26,379
null-locale checks per version, using seven locales and three argument fixtures.

The clean library build passes 100 tests. New tests compile and execute generated
Java for single-locale and multilingual/regional catalogs, including empty strings,
quotes, backslashes, newlines, Unicode, references, parameters, plurals, and selections.

Clean consumer builds pass with published 0.10.1 and the locally staged 0.10.2 candidate.
The packaged application comparisons cover 399,882 message resolutions, 122,724
reference resolutions, 57,126 null-locale checks, public generated message signatures,
and 24 HTML renderings per version. All results match. Six generated message sources
change as intended; the other 35 generated files are byte-identical. Utin has no
generated message factories, and all seven of its generated files are unchanged.

Other small candidates were left for later: reusing the remaining naming regexes,
replacing the form-error stream with a loop, and using a linear string join for Java
source arguments. None has evidence of a substantial benefit in these consumers;
the Java-only compilation path is not used by these four Kotlin applications. The
larger property-resolution, dispatch, response-buffering, and incremental-compilation
ideas above still need dedicated profiling and compatibility work.

Reproduce the message measurements with:

```sh
./gradlew :benchmark:jmhJar
java -jar benchmark/build/libs/benchmark-0.10.2-jmh.jar MessageBenchmark \
  -f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc -rf json -rff /tmp/thim-messages.json
```

For the before run, use the same `MessageBenchmark.java` with the 0.10.1 compiler
and regenerate the benchmark's message classes before building its JMH jar.

## 0.11.2 follow-up — 18 September 2026

A second pass measured the remaining runtime and build-time candidates with JMH scratch
benchmarks (Apple M5 Max, OpenJDK 27, two forks, three one-second warmups, five one-second
measurements) and a profiled ReAI build (422 templates, 339 compiled renderers, 9,597
message factories). The benchmark sources are not part of the repository; the
`DispatchBench` shape is a 422-record replica of the generated registry.

### Registry dispatch (implemented)

The generated `TemplateSet` resolved page models linearly: an `==` chain in `supports`,
an `instanceof` chain in `render`, a chain in `usesRequestDataValues`, and an
`isAssignableFrom` chain in `supportsReturnType`. Spring's return-value composite does not
cache handler selection, so each request paid every chain.

| Model position of 422 | Linear `supports` ×2 + `render` | `Map.ofEntries` index + `switch` |
| --- | ---: | ---: |
| first | 28.8 ± 0.8 ns | 4.3 ± 0.1 ns |
| middle | 597.1 ± 9.8 ns | 6.0 ± 0.3 ns |
| last | 1,165.7 ± 9.3 ns | 7.1 ± 2.0 ns |

ReAI pages render in roughly 6–40 µs, so the chains were 1–15% of render time depending
on where a page sat in the registry. The registry now keeps a `Map<Class<?>, Integer>`
constant plus an int switch and retains the `instanceof` chain only as a fallback for
subclasses of open models, which `supports` never accepted anyway.

The index is filled imperatively in a static helper. A first version used one
`Map.ofEntries(...)` call, and javac's inference over 344 distinct `Class` arguments took
14.4 s for ReAI's 14.8 MB generated source; the same file with `HashMap.put` calls compiles
in 2.8 s. The previous `instanceof` chain was also expensive for javac: ReAI's
`:web-app:compileJava` fell from 10.8 s to 6.3 s in a profiled build, with the messages
class removal below accounting for the rest.

### Shared catalogs (implemented)

ReAI's `:i18n` module generates `I18nMessages` from the web-app catalog and 414 files use
it; `:web-app` generated a second `ThimMessages` from the same directory with three uses.
That second 7.6 MB source cost 2.84 s of javac wall time (10.75 s CPU) and 5.3 MB of
classes in the application jar. `generateMessages=false` was unusable for the web-app
because the processor then skipped the usage manifest and ran a module-local unused check,
which fails on backend-only keys. The manifest is now written whenever a catalog exists.

Profiled ReAI tasks after a template edit, before this change: `:web-app:kspKotlin` 5.2 s,
`:web-app:compileJava` 10.8 s (14.5 MB `ThimTemplates.java` plus the messages class),
`:web-app:compileKotlin` 11.7 s, `thimCssUsageCheck` 1.1 s, `thimMessageUsageCheck`
under 0.7 s. The two usage checks are not worth optimizing. With web-app on
`generateMessages=false` and the new registry: `:web-app:kspKotlin` 4.1 s,
`:web-app:compileJava` 6.3 s, `thimMessageUsageCheck` 0.6 s reporting 9,653 messages
and 0 unused across the shared catalog.

### Servlet response buffering (rejected)

`ThimRenderer.renderResponse` renders through a 1 KiB `HtmlOutput` into an 8 KiB
`ByteArrayOutputStream` and copies the body into the servlet stream. Larger buffers help
medium pages and hurt small ones, because the cost moves from growth copies to zeroing:

| Response | 8 KiB / 1 KiB (current) | 16 KiB / 4 KiB | 32 KiB / 8 KiB |
| --- | ---: | ---: | ---: |
| empty inbox, ~0.5 KB | 328 ± 13 ns | 426 ± 24 ns | 557 ± 7 ns |
| static page, 3.4 KB | 387 ± 9 ns | 592 ± 4 ns | 723 ± 6 ns |
| 50-item catalog | 7,612 ± 1,951 ns | 6,469 ± 532 ns | 6,343 ± 35 ns |
| 300-item catalog | 41,694 ± 340 ns | 40,760 ± 618 ns | 40,552 ± 523 ns |

HTMX partials dominate ReAI's request mix, so the sizes stay as they are. Rendering
straight into the response stream measured 5.9 µs and 35.9 µs for the two catalogs, a
12–15% ceiling that only streaming can reach; that changes Content-Length and failure
semantics and remains separate design work.

### Text encoding fast path (rejected)

A run-copy fast path in `HtmlOutput.text(String)` was byte-identical to the current loop
over 39,000 random strings at every buffer size from 4 to 40 bytes, but it only wins on
long plain-ASCII runs and loses on the text these applications render:

| Input | Current | Run-copy |
| --- | ---: | ---: |
| "Item 42" | 7.1 ± 0.0 ns | 7.9 ± 0.2 ns |
| 60-char sentence with `&` and `<` | 70.2 ± 0.4 ns | 47.9 ± 0.7 ns |
| 1.9 KB ASCII | 1,107.5 ± 15.9 ns | 961.6 ± 2.3 ns |
| escape-heavy | 1,074.8 ± 42.9 ns | 1,384.7 ± 22.3 ns |
| Norwegian with æøå | 634.4 ± 10.2 ns | 1,134.1 ± 4.3 ns |

The current per-character loop runs at about 0.6 ns per character, close to the copy
floor, and stays.

## Grouped generated sources — 18 September 2026

After 0.11.2 the compiler emits renderers into 32 source files chosen by
`floorMod(modelName.hashCode(), 32)`, each with a holder class that owns the file's static
resource; renderers are nested in that holder and reference only its `STATIC`. The registry
is the only file that references every renderer. KSP dependencies stay aggregating, so KSP
regenerates everything on each run; the gain comes from Gradle's incremental Java
compilation seeing byte-identical files for unchanged templates, and from Kotlin's
incremental compilation seeing one changed Java source instead of one huge one.

Two intermediate layouts were measured and rejected on ReAI (344 renderers):

| Layout | Forced `kspKotlin` | Edit `compileJava` | Resources | Note |
| --- | ---: | ---: | ---: | --- |
| 0.11.2, one source | 2.8–3.0 s | 6.1–6.3 s | 1.6 MB | baseline |
| one file per template | 4.4–4.7 s | 0.26–0.28 s | 6.5 MB | KSP registers 345 generated Java files: Thim's own generation grew only 0.07 → 0.30 s, the rest is KSP; no static dedup |
| 32 hash-assigned files | 3.6 s | 0.29–0.31 s | 2.8 MB | shipped |

The whole edit loop (`:web-app:classes` after touching one template, warm daemon, build
cache disabled, two or three repetitions):

| Task | 0.11.2 | 32 files |
| --- | ---: | ---: |
| `:web-app:kspKotlin` | 3.0–4.0 s | 3.6 s |
| `:web-app:compileKotlin` | 1.0–1.2 s | 0.09 s |
| `:web-app:compileJava` | 6.1–6.3 s | 0.3 s |
| Total build | 10.4–12.1 s | 4.3–4.4 s |

Full `:web-app:compileJava` fell from 6.3 s to 2.8 s. Processor phases were timed with
temporary instrumentation: parsing, expansion, catalog, strict-model checks, routes and
compilation take 2.1–2.6 s in every layout, so the remaining KSP task time is KSP's own
handling of generated files.

Two details matter for the upgrade. First, the renderer classes are named `…Renderer`
instead of `…ThimRenderer`: with the old names, Gradle's previous-compilation data still
listed every renderer under `ThimTemplates.java` after the layout change, so the next
one-template edit deleted all class files while passing two sources to javac, and retrying
did not recover. Second, renderers are nested in their holder rather than declared as
package-private top-level classes, because javac's auxiliary-class lint rejects those when
referenced from another file and one ReAI module compiles with `-Werror`. With both in
place, the sequence 0.11.2 full build → new layout (incremental) → edit → retry → second
edit → revert succeeds with the build cache disabled; Gradle's reported class counts for
an edit include stale old names it deletes as no-ops in about 0.25 s.

Output equivalence is checked by `GoldenRenderTest` in the example module: twelve renders
(four pages, three locales, including form errors and a 64-byte output buffer) recorded
with the 0.11.2 compiler and compared byte for byte.


## Small compiler improvements — 19 September 2026

Compared 0.12.1 (`828994b`) with three isolated candidates on the same ReAI checkout
and M5 Max, then tested the selected combination without profiling instrumentation.
All builds used local composite builds, a warm Gradle daemon and `--no-build-cache`.
No other benchmark ran concurrently; normal desktop workloads were not controlled.
These numbers are not directly comparable to the earlier layout measurements.

The selected changes touch three production files:

- Generate renderer Java at its final nesting depth, including empty lines, instead
  of splitting/copying each completed source through `prependIndent` and a regex.
  The instrumented source-writing phase fell from about 190 ms to 50 ms.
- Cache matching routes by the query/fragment-free path for one `RouteCatalog` lifetime.
  4,075 route uses took roughly 100–170 ms of matching before, versus 28–40 ms with
  reuse. Each use still validates its HTTP method and enum coverage and creates its
  own diagnostic, so neither errors nor source locations are cached.

The cache retains only route references and path strings during the compilation.
It is not persisted, shared across builds, or used at runtime. The formatting change
removes temporary whole-source copies. Neither changes generated file layout or JAR size.

Final end-to-end measurements ran `:web-app:classes --no-build-cache --profile` after
editing a static attribute in `banks.html`. Each variant had two blocks of six runs;
exclude the setup build and first edit per block, retaining eight runs per variant.
The first sequence was baseline/candidate/baseline/candidate, the second
candidate/baseline/baseline/candidate. The first edit pattern keeps the same static
byte length, so subsequent edits only change resource bytes. The second increases
its length each time, changing Java offsets and exercising Java recompilation.

| Edit pattern | 0.12.1 median wall time | Candidate median wall time | KSP medians |
| --- | ---: | ---: | ---: |
| Same-length static text | 5.597 s | 5.533 s | 4.903 → 4.843 s |
| Different-length static text | 6.684 s | 6.151 s | 5.397 → 4.813 s |

There is meaningful timing variation and overlap: baseline/candidate wall-time ranges
are 5.423–6.624 / 5.355–5.699 s and 6.202–7.118 / 5.878–6.467 s respectively.
The phase reductions are repeatable; the complete-loop benefit is small and workload
dependent, not a promised percentage or a route to sub-second rebuilds. This is worth
keeping because it removes redundant work with very little code and no generated-output
change, not because it changes the development workflow.

All 67 generated web-app files, including resources and manifests, match byte for byte
between baseline and candidate for both edit patterns. Added regressions exercise
repeated URLs with different HTTP methods, source locations and enum bindings. The
existing golden render tests continue checking twelve renders against the old compiler.

A third experiment skipped extracting controller parameter types when `generateRoutes`
is false. Its instrumented forced-KSP median improved 4.983 → 4.794 s, but it adds another
conditional extraction mode; it was left out of this patch. The IDE configuration-cache
override was also left unchanged: ordinary Gradle builds do not validate debugger behavior.

Raw measurements, including all warmups and the isolated candidates, are in
[`2026-09-19-compiler-small-wins.json`](benchmark/results/2026-09-19-compiler-small-wins.json).
