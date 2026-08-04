# Publishing

This project publishes the `runtime`, `compiler`, `spring`, and `gradle-plugin` modules under `no.beint.thim`.
The `example` module is not published.

## One-time setup

Install GnuPG, create a signing key, and publish the public key:

```sh
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys <key-id>
```

Generate a Central Portal user token at:

```text
https://central.sonatype.com/usertoken
```

Add the publishing values to `~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=<central-token-username>
mavenCentralPassword=<central-token-password>

signingInMemoryKey=<ascii-armored-private-key-without-header-footer-lines>
signingInMemoryKeyId=<key-id>
signingInMemoryKeyPassword=<key-password>

thim.pom.developer.id=beint-no
thim.pom.developer.name=Beint
thim.pom.developer.url=https://github.com/beint-no
```

For GitHub Actions, use equivalent repository secrets:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
SIGNING_IN_MEMORY_KEY
SIGNING_IN_MEMORY_KEY_ID
SIGNING_IN_MEMORY_KEY_PASSWORD
```

This repository also uses a local `.maven-central.local.properties` file as a scratch copy for setting up CI secrets.
It is ignored by git and must not be committed.

The GitHub Actions workflow is `.github/workflows/publish.yml`.
It builds on pushes and pull requests, and publishes only from `v*` tags or manual `workflow_dispatch`.
For tag releases, make the tag match the Gradle version, for example `v0.4.4` for `version = "0.4.4"`.

Export the private key for `signingInMemoryKey`:

```sh
gpg --export-secret-keys --armor <key-id>
```

## Release

Check the version in `build.gradle.kts`, then run:

```sh
./gradlew clean build
./gradlew publishToMavenCentral
```

After validation succeeds, open Central Portal Deployments and publish the deployment manually.
For automatic release instead, run:

```sh
./gradlew publishAndReleaseToMavenCentral
```

For a CI release, push a matching tag:

```sh
git tag v0.4.4
git push origin v0.4.4
```
