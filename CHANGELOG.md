# Changelog

## 0.11.0

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
