# DocTruth - Rust-Core Document Evidence Runtime

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
[![Rust Core](https://img.shields.io/badge/parser%20core-Rust-b7410e?logo=rust)](runtime/doctruth-runtime)
[![Java Wrapper](https://img.shields.io/badge/Java%20wrapper-25+-007396?logo=openjdk)](https://openjdk.org)

**DocTruth is a Rust-core document evidence runtime with Java SDK/CLI wrappers.**
It turns PDFs and other documents into schema-bound structured output with
field-level source citations, optional PDF bounding boxes, confidence scores,
provenance, and PROV-O audit JSON.

DocTruth is for teams that need to answer one question reliably:

> Where did this extracted value come from?

The core boundary is simple: source document in, validated structured output plus evidence trail out.

The parser/runtime core lives in [`runtime/doctruth-runtime`](runtime/doctruth-runtime).
Java is the integration wrapper: SDK, CLI, API compatibility, packaging, and
enterprise lifecycle. Java/PDFBox is legacy/oracle only and is not the default
parser path.

DocTruth is framework-agnostic and fits into plain Java, Spring Boot,
LangChain4j, Spring AI, Quarkus, Micronaut, or any service that already calls
OpenAI, Anthropic, Gemini, DeepSeek, or an OpenAI-compatible model endpoint.

```text
contract.pdf
→ Contract record
→ result.requireCitation("totalValue")
→ source quote + page/line + optional bbox + match score
→ audit JSON
```

## Installation

The main parser path requires the Rust runtime. Release tarballs and the
installed CLI include `doctruth-runtime` and set `DOCTRUTH_RUNTIME_COMMAND`
automatically. Direct Maven/JAR usage should set it explicitly:

```bash
export DOCTRUTH_RUNTIME_COMMAND=/path/to/doctruth-runtime
```

The Java wrapper requires Java 25+. Use in a Maven project:

```xml
<dependency>
    <groupId>ai.doctruth</groupId>
    <artifactId>doctruth-java</artifactId>
    <version>0.2.0-alpha</version>
</dependency>
```

Gradle uses the same coordinate: `ai.doctruth:doctruth-java:0.2.0-alpha`.

Upgrade to the latest release:

```bash
mvn versions:use-latest-releases -Dincludes=ai.doctruth:doctruth-java -DgenerateBackupPoms=false
```

If `java` on your shell still points to the macOS Java stub or an older runtime,
set `JAVA_HOME` to a Java 25 installation before running the CLI or examples:

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

## Quick Start

```java
import ai.doctruth.DocTruth;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

record Contract(String partyA, String partyB, LocalDate effectiveDate, BigDecimal totalValue) {}

var trustDoc = DocTruth.withOpenAi(System.getenv("OPENAI_API_KEY"))
        .parsePdf(Path.of("contract.pdf"))
        .withParser(ParserPreset.STANDARD)
        .parse();

System.out.println(trustDoc.toMarkdownClean());
System.out.println(trustDoc.toJsonEvidence());
```

The legacy extraction wrapper can still bind a parsed document to an LLM schema
while the TrustDocument-native extraction API converges:

```java
var result = DocTruth.withOpenAi(System.getenv("OPENAI_API_KEY"))
        .fromPdf(Path.of("contract.pdf"))
        .extract("Extract the contract terms", Contract.class)
        .withEvidence()
        .run();

Contract contract = result.value();
var partyACitation = result.requireCitation("partyA");

System.out.println(partyACitation.exactQuote());
System.out.println(partyACitation.location());
partyACitation.boundingBox().ifPresent(System.out::println);
result.writeAudit(Path.of("audit.json"));
```

See [`examples/quickstart`](examples/quickstart/) for a runnable example.

`withEvidence()` is the opinionated default for auditable extraction. It enables
field citations, confidence scores, bitemporal provenance, and audit metadata in
one call. Use `result.requireCitation("field")` for required evidence and
`result.findCitation("field")` when missing evidence should be handled manually.

## CLI For Try / Debug / Inspect

The CLI is for first-run inspection, parser debugging, schema checks, and CI
smoke tests. Parser and schema inspection do not require an LLM key.

```bash
cargo build --manifest-path runtime/doctruth-runtime/Cargo.toml --release
mvn package -DskipTests
export DOCTRUTH_RUNTIME_COMMAND="$PWD/runtime/doctruth-runtime/target/release/doctruth-runtime"
java -jar target/doctruth-java-0.2.0-alpha-all.jar parse contract.pdf
java -jar target/doctruth-java-0.2.0-alpha-all.jar parse contract.pdf --format json -o trust-document.json
java -jar target/doctruth-java-0.2.0-alpha-all.jar schema contract.schema.json
```

See [Install DocTruth CLI](docs/install.md) and [CLI](docs/cli.md).

Tagged releases publish `doctruth-<version>.tar.gz`,
`doctruth-java-<version>-all.jar`, checksums, and a generated Homebrew formula.
Homebrew install is the intended default once the tap is updated:

```bash
brew tap doctruthhq/tap
brew install doctruth
doctruth version
```

## What It Does

<p align="center">
  <img src="docs/assets/capabilities.png" alt="DocTruth capabilities: parse, assemble context, extract with LLM providers, validate schema, attach evidence, and export audit JSON">
</p>

- Parses documents through the Rust runtime into source-grounded evidence units; PDF text sections include page-normalized bounding boxes when layout data is available.
- Extracts Java records or JSON Schema-bound objects through LLM providers via the Java wrapper.
- Validates structured output locally and retries repairable failures.
- Matches extracted fields back to exact source quotes.
- Returns per-field `Citation`, including source location and optional PDF bounding box, plus `Confidence` and `Provenance`.
- Exports W3C PROV-O JSON-LD audit files with `toAuditJson(...)`.

## Java Schema and JSON Schema Interop

Java records and simple POJOs are the native path. DocTruth turns the target
Java type into the same JSON Schema contract it sends to providers and validates
locally before deserializing the response.

Supported Java-native schema shapes include nested records/classes, `List<T>`,
`Map<String, T>`, enums, `String`, booleans, integer and decimal numbers,
`BigDecimal`, `LocalDate`, and Jackson property annotations such as
`@JsonProperty` and `@JsonIgnore`. `Optional<T>` is treated as an optional field:
it is omitted from `required`, while the wrapped value type is still reflected in
the generated schema. Raw `Object` and unbounded shapes fail fast instead of
becoming unauditable catch-all objects.

JSON Schema remains the interoperability path for external schema producers and
template packs.

```java
var schema = JsonSchema.from(Path.of("contract.schema.json"));

var result = DocTruth.withProvider(provider)
        .fromPdf(Path.of("contract.pdf"))
        .extractJson("Extract contract terms", schema)
        .requireCitation("partyA")
        .requireCitation("totalValue")
        .withEvidence()
        .withMaxRetries(2)
        .runJson();
```

If a team already owns Pydantic v2 models, export them to JSON Schema at build
time and treat the output as a normal schema file. DocTruth does not import
Python in Java production.

## Providers

OpenAI-compatible chat completions are the primary path because many hosted, gateway, and local models expose that API shape.

| Provider | Structured output mode |
| --- | --- |
| OpenAI / OpenAI-compatible | `response_format: json_schema` |
| Anthropic | tool-use forcing |
| Gemini | `responseMimeType` + `responseSchema` |
| DeepSeek | OpenAI-compatible JSON mode plus local validation |

Provider clients use JDK `java.net.http.HttpClient`; no vendor SDKs are on the classpath.

Common provider setup:

```java
var client = DocTruth.withProvider(LlmProviders.openAi(System.getenv("OPENAI_API_KEY")));
var anthropic = DocTruth.withProvider(LlmProviders.anthropic("sk-ant-..."));
var local = DocTruth.withProvider(LlmProviders.openAiCompatible(
        "local-key",
        URI.create("http://localhost:11434/v1/chat/completions"),
        "qwen2.5"));
```

## CLI

```bash
doctruth init
doctruth parse contract.pdf
doctruth parse contract.pdf --format json -o trust-document.json
doctruth ingest-audit ./resumes --json -o ingest-audit.json
doctruth schema contract.schema.json
doctruth doctor
doctruth extract contract.pdf -s contract.schema.json
doctruth audit .doctruth/runs/<run-id>/audit.json
```

## Documentation

- Start here:
  - [Quickstart example](examples/quickstart/)
  - [No-LLM parse example](examples/no-llm-parse/)
  - [Install DocTruth CLI](docs/install.md)
  - [CLI](docs/cli.md)
  - [Evidence schema](docs/evidence-schema.md)
- Integrate:
  - [Java integration guide](docs/java-integration.md)
  - [Spring Boot](docs/integrations/spring-boot.md)
  - [LangChain4j](docs/integrations/langchain4j.md)
  - [JSON Schema](docs/integrations/json-schema.md)
  - [Pydantic interop example](examples/pydantic-interop/) for existing Python schema owners
- Understand:
  - [Parser capability matrix](docs/parser-capability-matrix.md)
  - [Architecture](docs/architecture/auditable-structured-extraction-engine.md)
  - [Error handling](docs/error-handling.md)
  - [OSS PMF gap](docs/oss-pmf-gap.md)
  - [Release process](docs/release.md)
- Use cases:
  - [Auditable LLM extraction with the Java wrapper](docs/use-cases/auditable-llm-extraction-java.md)
  - [Source citations for LLM output](docs/use-cases/source-citations-for-llm-output.md)
  - [PDF extraction with bounding boxes](docs/use-cases/pdf-extraction-with-bounding-boxes.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Status

`0.2.0-alpha` is an early public alpha. The API is usable, tested, and published for feedback, but may still change before `1.0`.

Current verification baseline: `mvn verify` passes with 703 unit tests and the
tracked integration suite; optional local corpus tests run when `fixtures/` is
present. Coverage gates are 90% line / 79% branch.

## License

Code is licensed under [Apache License 2.0](LICENSE).

`DocTruth`, `doctruth.ai`, and the DocTruth logo are trademarks of doctruthhq. See [NOTICE](NOTICE).
