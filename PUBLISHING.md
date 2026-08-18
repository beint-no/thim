# Publishing

This project publishes the `runtime`, `compiler`, `spring`, and `gradle-plugin` modules under `no.beint.thim`.
The `example` module is not published.

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

Set the version in `build.gradle.kts`, merge the change to `main`, and push a
matching tag:

```sh
git tag v0.4.4
git push origin v0.4.4
```

GitHub Actions verifies that the tag matches the Gradle version, then builds
and publishes that exact tagged commit. No local Maven credentials are needed.
