# Changelog

All notable changes to DocTruth are documented in this file.

The format is based on [Keep a Changelog 1.1](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Fixed

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

[Unreleased]: https://github.com/doctruthhq/DocTruth/compare/v0.1.0-alpha...HEAD
[0.1.0-alpha]: https://github.com/doctruthhq/DocTruth/releases/tag/v0.1.0-alpha
