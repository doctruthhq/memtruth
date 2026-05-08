# DocTruth

<p align="center">
  <img src="docs/assets/readme-hero.png" alt="DocTruth source-cited extraction: every extracted field cites a source page and line">
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.zh-TW.md">繁體中文</a> ·
  <a href="README.es.md">Español</a>
</p>

[![CI](https://github.com/doctruthhq/DocTruth/actions/workflows/ci.yml/badge.svg)](https://github.com/doctruthhq/DocTruth/actions)
[![Maven Central](https://img.shields.io/maven-central/v/ai.doctruth/doctruth-java.svg?label=Maven%20Central)](#installation)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25+-007396?logo=openjdk)](https://openjdk.org)

**Auditable LLM extraction for Java.** Parse documents, extract structured data, and attach field-level citations, confidence, and provenance.

DocTruth is for teams that need to answer one question reliably:

> Where did this extracted value come from?

It is not an agent framework, chain framework, vector database wrapper, or UI. It is a small Java library for the extraction boundary: source document in, validated structured output plus evidence trail out.

## Installation

Requires Java 25+. Gradle users can use the same coordinate: `ai.doctruth:doctruth-java:0.1.0-alpha`.

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

Upgrade to the latest release:

```bash
mvn versions:use-latest-releases -Dincludes=ai.doctruth:doctruth-java -DgenerateBackupPoms=false
```

## Quick Start

```java
import ai.doctruth.DocTruth;
import ai.doctruth.OpenAiProvider;
import ai.doctruth.PdfDocumentParser;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

record Contract(String partyA, String partyB, LocalDate effectiveDate, BigDecimal totalValue) {}

var doc = PdfDocumentParser.parse(Path.of("contract.pdf"));

var result = DocTruth.from(new OpenAiProvider(System.getenv("OPENAI_API_KEY")))
        .extract("Extract the contract terms", Contract.class)
        .withProvenance()
        .withConfidence()
        .withBitemporal()
        .run(doc);

Contract contract = result.value();
var partyACitation = result.citations().get("partyA");
```

See [`examples/quickstart`](examples/quickstart/) for a runnable example.

## What It Does

<p align="center">
  <img src="docs/assets/capabilities.png" alt="DocTruth capabilities: parse, assemble context, extract with LLM providers, validate schema, attach evidence, and export audit JSON">
</p>

- Parses PDF, DOCX, XLSX, and CSV into sections with source locations.
- Extracts Java records or JSON Schema-bound objects through LLM providers.
- Validates structured output locally and retries repairable failures.
- Matches extracted fields back to exact source quotes.
- Returns per-field `Citation`, `Confidence`, and `Provenance`.
- Exports W3C PROV-O JSON-LD audit files with `toAuditJson(...)`.

## JSON Schema and Pydantic Interop

Java records are the native path. JSON Schema is the interoperability path.

```java
var schema = JsonSchema.from(Path.of("contract.schema.json"));

var result = DocTruth.from(provider)
        .extractJson("Extract contract terms", schema)
        .requireCitation("partyA")
        .requireCitation("totalValue")
        .withMaxRetries(2)
        .runJson(doc);
```

DocTruth supports common Pydantic v2 JSON Schema exports, including local `$defs` / `$ref`, nullable unions, nested objects, arrays, enums, required fields, scalar constraints, and `additionalProperties=false`.

Build-time helper:

```bash
java -jar target/doctruth-java-0.1.0-alpha.jar \
  migrate pydantic myapp.schemas:ResumeExtraction \
  --out schemas/resume.schema.json \
  --check
```

Production Java extraction only needs the exported schema file and the DocTruth jar.

## Providers

OpenAI-compatible chat completions are the primary path because many hosted, gateway, and local models expose that API shape.

| Provider | Structured output mode |
| --- | --- |
| OpenAI / OpenAI-compatible | `response_format: json_schema` |
| Anthropic | tool-use forcing |
| Gemini | `responseMimeType` + `responseSchema` |
| DeepSeek | OpenAI-compatible JSON mode plus local validation |

Provider clients use JDK `java.net.http.HttpClient`; no vendor SDKs are on the classpath.

## CLI

```bash
java -jar target/doctruth-java-0.1.0-alpha.jar parse contract.pdf
java -jar target/doctruth-java-0.1.0-alpha.jar migrate pydantic myapp.schemas:Model --out schema.json --check
```

## Documentation

- [Quickstart example](examples/quickstart/)
- [Pydantic interop example](examples/pydantic-interop/)
- [Architecture](docs/architecture/auditable-structured-extraction-engine.md)
- [Error handling](docs/error-handling.md)
- [Release process](docs/release.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Status

`0.1.0-alpha` is an early public alpha. The API is usable, tested, and published for feedback, but may still change before `1.0`.

Current verification baseline: 628 unit tests and 16 integration tests passing, with 2 external smoke tests skipped, coverage gates at 90% line / 80% branch, single jar about 202 KB.

## License

Code is licensed under [Apache License 2.0](LICENSE).

`DocTruth`, `doctruth.ai`, and the DocTruth logo are trademarks of doctruthhq. See [NOTICE](NOTICE).
