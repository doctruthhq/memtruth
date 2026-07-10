# Memtruth SDK Language-First Repository Layout

**Status:** Accepted on 2026-07-10

## Context

The public Memtruth SDK repository currently presents the Java Maven module at
the repository root and the Rust workspace under `memtruth-sdk/`. That layout
obscures the intended architecture: Rust owns the reusable core while Java is
the stable SDK, CLI, packaging, and compatibility wrapper.

A top-level `crates/` directory would be idiomatic for a Rust-only repository,
but Memtruth SDK is deliberately polyglot. The root should communicate that
boundary without requiring readers to understand Cargo terminology.

## Decision

Use language-first top-level directories:

```text
rust/
  Cargo.toml
  Cargo.lock
  README.md
  crates/
    memtruth-contracts/
    memtruth-chunker/
    memtruth-projector/
    memtruth-vespa/

java/
  pom.xml
  src/

config/
docs/
examples/
scripts/
```

The existing Rust workspace moves from `memtruth-sdk/` to `rust/`. The existing
Maven project moves from the repository root to `java/`. Rust-specific corpus
registry files move with the Rust workspace to `rust/docs/`.

## Compatibility

This is a repository-layout change, not a public API rename.

- Keep existing Cargo package names.
- Keep Maven coordinates `ai.doctruth:doctruth-java`.
- Keep Java packages under `ai.doctruth`.
- Keep CLI artifact names and compatibility commands.
- Keep root-level `docs/`, `examples/`, `scripts/`, and shared `config/`.

Root scripts remain the stable automation entrypoints. They resolve the
repository root from their own location and operate on `java/pom.xml` and
`java/target/`, so callers do not need to depend on their current directory.

## Path Contracts

- Rust contributor commands run from `rust/`.
- Java contributor commands run from `java/`.
- GitHub Actions set an explicit working directory for language-specific jobs.
- Java tests that inspect root documentation or examples receive an explicit
  repository-root path from Maven instead of assuming the process directory.
- Release packaging continues to run through root scripts and consumes Java
  artifacts from `java/target/`.
- Dependabot scans `/rust` for Cargo and `/java` for Maven.

## Verification

Add a repository-layout contract that rejects the legacy `memtruth-sdk/`, root
`pom.xml`, and root `src/` paths. Then run:

```bash
scripts/check-repository-layout.sh
cd rust && cargo fmt --check
cd rust && cargo test
cd rust && cargo clippy --all-targets -- -D warnings
cd java && mvn spotless:check checkstyle:check
cd java && mvn test
cd java && mvn verify -P recorded
scripts/compile-quickstart.sh
scripts/package-cli-release.sh --version 0.2.0-alpha
scripts/smoke-cli-release.sh --version 0.2.0-alpha
git diff --check
```

The Java baseline currently has a pre-existing OpenDataLoader/veraPDF runtime
linkage failure. Migration verification must distinguish that known baseline
from new path failures and must not silently include an unrelated dependency
fix in this layout change.
