# Changelog

## 0.12.2 - 2026-09-19

- Avoid copying and reformatting generated renderer sources; write their final indentation directly.
- Reuse path-to-route matches within one compilation while still validating each use's HTTP method, enum coverage, and source location. Generated output and runtime behavior are unchanged.

## 0.12.1 - 2026-09-19

- Cache repeated KSP property and accessor discovery within one processor invocation; generated output, diagnostics, and runtime behavior are unchanged.

## 0.12.0 - 2026-09-19

- Generate renderers into a fixed set of 32 source files chosen by a stable hash of the
  page-model name, each with its own static resource, instead of one source file holding
  every renderer. Renderers no longer reference the registry, so an unchanged file
  regenerates byte-identical output and Gradle's incremental Java compilation recompiles
  only the file whose template changed plus the registry. In ReAI a one-template edit now
  takes 4.3 s end to end instead of 10.4–12.1 s (`compileJava` 6.2 s → 0.3 s, `compileKotlin`
  1.1 s → 0.09 s), and a full `compileJava` takes 2.8 s. Rendered HTML is byte-identical to
  0.11.2, checked by recorded golden renders. Generated renderer classes are now nested in
  `<Registry>Part<n>` holders and end in `Renderer`; they are package-private, not API.
  Static content is deduplicated per file rather than per module, so ReAI's resources grow
  from 1.6 MB to 2.8 MB.

## 0.11.2

- Dispatch generated template registries through a class index instead of linear `==` and
  `instanceof` chains. With 422 page models the last model cost about 1.1 µs per request;
  every model now resolves in a few nanoseconds. Subclasses of open models still fall back
  to the previous `instanceof` chain. The new registry also compiles faster: ReAI's
  `compileJava` for 344 renderers fell from 10.8 s to 6.3 s.
- Write the message usage manifest whenever a catalog exists, so `generateMessages=false`
  keeps build-wide dead-key detection. A module can now render templates from a catalog
  whose typed factories another module generates instead of compiling a second factory class.
  The module-local unused check that previously ran at compile time is replaced by the
  build-wide `thimMessageUsageCheck`.
- Record the measurements behind these changes, and the buffer and text-encoding ideas
  that were rejected, in [the performance audit](PERFORMANCE_AUDIT.md).

## 0.11.1

- Build with a JDK 27 toolchain and publish `--release 27` artifacts; consumers need JDK 27.

## 0.11.0

- Keep settings integration in a separate artifact so Kotlin and KSP load on the consumer project classpath.
- Add the required `no.beint.thim.settings` settings plugin for build-wide validation under Isolated Projects.
- Exchange compiled outputs, CSS sources, usage sources and runtime CSS classes through declared project artifacts.
- Preserve shared-catalog validation across all production modules and root validation task/report locations.
- Make Kotlin compiler cache options relocatable while retaining absolute runtime paths.
- Upgrade KSP to 2.3.11 and migrate this repository's shared build configuration to isolated callbacks and a publishing convention.


## 0.10.2

- Generate direct literal returns for constant translations, avoiding a new string per lookup.
- Preserve locale fallback, message references, escaping, parameters, plural/select handling,
  and null-locale validation. Runtime and Spring adapter implementations are unchanged.
- Add message-resolution benchmarks and tests that compile and execute generated Java for
  both single-locale and regional/multilingual catalogs.

## 0.10.1

- Index template line starts instead of rescanning the source for every diagnostic location.
- Reuse each opening tag's immutable source location during parsing.
- Skip unnecessary fragment substitutions and reuse identifier patterns within one compilation.
- Format integers directly into the output buffer without temporary allocations.
- Add compiler benchmarks and regression coverage for mixed UTF-8/integer output, buffer boundaries,
  numeric extremes, destination failures, fragment bindings, and diagnostic positions.
- Validate HTMX 4 `QUERY` routes and align Kotlin module bytecode with the documented JDK 26 requirement.

The runtime and Spring adapter retain their public signatures. Release preflight includes clean builds
of ReAI, Utin, Eteo, and Ecomtools, identical generated output, and before/after packaged renderer checks.
See [the performance audit](PERFORMANCE_AUDIT.md) for measurements and validation scope.
