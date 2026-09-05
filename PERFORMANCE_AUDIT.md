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
| Highest follow-up | Generated renderers share one Java source and static resource, with aggregating KSP dependencies. ReAI's existing local output contains 314 renderer classes in a 13.49 MB source file, plus a 1.51 MB static resource. | Profile a one-model edit and a one-template edit. Consider per-template sources/resources with isolating dependencies, while retaining a separate aggregating registry. Shared layouts, message changes, additions, and deletions need explicit invalidation coverage. |
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
