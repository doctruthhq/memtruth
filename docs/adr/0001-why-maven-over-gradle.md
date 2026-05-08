# ADR 0001: Use Maven, not Gradle

- **Status**: Accepted
- **Date**: 2026-05-07
- **Deciders**: doctruthhq maintainers

## Context

DocTruth is a public, single-jar library aimed at Java backend engineers in
regulated industries (banking, insurance, healthcare, construction, government).
The build tool choice affects (a) friction for downstream consumers reading the
build, (b) friction for a maintainer authoring the build, and (c) the
size of the contributor pool willing to send PRs.

## Decision

Use **Apache Maven** for the build.

## Consequences

### Why Maven wins for *this* library

- **Enterprise familiarity.** Maven is the lingua franca of regulated-industry
  Java teams. Teams running Spring Boot, JBoss, WebLogic, or WAS overwhelmingly
  read `pom.xml` daily and have internal Nexus / Artifactory mirrors already
  wired for Maven. Gradle adoption is heavier in Android and greenfield startups
  — neither is the target user.
- **Declarative XML over Groovy/Kotlin DSL.** A `pom.xml` is data; a
  `build.gradle.kts` is code. Library consumers should be able to skim the
  build to understand transitive deps without learning a DSL.
- **Toolchain stability.** Maven's plugin contract has been stable for 15+
  years. Gradle's plugin model has had three major rewrites in the last decade
  (Kotlin DSL, plugin DSL, version catalogs).
- **Maven Central publishing path is well-trodden.** OSS Sonatype + Nexus
  Staging plugin is the standard release pipeline. Gradle's Maven Publish
  plugin works too, but adds another moving part.

### What Maven costs us

- **Slower builds for large multi-module projects.** Mitigated: this library is
  intentionally single-module while the published jar stays small and the build
  remains easy to audit.
- **Less expressive build logic.** Acceptable: the build should not need
  expressive logic. If we ever need scripting, `exec-maven-plugin` is enough.
- **Verbose pom.xml.** Acceptable: verbosity is a feature for a security-
  sensitive library where downstream auditors read the build.

### Revisit triggers

This ADR will be revisited if any of the following happens:

1. We split into ≥ 4 Maven modules and module-level builds become a bottleneck.
2. A dominant downstream consumer (≥ 100 production users) requests Gradle.
3. Maven Central publishing pipeline blocks a release for > 1 week.

## Alternatives considered

- **Gradle (Kotlin DSL).** Faster incremental builds; better for multi-project.
  Rejected because Maven's enterprise familiarity outweighs build-speed gains
  for a single-module library.
- **Bazel.** Best at very large monorepos. Vastly over-spec for one library.
- **sbt.** Scala-first; effectively zero adoption in target enterprise segments.
