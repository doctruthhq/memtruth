# DocTruth

[![CI](https://github.com/doctruthhq/DocTruth/actions/workflows/ci.yml/badge.svg)](https://github.com/doctruthhq/DocTruth/actions)
[![Maven Central](https://img.shields.io/badge/maven--central-pending-lightgrey.svg)](#installation)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25+-007396?logo=openjdk)](https://openjdk.org)

> **Auditable LLM extraction for Java. Parse documents, extract structured data, and attach field-level citations, confidence, and provenance.**

## Overview

DocTruth is an open-source, framework-agnostic Java 25 library for document-grounded structured extraction. It is designed for applications that need to answer a simple audit question: *where did this extracted value come from?*

The library focuses on the extraction boundary: parsing source files, assembling model context, requesting typed output from an LLM provider, matching extracted fields back to source text, and exporting an auditable result. It is not a chain framework, agent framework, vector-store wrapper, or Spring extension.

## 60-second hello world

```java
import ai.doctruth.Citation;
import ai.doctruth.Confidence;
import ai.doctruth.DocTruth;
import ai.doctruth.OpenAiProvider;
import ai.doctruth.PdfDocumentParser;
import ai.doctruth.Provenance;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

record Contract(String partyA, String partyB, LocalDate effectiveDate, BigDecimal totalValue) {}

var doc = PdfDocumentParser.parse(Path.of("contract.pdf"));
var result = DocTruth.from(new OpenAiProvider(System.getenv("OPENAI_API_KEY")))
        .extract("Extract the contract terms", Contract.class)
        .withProvenance()
        .withSourcePublishedAt(Instant.parse("2026-01-01T00:00:00Z"))
        .withBitemporal()
        .withConfidence()
        .run(doc);

Contract value           = result.value();
Citation partyACitation  = result.citations().get("partyA");   // page 1, line 3, "Acme Corp Ltd", matchScore 0.97
Confidence partyAConf    = result.confidence().get("partyA");  // score derived from citation match quality
Provenance prov          = result.provenance();                // model, modelVersion, extractedAt, sourcePublishedAt, retries
result.toAuditJson(Path.of("audit/contract-2026-Q2.jsonld"));  // W3C PROV-O JSON-LD, audit-team-ready
```

The complete runnable example lives in [`examples/quickstart/Quickstart.java`](examples/quickstart/Quickstart.java) — copy-paste, set `OPENAI_API_KEY`, run.

## JSON Schema / Pydantic interop

Java records are the native path, but external schema contracts are first-class.
If a Python team already owns Pydantic models, export their JSON Schema and load
it directly:

```java
var schema = JsonSchema.from(Path.of("contract.schema.json"));

var result = DocTruth.from(provider)
        .extractJson("Extract contract terms", schema)
        .requireCitation("partyA")
        .requireCitation("totalValue")
        .withMaxRetries(2)
        .runJson(doc);

JsonNode value = result.value();
Citation partyA = result.citations().get("partyA");
```

DocTruth validates returned JSON locally against the JSON Schema contract. The
interop path covers common Pydantic v2 exports: local `$defs` / `$ref`, nullable
`anyOf` unions, `oneOf` / `allOf`, `type: ["string", "null"]`, required fields,
primitive types, enums, nested object properties, array items,
`additionalProperties=false`, and common scalar / array constraints such as
`minLength`, `pattern`, `minimum`, `maximum`, `minItems`, and `maxItems`. Retry
requests include the validation/citation failure so the provider can repair the
JSON instead of blindly receiving the same prompt again.

For build-time migration from Python-owned schemas, the jar also exposes a
small CLI:

```bash
java -jar target/doctruth-java-0.1.0-SNAPSHOT.jar \
  migrate pydantic myapp.schemas:ResumeExtraction \
  --out schemas/resume.schema.json \
  --check
```

The CLI may invoke Python during migration; production Java extraction only
needs the exported schema file and the DocTruth jar. A complete example lives in
[`examples/pydantic-interop`](examples/pydantic-interop/).

## Providers

The primary integration path is OpenAI-compatible chat completions because many
hosted, gateway, and local models expose that API shape. Anthropic and Gemini
remain first-class providers where their native structured-output features are
useful.

| Provider | Status | Endpoint |
| --- | --- | --- |
| OpenAI / OpenAI-compatible | `response_format: json_schema`; custom endpoint and model supported | `api.openai.com/v1/chat/completions` |
| Anthropic | Tool-use forcing + prompt cache | `api.anthropic.com/v1/messages` |
| Gemini | `responseMimeType` + `responseSchema` | `generativelanguage.googleapis.com/v1beta` |
| DeepSeek | Convenience OpenAI-compatible adapter | `api.deepseek.com/v1/chat/completions` |

Provider clients are hand-rolled `java.net.http.HttpClient` clients — no vendor SDK on the classpath. See [ADR 0003](docs/adr/0003-llm-provider-dependency-strategy.md) for the rationale.

## Capabilities

- **PDF parsing** — Apache PDFBox 3.x; detected layout blocks as `TextSection` records with page, line, and char offset preserved.
- **DOCX parsing** — Apache POI; paragraph/table extraction with source locations.
- **XLSX and CSV parsing** — spreadsheet and delimited text ingestion with structured sections.
- **Citation matching** — Apache Commons Text JaroWinkler similarity; low-confidence matches remain visible through `matchScore` instead of being silently omitted.
- **JSON Schema extraction** — `extractJson(...)` accepts caller-supplied JSON Schema, including Pydantic v2 exported schemas with nested `$defs` / `$ref`, and returns validated `JsonNode` output.
- **Custom constraints** — `withFieldConstraint(...)` and `withObjectConstraint(...)` enforce business rules with stable error codes and retry semantics.
- **PROV-O JSON-LD audit export** — `result.toAuditJson()` emits W3C PROV-O JSON-LD with full `Activity` / `Entity` / `Agent` / `wasGeneratedBy` / `wasDerivedFrom` graph.
- **OpenAI-compatible first provider layer** — OpenAI-compatible endpoints are the default path; Anthropic and Gemini remain native first-class integrations.
- **Smart context strategies** — `PriorityTruncate`, `SlidingWindow`, `Hierarchical`. STRICT vs WARN_AND_INCLUDE policy on overflow — no silent overrun.
- **Bi-temporal provenance** — `extractedAt` plus caller-supplied `sourcePublishedAt`, so downstream systems can answer *"was this extraction performed before or after the source was last revised?"*.
- **Failsafe-backed retries** — exponential backoff with jitter via `dev.failsafe:failsafe`; `Retry-After` header honored on 429 / 503.

## Why no vendor SDKs?

Single jar < 500 KB, no transitive bloat, no version lock to a vendor's `0.x` SDK. Adding a new provider is one wire-record package + one `HttpClient` class — no waiting on someone else's release cycle. See [ADR 0003](docs/adr/0003-llm-provider-dependency-strategy.md).

## Installation

**Maven** (Maven Central publication pending; build locally until then):

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

**Gradle**:

```groovy
dependencies {
    implementation 'ai.doctruth:doctruth-java:0.1.0-alpha'
}
```

## Architecture

Five layers; every public type lives in `ai.doctruth.*` (root) or `ai.doctruth.spi.*`. Internals are under `ai.doctruth.internal.*` and are explicitly NOT public API.

```
┌──────────────────────────────────────────────────────────────┐
│ 5. Audit + Export — toAuditJson() → W3C PROV-O JSON-LD       │
│    Standard format SOC 2 / HIPAA / EU AI Act auditors ingest │
├──────────────────────────────────────────────────────────────┤
│ 4. High-level fluent API — DocTruth.from(provider)           │
│    .extract(prompt, type).withProvenance().run(doc)          │
├──────────────────────────────────────────────────────────────┤
│ 3. Smart context assembly — PriorityTruncate w/ STRICT or    │
│    WARN_AND_INCLUDE policy (no silent overrun, ever)         │
├──────────────────────────────────────────────────────────────┤
│ 2. Evidence-attributed extraction — per-field Citation,      │
│    Confidence, bi-temporal Provenance; non-silent fuzzy      │
│    matching via Apache Commons Text (JaroWinkler)            │
├──────────────────────────────────────────────────────────────┤
│ 1. Document parsing — PdfDocumentParser (Apache PDFBox 3.x); │
│    DocxDocumentParser (Apache POI); docId = SHA-256 of bytes │
└──────────────────────────────────────────────────────────────┘
```

## Engineering principles

The five rules in [CONTRIBUTING.md](CONTRIBUTING.md) govern every commit:

1. **Decoupled by default** — one reason to change per public type; layers communicate only through root-package records and sealed interfaces.
2. **Auditable + debuggable + loggable everywhere** — every external boundary emits SLF4J events; every public exception carries a stable error code plus structured context. **No silent failures.**
3. **No god files / classes / functions** — file ≤ 300 LOC, method body ≤ 30 LOC, class ≤ 8 public methods or ≤ 5 record components.
4. **Build, don't synthesize** — JDK + already-declared deps before hand-rolling utilities; new dependencies require an ADR.
5. **Elegance over cleverness** — records over classes, sealed interfaces over inheritance, pattern-matching `switch` with no `default`, `Optional<T>` over null. From any public-API call site to the implementation in ≤ 3 hops.

## Documentation

- [Contributing guide](CONTRIBUTING.md) — engineering principles, TDD discipline, review checklist
- [Structured extraction engine](docs/architecture/auditable-structured-extraction-engine.md) — project scope, schema-bound extraction, validation, and evidence gating
- [Error handling](docs/error-handling.md) — stable error-code matrix and provider schema-normalization semantics
- [Architecture decisions](docs/adr/) — ADRs explaining non-obvious calls
- [Quickstart](examples/quickstart/) — runnable copy-paste example
- [Pydantic interop](examples/pydantic-interop/) — JSON Schema migration + `extractJson(...)` golden path
- [Release process](docs/release.md) — Maven Central signing, GPG, Sonatype Central Portal
- [Changelog](CHANGELOG.md) — Keep a Changelog 1.1, semver

## Quality

**v0.1.0-alpha measured numbers (this commit):**

- Tests: 628 unit + 15 integration = **643 passing**, 0 failures, 1 live smoke skipped in recorded CI
- Test coverage: **93.7% line / 81.0% branch** — gates set at 90% bundle line, 80% bundle branch, and 80% line per class
- Single jar size: **202 KB** — well under the 500 KB target

## Maintainer

**doctruthhq maintainers** — GitHub [@doctruthhq](https://github.com/doctruthhq). Issues triaged within 5 business days, PRs reviewed within 10. Major architectural decisions documented as ADRs in [`docs/adr/`](docs/adr).

## License & Trademark

**Code**: Apache License 2.0 — see [LICENSE](LICENSE). Picked for the explicit patent grant and broad open-source compatibility. Full reasoning in [ADR 0008](docs/adr/0008-license-apache-2-0-and-trademark.md).

**Trademark**: `DocTruth`, `doctruth.ai`, and the DocTruth logo are trademarks of doctruthhq. The Apache 2.0 grant covers the source code and binaries; it does **not** grant permission to use the DocTruth name or logo to identify, market, or describe a fork or competing service. Forks must use a different name. See the [NOTICE](NOTICE) file for the full trademark policy.

## Citation

```bibtex
@software{doctruthhq_doctruth_java_2026,
  author       = {{doctruthhq maintainers}},
  title        = {{DocTruth}: Auditable LLM Extraction for Java},
  year         = {2026},
  version      = {0.1.0-alpha},
  url          = {https://github.com/doctruthhq/DocTruth},
  note         = {Per-field source citation, per-field confidence,
                  and bi-temporal provenance for LLM extraction in Java 25+;
                  framework-agnostic, single-jar, Apache 2.0 licensed.}
}
```
