# ADR 0014: Use language-first directories for the SDK repository

- Status: Accepted
- Date: 2026-07-10

## Context

Memtruth SDK is a polyglot repository. Its Rust packages were previously nested
under `memtruth-sdk/`, while the Java Maven module occupied the repository root.
That shape hid the intended Rust/Java boundary and made the root look like a
Java project with an unrelated Rust subproject.

A top-level `crates/` directory is idiomatic for a Rust-only repository, but it
does not communicate the language boundary to SDK users. It would also become
ambiguous as additional language wrappers are introduced.

## Decision

Use language-first top-level directories:

```text
rust/
  Cargo.toml
  Cargo.lock
  crates/

java/
  pom.xml
  src/

config/
docs/
examples/
scripts/
```

Cargo packages remain under `rust/crates/`. Shared documentation, examples,
configuration, and release scripts remain at the repository root.

Root automation must resolve paths explicitly. GitHub Actions runs Cargo from
`rust/` and Maven from `java/`; release scripts consume Java artifacts from
`java/target/`. Java tests that inspect shared root resources receive the
repository root through the `memtruth.repoRoot` Maven property.

## Compatibility

This decision does not rename public contracts:

- Cargo package names remain unchanged.
- Maven coordinates remain `ai.doctruth:doctruth-java`.
- Java packages remain under `ai.doctruth`.
- CLI artifact and launcher names remain unchanged.
- The Java CI status check remains `build (25)` for branch-protection compatibility.
- The SDK/server boundary in ADR 0013 remains unchanged.

## Consequences

The GitHub root now exposes the Rust and Java implementations as peers. Cargo
and Maven commands have explicit working directories, and release automation no
longer depends on a root-level `pom.xml` or `target/` directory.

Existing links or scripts that assume `memtruth-sdk/`, root `pom.xml`, or root
`src/` must migrate to the new paths. Historical ADRs and completed plans retain
their original paths as records of the state they described.
