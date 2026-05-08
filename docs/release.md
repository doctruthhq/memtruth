# Release runbook

How to cut a `doctruth-java` release to Maven Central via the Sonatype Central Portal.

> Default profile (`mvn install`) does NOT sign or publish. Everything in this doc runs
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

In `doctruthhq/DocTruth → Settings → Secrets and variables → Actions`:

| Secret | Value |
| --- | --- |
| `MAVEN_USERNAME` | Central Portal token username |
| `MAVEN_PASSWORD` | Central Portal token password |
| `OSSRH_GPG_PRIVATE_KEY` | Contents of `private.asc` (full ASCII-armored block) |
| `MAVEN_GPG_PASSPHRASE` | GPG key passphrase |

Delete `private.asc` from the local disk afterwards.

---

## Cutting a release

Replace `0.1.0` with the target version throughout.

### 1. Bump version (drop `-SNAPSHOT`)

```bash
mvn -B versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
```

### 2. Update `CHANGELOG.md`

Move items from `## [Unreleased]` into a new `## [0.1.0] - YYYY-MM-DD` section.
Keep the `## [Unreleased]` heading at the top with empty `### Added/Changed/Fixed`
subheadings ready for the next cycle.

### 3. Commit, tag, push

```bash
git add pom.xml CHANGELOG.md
git commit -m "chore(release): 0.1.0"
git tag -a v0.1.0 -m "v0.1.0"
git push origin main
git push origin v0.1.0
```

### 4. GitHub Actions runs

The `Release` workflow (`.github/workflows/release.yml`) fires on the `v*` tag:

- Builds + signs jar / sources jar / javadoc jar with GPG
- Deploys to the Central Portal via `central-publishing-maven-plugin`
- Automatically publishes the deployment after Central validation passes
- Uploads signed artefacts as workflow artifacts (30-day retention)

Watch it at https://github.com/doctruthhq/DocTruth/actions.

### 5. Wait for Central propagation

`autoPublish=true`, so the GitHub Actions release job publishes automatically
after Central validation passes. Propagation to Maven Central usually takes
~10–30 min; search index updates can take ~4 hours.

### 6. Bump to next `-SNAPSHOT`

```bash
mvn -B versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git add pom.xml
git commit -m "chore: bump to 0.2.0-SNAPSHOT"
git push origin main
```

---

## Verifying the release

After Central propagation finishes:

```bash
mvn dependency:get -Dartifact=ai.doctruth:doctruth-java:0.1.0
```

A clean download confirms the artefact is live. Browse it at
https://central.sonatype.com/artifact/ai.doctruth/doctruth-java/0.1.0.

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
      <version>0.1.0</version>
    </dependency>
  </dependencies>
</project>
EOF
mvn dependency:resolve
```

---

## Rolling back

Maven Central releases are **immutable** after publish. Cut a new patch
(`0.1.1`) that fixes or reverts the issue. Do NOT attempt to re-publish the
same coordinates.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `gpg: signing failed: No pinentry` | CI missing loopback pinentry | Already configured in `pom.xml` `release` profile (`--pinentry-mode loopback`) — re-check the GPG key was imported by `setup-java` |
| `401 Unauthorized` from Central | Stale or wrong token | Regenerate user token in Central Portal; update `MAVEN_USERNAME` / `MAVEN_PASSWORD` secrets |
| Validation: missing `scm` / `developers` / `licenses` | pom.xml drift | Block lives in root `pom.xml`; do not move into a profile |
| Release succeeded but not on Central search | Index lag | Wait ~4h; the artefact is downloadable via `dependency:get` immediately |
