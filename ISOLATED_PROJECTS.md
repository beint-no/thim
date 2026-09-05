# Isolated Projects verification

Verified on 2026-09-05 with Gradle 9.7.1 and JDK 26. The published 0.11.0 release replaces root-project mutation with
an explicit settings plugin and project artifact exchange. This is a migration from 0.10: consumers must apply
`no.beint.thim.settings` in their settings file. The project plugin fails clearly if it is absent.

## Design

The settings plugin collects immutable project paths from settings descriptors and registers an isolated
`beforeProject` callback. Each project publishes its own production outputs and source inputs. Thim modules
also publish CSS inputs and a generated runtime-class allowlist. Root checks resolve those artifacts through
explicit, non-transitive project dependencies. No mutable project model is shared and no missing variant or
resolution failure is ignored. Plain Java/Kotlin consumers remain part of shared-catalog validation.

Root `thimMessageUsageCheck` and `thimCssUsageCheck` names and report locations are preserved. Existing validation
switches retain their meaning. CSS inputs exclude vendor/node_modules trees; usage inputs retain the existing
source-extension filters. KSP cache-facing template/catalog options are module-relative, while runtime options
remain absolute and file-content inputs retain relative path sensitivity.

KSP 2.3.11 supports Gradle's current isolation properties. Kotlin consumers using `--isolated-projects` should
also set `ksp.project.isolation.enabled=true` in `gradle.properties`; KSP's detection does not inspect the CLI flag.
This repository sets that property so both normal and isolated builds use the same generated-source wiring.

The settings plugin is published in `settings-plugin`, separately from `gradle-plugin`. It has no KSP dependency.
Loading KSP in the settings classloader prevents Kotlin consumers from loading their compiler plugin API and makes
explicit project-plugin versions conflict. The project plugin depends on the settings artifact for shared task/extension
classes; CSS registration is public because these classes can belong to different plugin classloaders.

## Results

- `./gradlew clean build` passes: 102 test cases across runtime (13), compiler (41), Spring (11), Gradle plugin (15),
  settings plugin (1), benchmark (17) and example (4).
- `./gradlew :runtime:check :compiler:check :spring:check :gradle-plugin:check :settings-plugin:check :example:classes :benchmark:classes
  --isolated-projects` passes. CI runs this in addition to the complete build.
- A functional test compiles a shared message catalog and a separate consumer under isolation, verifies configuration
  cache reuse, then removes a usage and verifies that the unused message fails validation.
- A separate packaged-settings-plugin fixture compiles a Kotlin consumer under strict isolation and checks configuration-cache reuse.
  Temporarily adding KSP to the settings classpath makes that regression test fail with the original missing Kotlin API error.
- Cross-module CSS and dynamic-prefix validation passes under isolation.
- A real plain Java consumer passes `clean build thimCheck --isolated-projects`, restores generated outputs from cache,
  rejects an added unused CSS class, and passes again after restoration.
- ReAI's Kotlin compilation, three Thim checks and two Riss/OpenAPI checks pass under strict isolation with both the
  source composite and published Maven Central artifacts. Its five KSP generation tasks restore from cache in a fresh worktree.
- ReAI's validation reports retain 9,289 messages and 912 CSS classes with no unused entries. Its 11,612 compared file
  contents match the baseline after accounting for the new Thim artifact version labels.
- ReAI also starts with `bootRun --isolated-projects`; login, health, OpenAPI and Enak pages returned HTTP 200 with no startup/request errors.
- The tagged release workflow passed. Runtime, compiler, Spring, both plugin implementations and both plugin markers
  are available from Maven Central. All four consumers pass clean builds and isolated validation without local overrides.

## Additional consumers

Utin, Eteo and Ecomtools migration branches also pass clean builds without the build cache, strict isolated compilation
and validation, configuration-cache reuse, and local startup. The consumers use Maven Central by default and retain `-PthimBuild` for local Thim development.
The packaged comparisons below were repeated against the actual public release artifacts.

| Consumer | Equivalent packaged entries | CSS classes | Catalog messages |
| --- | ---: | ---: | ---: |
| Utin | 2,789 | 1,034 | 0 |
| Eteo | 3,946 | 215 | 386 |
| Ecomtools | 2,093 | 35 | 191 |

Content comparisons normalize Thim version labels and exclude ZIP metadata; classpath ordering is preserved.
Validation reports are byte-identical. Eteo's missing-image task wiring is also verified with a failing image reference
and a successful build after restoration. Startup smoke tests disable jobs and migrations, use local databases and verify
rendered login/landing pages. Utin and Ecomtools also return health status UP.

## Remaining ecosystem blocker

Full isolated Spring Boot packaging still fails when Boot 4.1.1 constructs its `BootJar` task and reads other projects'
mutable group/version in `ResolvedDependencies`. The same code exists in 4.2.0-M1.
[spring-boot#43755](https://github.com/spring-projects/spring-boot/issues/43755) is open and the proposed
[PR #51311](https://github.com/spring-projects/spring-boot/pull/51311) was closed without merging.
The full build therefore keeps isolation opt-in. No violations are suppressed and no checks are disabled.
The application packaging and IDE-import limitations should be reassessed when Spring Boot adds support.

See [Gradle's migration guide](https://docs.gradle.org/9.7.1/userguide/isolated_projects.html#sec:migration) and
[KSP's release notes](https://github.com/google/ksp/releases/tag/2.3.11).

## Release and upstream follow-up

[Thim 0.11.0](https://github.com/beint-no/thim/releases/tag/v0.11.0) is published. Consumer migrations:
[ReAI #13751](https://github.com/beint-no/reai/pull/13751),
[Utin #458](https://github.com/beint-no/utin/pull/458),
[Eteo #833](https://github.com/beint-no/eteo/pull/833), and
[Ecomtools #380](https://github.com/beint-no/ecomtools/pull/380).

[Gradle #39057](https://github.com/gradle/gradle/pull/39057) proposes an isolated-projects regression test and API
contract clarification for `ResolvedComponentResult.moduleVersion` through the lazy resolution result. It covers
late producer versions, renamed archives, unrelated explicit capabilities and included-build identity. Both targeted
integration tests, CodeNarc and Checkstyle pass. Maintainer confirmation of that contract is still required.

[The Spring Boot follow-up](https://github.com/spring-projects/spring-boot/issues/43755#issuecomment-5551490296)
links this evidence and asks how the existing public custom-archive API should preserve compatibility when supplied
only an artifact provider. The previously closed capability-based patch has not been resubmitted.

[KSP #3189](https://github.com/google/ksp/pull/3189) uses Gradle's effective `BuildFeatures` state, including CLI
precedence, while preserving explicit KSP opt-in and older Gradle support. Six focused tests pass, including actual
generated-source compilation and configuration-cache reuse. Plugin validation and formatting checks pass too.
Keep `ksp.project.isolation.enabled=true` until a release containing the fix is adopted.

Both contributions disclose AI assistance. The Gradle draft needs human review and DCO sign-off; the KSP draft needs
Google's contributor agreement. These are pending upstream contributions, not released fixes. Once accepted and
released, retest normal and isolated `bootJar`, custom layer coordinates, included builds, IDE import and cache reuse
before enabling isolation globally. No forked Spring Boot or KSP artifact is used by the consumers.
