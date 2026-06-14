# Auditable LLM Extraction With The Java Wrapper

DocTruth is for teams that need structured LLM extraction results they can
defend later. The parser core is Rust; the Java API is the SDK/CLI wrapper for
Java services that need to call that runtime, ask a model for schema-bound
output, validate the result, and attach source evidence to each extracted field.

The core use case is simple:

```text
PDF / DOCX / XLSX / CSV
→ Java record or JSON Schema
→ typed extraction result
→ field citations
→ confidence
→ provenance
→ audit JSON
```

## When To Use It

Use DocTruth when extracted fields need to enter a business system, review
queue, customer-facing report, or compliance process.

Common examples:

- contract terms extraction
- invoice and purchase order extraction
- supplier certificate extraction
- insurance document extraction
- resume and profile extraction
- regulated document intake

The important requirement is not just "can the model return JSON?" It is:

```text
Can each returned field point back to the source document?
```

## Java-Native Shape

DocTruth returns ordinary Java objects:

```java
record Contract(String partyA, String partyB, BigDecimal totalValue) {}

var result = DocTruth.withProvider(provider)
        .fromPdf(Path.of("contract.pdf"))
        .extract("Extract contract terms", Contract.class)
        .withEvidence()
        .run();

Contract contract = result.value();
var citation = result.requireCitation("totalValue");
```

The caller keeps its normal service, queue, database, and review workflow.
DocTruth owns the evidence boundary.

## What Makes It Auditable

Each extraction result can include:

| Artifact | Purpose |
| --- | --- |
| Typed value | Data the application can store or use |
| `Citation` | Field-level quote, page/line location, match score, optional bbox |
| `Confidence` | Per-field score and rationale |
| `Provenance` | Model, model version, timestamps, retry count |
| Audit JSON | PROV-O compatible JSON-LD export |

This is the difference between "the model said so" and "this field came from
this source quote on this page."

## What DocTruth Is Not

DocTruth is not an agent framework, vector store, data catalog, BI tool, or
general document Q&A system. It is intentionally small: one document, one
schema-bound extraction run, evidence attached at the field boundary.

That narrow scope is what makes it practical to drop into existing Java
enterprise stacks.
