# Changelog

All notable changes to Memtruth SDK and its Memtruth Parse compatibility
surface are documented in this file.

The format is based on [Keep a Changelog 1.1](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

- Repositioned the repository as Memtruth SDK while keeping the existing
  `doctruth` Java package, Maven coordinate, CLI command, and release artifact
  names as compatibility surfaces.

### Fixed

- Updated Jackson Databind to 2.18.8 to close polymorphic type validator
  bypasses tracked as CVE-2026-54512 and CVE-2026-54513.
- Aligned veraPDF validation components so the OpenDataLoader PDF backend does
  not fail at runtime with a `StreamInfo` linkage error.

## [0.2.0-alpha] - 2026-05-09

Second public alpha focused on Java-native structured extraction hardening and
release safety.

### Added

- Java-native schema generation for records and simple POJOs, including nested
  objects, lists, maps, enums, dates, numbers, Jackson property annotations, and
  fail-fast rejection for raw `Object` / unbounded shapes.
- Typed extraction contract tests proving Java records are schema-validated
  before deserialization, then retried on repairable provider output.
- Internal mapper support for `Optional<T>` so absent and explicit `null`
  values map consistently to `Optional.empty()`.

### Changed

- `Optional<T>` now has precise schema semantics: it is omitted from
  `required`, and only Optional fields include `null` in their generated type.
- Non-Optional reference fields are strict: explicit JSON `null` is rejected by
  local schema validation before typed object mapping.
- Custom dotted field constraints now traverse Optional intermediate values
  safely, so caller-owned validation works for optional nested objects.
- CI now requires Spotless, Checkstyle, full recorded verification, integration
  tests, and Jacoco coverage gates before merge; release tags verify before
  deploying to Maven Central.

### Fixed

- Fixed Java typed extraction paths where Optional fields could validate at the
  schema layer but fail during runtime object mapping.
- Fixed overly permissive non-Optional object schemas that previously accepted
  `null`.

## [0.1.0-alpha] - 2026-05-09

First public alpha. The audit primitives the Java enterprise stack has been
missing — every LLM-extracted field carries a verifiable evidence chain
(source page + line + confidence + bi-temporal timestamps).

### Added

- **Document parsing (Layer 1)** — Apache PDFBox + Apache POI ingest preserving
  page, line, and char-offset for every section. Sealed `ParsedSection` family
  (`TextSection`, `TableSection`, `FigureSection`) keeps layout intact.
  *Why this matters:* downstream citations point at exact source coordinates,
  not "somewhere in the document".
- **Citation matching** — fuzzy quote-to-source matching via Apache Commons
  Text (Jaro-Winkler + Levenshtein), surfacing a `matchScore` rather than
  silently returning `page=None`. Replaces the common short-substring
  anti-pattern explicitly.
- **Four LLM providers via hand-rolled JDK `HttpClient`** — Anthropic, OpenAI,
  Gemini, DeepSeek. No SDK dependency, no Spring AI, no LangChain4j. Sealed
  `LlmProvider` interface; identical `extract(...)` semantics across all four.
  *Why this matters:* one library, framework-agnostic, single-jar drop-in
  for any Java 25+ project.
- **Evidence-attributed extraction (Layers 2 + 4)** — fluent
  `DocTruth.from(provider).extract(prompt, T.class).withProvenance().run(doc)`
  API enforcing per-field citation, per-section confidence, retry on missing
  evidence, and bi-temporal `extractedAt` / `sourcePublishedAt` provenance.
- **Smart context assembly (Layer 3)** — `PriorityTruncate`, `SlidingWindow`,
  and `Hierarchical` strategies for documents that exceed the model context
  window. Priority-section patterns ranked above blind truncation.
- **PROV-O audit JSON export (Layer 5)** — W3C-PROV-compatible provenance
  payloads (`Activity`, `Entity`, `Agent`, `wasGeneratedBy`, `wasDerivedFrom`)
  suitable for regulated-industry audit trails.
- **JSON Schema / Pydantic interop** — `extractJson(...)` accepts external
  JSON Schema contracts, including common Pydantic v2 exports with local
  `$defs` / `$ref`, nullable unions, nested objects, arrays, enums, scalar
  constraints, and `additionalProperties=false`.
- **Custom constraints** — field and object constraints attach caller-owned
  business rules to validation and retry semantics.
- **CLI helpers** — parse documents and migrate Python-owned Pydantic schemas
  into checked JSON Schema files for Java extraction.
- **Multilingual README** — English, Simplified Chinese, Traditional Chinese,
  and Spanish project entry points.
- **Failsafe-backed retry** — exponential backoff on transient provider
  failures via `dev.failsafe:failsafe`; per-call retry count carried on
  `Provenance`.

[Unreleased]: https://github.com/doctruthhq/memtruth/compare/v0.2.0-alpha...HEAD
[0.2.0-alpha]: https://github.com/doctruthhq/memtruth/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/doctruthhq/memtruth/releases/tag/v0.1.0-alpha
