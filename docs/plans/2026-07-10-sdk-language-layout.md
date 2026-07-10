# Memtruth SDK Language Layout Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move the existing Rust workspace to `rust/` and the existing Maven/Java module to `java/` while preserving all public package, artifact, CLI, and crate contracts.

**Architecture:** The repository root becomes a language-neutral SDK entrypoint. Rust and Java own their build files and source trees under explicit language directories, while shared documentation, examples, configuration, and release scripts remain at the root. Root automation resolves paths explicitly so CI and release callers do not rely on an implicit working directory.

**Tech Stack:** Rust 1.95/Cargo, Java 25/Maven, POSIX shell, GitHub Actions, Dependabot

---

### Task 1: Add the repository layout contract

**Files:**
- Create: `scripts/check-repository-layout.sh`
- Modify: `.github/workflows/ci.yml`

**Step 1: Write the failing layout check**

Create an executable POSIX shell script that requires:

```text
rust/Cargo.toml
rust/crates/
java/pom.xml
java/src/main/java/
java/src/test/java/
```

It must reject:

```text
memtruth-sdk/
pom.xml
src/
```

**Step 2: Run it against the legacy layout**

Run: `scripts/check-repository-layout.sh`

Expected: FAIL with missing `rust/Cargo.toml` and `java/pom.xml` diagnostics.

**Step 3: Add the check to CI**

Add a small `repository-layout` job before language-specific build jobs.

**Step 4: Commit the red contract**

```bash
git add scripts/check-repository-layout.sh .github/workflows/ci.yml
git commit -m "test: define SDK repository layout contract"
```

### Task 2: Move the Rust workspace

**Files:**
- Move: `memtruth-sdk/Cargo.toml` -> `rust/Cargo.toml`
- Move: `memtruth-sdk/Cargo.lock` -> `rust/Cargo.lock`
- Move: `memtruth-sdk/README.md` -> `rust/README.md`
- Move: `memtruth-sdk/crates/` -> `rust/crates/`
- Move: `memtruth-sdk/docs/` -> `rust/docs/`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/dependabot.yml`
- Modify: `README.md`

**Step 1: Move the workspace without renaming packages**

Use `git mv memtruth-sdk rust`. Do not rename any Cargo package or Rust import.

**Step 2: Update Rust automation paths**

Change the Rust CI working directory to `rust`, give the job a user-facing Rust
SDK name, and configure Dependabot Cargo updates under `/rust`.

**Step 3: Update the root Rust workspace link**

Change current documentation links from `memtruth-sdk/` to `rust/`. Keep
superseded ADR 0012 as historical evidence instead of rewriting its original
migration record.

**Step 4: Verify Rust from its new root**

Run:

```bash
cd rust && cargo fmt --check
cd rust && cargo test
cd rust && cargo clippy --all-targets -- -D warnings
```

Expected: all commands pass with the existing four Cargo packages.

**Step 5: Commit the Rust move**

```bash
git add rust .github/workflows/ci.yml .github/dependabot.yml README.md
git commit -m "refactor: move Rust SDK workspace under rust"
```

### Task 3: Move the Java Maven module

**Files:**
- Move: `pom.xml` -> `java/pom.xml`
- Move: `src/` -> `java/src/`
- Modify: `java/pom.xml`
- Modify: `java/src/test/java/ai/doctruth/JavaBaselineContractTest.java`
- Modify: `java/src/test/java/ai/doctruth/ExamplesCompileTest.java`

**Step 1: Move the Maven project**

Create `java/`, then move `pom.xml` and `src/` into it without changing Maven
coordinates or Java package names.

**Step 2: Make shared paths explicit in Maven**

Point Checkstyle at `${project.basedir}/../config/checkstyle/checkstyle.xml`.
Expose `${project.basedir}/..` to tests as the `memtruth.repoRoot` system
property through Surefire and Failsafe.

**Step 3: Anchor root-inspecting tests**

Update `JavaBaselineContractTest` and `ExamplesCompileTest` to resolve root
documentation and examples from `memtruth.repoRoot`. Keep Java-local source,
snapshot, POM, and target paths relative to the Maven module.

**Step 4: Run path-focused Java tests**

Run:

```bash
cd java && mvn -Dtest=JavaBaselineContractTest,ExamplesCompileTest,CliPackagingContractTest,ArchitectureContractTest test
```

Expected: the path and packaging contract tests pass.

**Step 5: Commit the Java move**

```bash
git add java pom.xml src
git commit -m "refactor: move Java SDK module under java"
```

### Task 4: Rewire CI and release automation

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `.github/workflows/javadocs.yml`
- Modify: `.github/workflows/nightly-live.yml`
- Modify: `.github/dependabot.yml`
- Modify: `scripts/compile-quickstart.sh`
- Modify: `scripts/install-cli.sh`
- Modify: `scripts/package-cli-release.sh`
- Modify: `scripts/smoke-cli-release.sh`

**Step 1: Update Java workflow working directories**

Run all Maven steps from `java/`. Keep root scripts invoked from the repository
root. Update uploaded artifact and Javadocs paths to `java/target/...`.

**Step 2: Update Dependabot**

Point Maven updates at `/java` and Cargo updates at `/rust`.

**Step 3: Make root scripts location-independent**

Resolve `repo_root` from each script path. Use `java/pom.xml` for Maven calls
and `java/target/` for built JARs. Keep distribution output under root `dist/`
and smoke output under root `target/`.

**Step 4: Verify package automation**

Run:

```bash
mvn -f java/pom.xml -DskipTests package
scripts/compile-quickstart.sh
scripts/package-cli-release.sh --version 0.2.0-alpha
scripts/smoke-cli-release.sh --version 0.2.0-alpha
```

Expected: package, quickstart compilation, archive creation, and CLI smoke pass.

**Step 5: Commit automation changes**

```bash
git add .github scripts
git commit -m "ci: align automation with language directories"
```

### Task 5: Update contributor and release documentation

**Files:**
- Create: `docs/adr/0014-language-first-sdk-layout.md`
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `README.zh-TW.md`
- Modify: `README.es.md`
- Modify: `CONTRIBUTING.md`
- Modify: `.github/pull_request_template.md`
- Modify: `docs/cli.md`
- Modify: `docs/homebrew.md`
- Modify: `docs/install.md`
- Modify: `docs/integrations/json-schema.md`
- Modify: `docs/release.md`
- Modify: `examples/evidence-overlay/README.md`
- Modify: `examples/no-llm-parse/README.md`
- Modify: `examples/pydantic-interop/README.md`
- Modify: `examples/quickstart/README.md`

**Step 1: Record the architecture decision**

Add ADR 0014 with context, decision, compatibility guarantees, automation
impact, and the prerequisite resolution of the Java baseline failure.

**Step 2: Update active build instructions**

Use `cd rust` for Cargo commands and `cd java` for contributor Maven commands.
For root-oriented examples, use `mvn -f java/pom.xml` and `java/target/...` so
paths remain directly runnable from a fresh clone.

**Step 3: Update release paths**

Change release-version staging and artifact references from root `pom.xml` and
`target/` to `java/pom.xml` and `java/target/`.

**Step 4: Check for stale active references**

Run:

```bash
git grep -n 'memtruth-sdk' -- ':!docs/adr/0012-memtruth-monorepo-rename.md'
git grep -n 'target/doctruth-java' -- '*.md' '*.sh' '*.yml'
git grep -n 'git add pom.xml' -- '*.md' '*.sh' '*.yml'
```

Expected: no stale active layout paths.

**Step 5: Commit documentation**

```bash
git add README*.md CONTRIBUTING.md .github/pull_request_template.md docs examples
git commit -m "docs: document language-first SDK layout"
```

### Task 6: Run final verification and prepare the PR

**Files:**
- Modify only if verification exposes a migration regression.

**Step 1: Run the layout and Rust gates**

```bash
scripts/check-repository-layout.sh
cd rust && cargo fmt --check
cd rust && cargo test
cd rust && cargo clippy --all-targets -- -D warnings
```

**Step 2: Run Java style and focused path gates**

```bash
cd java && mvn spotless:check checkstyle:check
cd java && mvn -Dtest=JavaBaselineContractTest,ExamplesCompileTest,CliPackagingContractTest,ArchitectureContractTest test
```

**Step 3: Run full Java verification**

```bash
cd java && mvn test
cd java && mvn verify -P recorded
```

Both commands must pass. The prerequisite veraPDF alignment in PR #25 removed
the pre-migration linkage failure, so any OpenDataLoader/veraPDF failure is a
migration regression rather than a tolerated baseline condition.

**Step 4: Run release smokes and repository checks**

```bash
mvn -f java/pom.xml -DskipTests package
scripts/compile-quickstart.sh
scripts/package-cli-release.sh --version 0.2.0-alpha
scripts/smoke-cli-release.sh --version 0.2.0-alpha
git diff --check
git status --short
```

**Step 5: Request code review**

Review the complete branch for stale paths, CI semantics, release packaging,
and accidental public contract changes before pushing and opening a PR.
