# Release runbook

How to cut a `doctruth-java` release to Maven Central via the Sonatype Central Portal.

> The default Java profile (`cd java && mvn install`) does NOT sign or publish. Everything in this doc runs
> via `-P release` or the GitHub Actions tag-triggered workflow.

---

## One-time setup

### 1. Generate a GPG key

```bash
gpg --full-generate-key            # RSA 4096, no expiry, name + email
gpg --list-secret-keys --keyid-format=long
gpg --armor --export-secret-keys <KEYID> > private.asc   # for GH Actions secret
gpg --armor --export <KEYID>                              # paste into keyserver UI
```

Upload the public key:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
gpg --keyserver keys.openpgp.org    --send-keys <KEYID>
```

### 2. Register Sonatype Central Portal

- Sign up at https://central.sonatype.com
- Verify the `ai.doctruth` namespace with a DNS TXT record on `doctruth.ai`.
- Generate a **user token** at Account → Generate User Token. Copy both halves.

### 3. Add GitHub Actions secrets

In `doctruthhq/memtruth → Settings → Secrets and variables → Actions`:

| Secret | Value |
| --- | --- |
| `MAVEN_USERNAME` | Central Portal token username |
| `MAVEN_PASSWORD` | Central Portal token password |
| `OSSRH_GPG_PRIVATE_KEY` | Contents of `private.asc` (full ASCII-armored block) |
| `MAVEN_GPG_PASSPHRASE` | GPG key passphrase |
| `HOMEBREW_TAP_TOKEN` | Optional token with write access to `doctruthhq/homebrew-tap` |
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GOOGLE_API_KEY` / `DEEPSEEK_API_KEY` | Optional nightly live smoke keys |

Delete `private.asc` from the local disk afterwards.

---

## Cutting a release

The commands below use `0.2.0-alpha` as the release being cut. Replace it with
the target version throughout.

### 1. Bump version (drop `-SNAPSHOT`)

```bash
mvn -B -f java/pom.xml versions:set -DnewVersion=0.2.0-alpha -DgenerateBackupPoms=false
```

### 2. Update `CHANGELOG.md`

Move items from `## [Unreleased]` into a new `## [0.2.0-alpha] - YYYY-MM-DD` section.
Keep the `## [Unreleased]` heading at the top with empty `### Added/Changed/Fixed`
subheadings ready for the next cycle.

### 3. Confirm the public API snapshot

If this release intentionally changes `ai.doctruth.*` or `ai.doctruth.spi.*`,
regenerate and review the API snapshot before tagging:

```bash
mvn -f java/pom.xml -Dtest=ai.doctruth.PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test
git diff -- java/src/test/resources/ai/doctruth/public-api-snapshot.txt
```

For patch releases, the snapshot should usually be unchanged.

### 4. Commit, tag, push

```bash
git add java/pom.xml CHANGELOG.md
git commit -m "chore(release): 0.2.0-alpha"
git tag -a v0.2.0-alpha -m "v0.2.0-alpha"
git push origin main
git push origin v0.2.0-alpha
```

### 5. GitHub Actions runs

The `Release` workflow (`.github/workflows/release.yml`) fires on the `v*` tag:

- Builds the standalone CLI jar and packages GitHub Release artifacts
- Creates `doctruth-<version>.tar.gz`, `doctruth-java-<version>-all.jar`,
  `checksums.txt`, a CycloneDX SBOM, and a generated Homebrew formula
- Smoke-tests the generated CLI tarball before publishing
- Creates a GitHub Release with those CLI artifacts attached
- Builds + signs jar / sources jar / javadoc jar with GPG
- Deploys to the Central Portal via `central-publishing-maven-plugin`
- Automatically publishes the deployment after Central validation passes
- Updates `doctruthhq/homebrew-tap` automatically when `HOMEBREW_TAP_TOKEN`
  is configured; otherwise the generated formula is attached for manual tap update
- Uploads signed artefacts and CLI distribution files as workflow artifacts
  (30-day retention)

Watch it at https://github.com/doctruthhq/memtruth/actions.

### 6. Wait for Central propagation

`autoPublish=true`, so the GitHub Actions release job publishes automatically
after Central validation passes. Propagation to Maven Central usually takes
~10–30 min; search index updates can take ~4 hours.

### 7. Bump to next `-SNAPSHOT`

```bash
mvn -B -f java/pom.xml versions:set -DnewVersion=0.3.0-SNAPSHOT -DgenerateBackupPoms=false
git add java/pom.xml
git commit -m "chore: bump to 0.3.0-SNAPSHOT"
git push origin main
```

---

## Verifying the release

After Central propagation finishes:

```bash
mvn dependency:get -Dartifact=ai.doctruth:doctruth-java:0.2.0-alpha
```

A clean download confirms the artefact is live. Browse it at
https://central.sonatype.com/artifact/ai.doctruth/doctruth-java/0.2.0-alpha.

Smoke-test in a scratch project:

```bash
mkdir /tmp/dt-smoke && cd /tmp/dt-smoke
cat > pom.xml <<'EOF'
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>x</groupId><artifactId>x</artifactId><version>1</version>
  <dependencies>
    <dependency>
      <groupId>ai.doctruth</groupId>
      <artifactId>doctruth-java</artifactId>
      <version>0.2.0-alpha</version>
    </dependency>
  </dependencies>
</project>
EOF
mvn dependency:resolve
```

Verify the CLI release artifacts from the GitHub Release:

```bash
shasum -a 256 -c checksums.txt
tar -xzf doctruth-0.2.0-alpha.tar.gz
JAVA=/path/to/java ./doctruth-0.2.0-alpha/bin/doctruth version
JAVA=/path/to/java ./doctruth-0.2.0-alpha/bin/doctruth doctor
```

When `HOMEBREW_TAP_TOKEN` is not configured, update the Homebrew tap manually
with the generated formula:

```bash
cp doctruth.rb ../homebrew-tap/Formula/doctruth.rb
cd ../homebrew-tap
brew install --build-from-source ./Formula/doctruth.rb
doctruth version
doctruth doctor
git add Formula/doctruth.rb
git commit -m "doctruth 0.2.0-alpha"
git push origin main
```

Public Javadocs are deployed by `.github/workflows/javadocs.yml` on release tags
and manual dispatch. The dependency review workflow blocks high-severity
dependency changes on pull requests, and Dependabot opens weekly Maven and
GitHub Actions update PRs.

---

## Rolling back

Maven Central releases are **immutable** after publish. Cut a new patch
(`0.2.1`) that fixes or reverts the issue. Do NOT attempt to re-publish the
same coordinates.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `gpg: signing failed: No pinentry` | CI missing loopback pinentry | Already configured in `java/pom.xml` `release` profile (`--pinentry-mode loopback`) — re-check the GPG key was imported by `setup-java` |
| `401 Unauthorized` from Central | Stale or wrong token | Regenerate user token in Central Portal; update `MAVEN_USERNAME` / `MAVEN_PASSWORD` secrets |
| Validation: missing `scm` / `developers` / `licenses` | `java/pom.xml` drift | Block lives in `java/pom.xml`; do not move into a profile |
| Release succeeded but not on Central search | Index lag | Wait ~4h; the artefact is downloadable via `dependency:get` immediately |
