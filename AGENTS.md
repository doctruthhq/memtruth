# DocTruth Agent Guide

DocTruth is a Java SDK and CLI for auditable document extraction. It turns
documents into structured fields with citations, confidence, provenance, and
audit JSON.

Use this file when contributing to DocTruth or when an agent needs enough
project context to build, test, debug, or extend the tool.

## What This Tool Does

DocTruth answers one question:

```text
Where did this extracted document field come from?
```

It owns:

```text
PDF / DOCX / XLSX / CSV parsing
schema-bound Java and JSON extraction
field-level citation
exact source quotes
page / line / bbox source locations
confidence and provenance
audit JSON export
Java SDK and CLI
```

DocTruth should stay focused on document-grounded extraction. Do not turn it
into an agent framework, vector database wrapper, general document chatbot,
team review workflow, hosted service, or unrelated application layer.

## Quick Start

```bash
mvn test
mvn package -DskipTests
java -jar target/doctruth-java-0.2.0-alpha-all.jar doctor
java -jar target/doctruth-java-0.2.0-alpha-all.jar parse contract.pdf --bboxes
java -jar target/doctruth-java-0.2.0-alpha-all.jar schema contract.schema.json
```

Minimal Java usage:

```java
var result = DocTruth.withOpenAi(System.getenv("OPENAI_API_KEY"))
        .fromPdf(Path.of("contract.pdf"))
        .extract("Extract the contract terms", Contract.class)
        .withEvidence()
        .run();

var citation = result.requireCitation("totalValue");
result.writeAudit(Path.of("audit.json"));
```

## Engineering Standards

- Keep public contracts stable and versioned.
- Preserve evidence, provenance, citation, confidence, and audit semantics.
- Prefer typed records, sealed interfaces, and enums over ad hoc JSON or strings.
- Keep adapters thin; put business rules in the owning SDK module.
- Add tests at the closest public or contract boundary for non-trivial behavior.
- Decompose by responsibility and reviewability, not by rigid line-count rules.
- Do not add direct dependencies without an issue and an ADR when the dependency
  affects parsing, provider calls, security, networking, cryptography, public
  API shape, or release packaging.
- Do not add telemetry, remote callbacks, or remote state by default.
- Do not commit secrets, private documents, API keys, credentials, or
  production-like fixture corpora.
- Keep one concept per commit and PR.

DocTruth-specific rules:

- Public API lives in `ai.doctruth.*` and `ai.doctruth.spi.*`.
- Internal implementation lives under `ai.doctruth.internal.*`.
- Preserve source evidence, confidence, provenance, and audit JSON semantics.
- Do not leak provider SDK, parser, HTTP, or storage implementation types into
  public API signatures.
- Prefer Java records, sealed interfaces, `Optional`, defensive copies, and
  explicit public exceptions.

## Contributing Rules

Open an issue before a PR when a change touches:

```text
public API
audit JSON format
citation or quote-matching semantics
parser behavior
provider behavior
security boundaries
new dependencies
release packaging
product scope
```

Direct PRs are fine for typos, small docs clarifications, test-only coverage,
and local refactors that do not change public behavior.

Every PR should include:

```text
linked issue when required
problem statement
behavior summary
security impact
tests run, copied exactly
fixture or migration notes when applicable
```

Use Conventional Commits:

```text
feat: add csv citation mapping
fix: preserve bbox in audit export
docs: clarify cli parse usage
test: cover missing citation retry
refactor: split provider schema projection
```

## Required Verification

Run focused tests while developing, then run full verification before review.

```bash
mvn test
mvn verify -P recorded
mvn spotless:check checkstyle:check
git diff --check
```

For intentional public API changes:

```bash
mvn -Dtest=ai.doctruth.PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test
```

Review `src/test/resources/ai/doctruth/public-api-snapshot.txt` before
committing the updated snapshot.
