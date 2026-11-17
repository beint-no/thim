# Publishing

This project publishes the `runtime`, `compiler`, `spring`, `settings-plugin`, and `gradle-plugin` modules under `no.beint.thim`.
The `no.beint.thim.settings` and `no.beint.thim` plugin markers are published too.
The `example` and `benchmark` modules are not published.

## Credentials

Generate a Central Portal user token at
<https://central.sonatype.com/usertoken>.

Set these standard environment variables in the shell or CI environment:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
SIGNING_IN_MEMORY_KEY
SIGNING_IN_MEMORY_KEY_ID
SIGNING_IN_MEMORY_KEY_PASSWORD
```

## Release

Set the version in `settings.gradle.kts`, merge the change to `main`, and push a
matching tag:

```sh
git tag vX.Y.Z
git push origin vX.Y.Z
```

GitHub Actions verifies that the tag matches the Gradle version, then builds
and publishes that exact tagged commit. No local Maven credentials are needed.

## Publish an existing tag to GitHub Packages

The release workflow can also publish the same signed publications to the existing
GitHub Packages repository. Use this when Central publication is unavailable:

```sh
gh workflow run publish.yml -f tag=v0.14.0 -f registry=github
```

The workflow checks out the tag, verifies its version, runs the clean build and
publishes with its repository-scoped `GITHUB_TOKEN`. Signing uses the existing
release environment secrets. It never changes or moves the release tag.

Consumers add `https://maven.pkg.github.com/beint-no/thim` as a Maven repository for
both plugin management and dependencies. GitHub requires authentication for its
Maven registry, including public packages: use a classic token with `read:packages`
through Gradle credentials, never a committed token. The repository's publishing
configuration accepts `gpr.user` / `gpr.key` or `GITHUB_ACTOR` and
`GITHUB_TOKEN` / `GH_TOKEN`.

To retry Central later, dispatch the same workflow with `registry=central`.
