# Changelog

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
