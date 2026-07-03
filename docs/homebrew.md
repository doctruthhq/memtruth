# Homebrew Distribution

Homebrew is the preferred CLI distribution path for macOS and many Java
developers. The DocTruth release workflow generates a ready-to-copy formula at:

```text
dist/homebrew/doctruth.rb
```

The formula downloads the release tarball:

```text
https://github.com/doctruthhq/DocTruth/releases/download/v<VERSION>/doctruth-<VERSION>.tar.gz
```

## Maintainer Flow

1. Cut a GitHub release tag, for example `v0.2.0-alpha`.
2. Wait for the `Release` workflow to finish.
3. If `HOMEBREW_TAP_TOKEN` is configured, the workflow pushes the generated
   formula to `doctruthhq/homebrew-tap` automatically.
4. Otherwise, download `doctruth.rb` from the GitHub Release assets or workflow
   artifact and copy it into the tap repository:

```text
doctruthhq/homebrew-tap/Formula/doctruth.rb
```

5. Commit and push the tap change.

Users can then install:

```bash
brew tap doctruthhq/tap
brew install doctruth
doctruth version
doctruth doctor
```

## Local Formula Generation

Build and package locally:

```bash
mvn package -DskipTests
scripts/package-cli-release.sh
```

Smoke the generated tarball:

```bash
mkdir -p /tmp/doctruth-release-smoke
tar -xzf dist/doctruth-0.2.0-alpha.tar.gz -C /tmp/doctruth-release-smoke
JAVA=/path/to/java /tmp/doctruth-release-smoke/doctruth-0.2.0-alpha/bin/doctruth version
JAVA=/path/to/java /tmp/doctruth-release-smoke/doctruth-0.2.0-alpha/bin/doctruth-runtime --doctor
```

## Why The Formula Is Not Committed As A Live Formula Here

The working formula belongs in a separate Homebrew tap repository. Keeping the
generated formula in release artifacts avoids a stale checksum in this source
repository. Every release tarball has a different SHA-256, so the formula must be
generated from the final artifact.
