# OSS PMF Gap

DocTruth should win its first market as a Java evidence primitive, not as a
general document parser, agent framework, knowledge graph product, or enterprise
governance platform.

The short-term PMF question is:

```text
Can a Java team add field-level evidence, validation, provenance, and audit JSON
to LLM document extraction without building its own evidence system?
```

If the answer becomes "yes", the OSS project is doing its job.

## Target User

The first OSS user is a Java backend developer or technical lead building
document AI inside an existing product.

Likely examples:

- a Spring Boot team extracting contract, invoice, certificate, or resume fields
- a vertical SaaS team adding LLM extraction to an existing workflow
- a system integrator delivering private document automation for regulated
  customers
- a Java enterprise team that cannot adopt a Python-first extraction stack in
  production

This user does not want a new platform. They want a small library that can sit
inside the service they already operate.

## Product Boundary

The OSS boundary should stay narrow:

```text
single document
→ schema-bound extraction
→ field citations
→ optional PDF bbox
→ confidence
→ provenance
→ audit JSON
```

DocTruth OSS should not expand into:

- multi-tenant SaaS
- agent orchestration
- generic RAG
- data catalog or lineage platform
- business workflow engine
- organization-wide evidence graph
- UI-heavy review application

Those may become paid or downstream products, but they are not the OSS adoption
wedge.

## Current OSS Strengths

The project already has enough substance to be more than a toy:

| Area | Current State |
| --- | --- |
| Public Java API | `DocTruth`, `ExtractionBuilder`, `ExtractionResult`, typed records |
| Document parsing | PDF, DOCX, XLSX, CSV parser entry points |
| Source anchoring | `SourceLocation` page/line/offset model |
| Visual evidence | PDF text bbox support through optional `BoundingBox` |
| Citation contract | `Citation` with quote, match score, location, optional bbox |
| Provider boundary | provider-neutral API for common LLM backends |
| Schema support | Java records/classes plus caller-supplied JSON Schema |
| Audit output | PROV-O compatible JSON-LD export |
| Java fit | framework-agnostic, usable from Spring Boot or plain Java |

This is the correct foundation for an OSS Java library.

## Gap To Ideal OSS

The gap is not "replace Python". The gap is making the Java path feel obvious,
trustworthy, and production-shaped.

| Gap | Why It Matters | Desired State |
| --- | --- | --- |
| First-run experience | OSS adoption depends on seeing value in minutes | Quickstart runs cleanly, prints field citation, bbox, confidence, and audit JSON |
| Parser confidence | Users need to know when built-in parsing is enough | Clear parser capability matrix and documented adapter boundary |
| Evidence schema | Integrators need a stable contract | Evidence schema doc defines `SourceLocation`, `BoundingBox`, `Citation`, and audit JSON |
| Java framework fit | Most buyers run Spring Boot or similar services | Integration guide shows plain Java, Spring Boot, LangChain4j, and Spring AI boundaries |
| Citation reliability | This is the product's core trust claim | Matching behavior, weak matches, and failure cases are explicit and tested |
| JSON Schema flow | Many teams already define schemas outside Java | JSON Schema examples feel first-class, not secondary |
| Performance expectation | Parser-heavy workloads need credible guidance | Benchmarks explain PDF parse throughput, CPU use, and concurrency limits |
| Maven adoption | Java users expect normal dependency flow | Maven Central release path and JPMS-friendly packaging stay clean |
| Public positioning | The project must avoid looking like a vague AI platform | README says "auditable LLM extraction for Java" and stops there |

## Competition Reality

DocTruth should not frame itself against huge platforms first.

| Category | Strong Existing Players | DocTruth Position |
| --- | --- | --- |
| Document parsing | Docling, MinerU, Unstructured, LlamaParse | Use or adapt parser output when useful; do not compete on parsing alone |
| Java LLM orchestration | LangChain4j, Spring AI | Complement them at the evidence-gated extraction boundary |
| Data governance | DataHub, Collibra, OpenLineage-style ecosystems | Stay below that layer; export audit artifacts that can feed governance systems |
| Enterprise platforms | Palantir-style internal operating systems | Do not claim this category in OSS |

The sharper category is:

```text
evidence-backed LLM extraction for Java enterprise stacks
```

That category is small enough for a focused OSS project and painful enough to
create commercial pull.

## PMF Stages

### Stage 1: Java OSS Primitive

Goal:

```text
Developers can add auditable extraction to one Java service.
```

Required OSS capabilities:

- reliable parser entry points
- stable `ParsedDocument`, `SourceLocation`, `BoundingBox`, and `Citation`
- typed record extraction
- JSON Schema extraction
- provider-neutral LLM calls
- local validation and retry
- audit JSON export
- quickstart and integration docs

PMF signal:

- external developers use it without maintainer handholding
- one or more production integrations store DocTruth audit JSON
- issues ask for adapters, templates, or server operation rather than "what is this?"

### Stage 2: Paid Server

Goal:

```text
Teams that like the OSS primitive can operate it as a shared private service.
```

Paid layer:

- Docker sidecar / REST API
- batch jobs
- template registry
- webhooks
- run history
- private deployment license
- support

This is still not an enterprise governance platform. It is an operationalized
version of the OSS primitive.

### Stage 3: Enterprise Evidence Layer

Goal:

```text
Organizations can manage evidence across documents, runs, users, and systems.
```

Enterprise layer:

- persistent evidence store
- multi-document audit bundles
- permission-aware access
- SSO/RBAC
- connectors
- reviewer workflow
- compliance exports
- report or claim verification

This stage should arrive only after Stage 1 has developer pull and Stage 2 has
paid operational demand.

## What To Build Next

Near-term OSS work should prioritize:

1. Keep bbox evidence stable across parser, citation, and audit JSON.
2. Make the quickstart undeniable: one command, one result, one citation, one
   bbox, one audit file.
3. Add a parser capability matrix so users know what PDF/DOCX/XLSX/CSV evidence
   quality to expect.
4. Add a benchmark document that reports parser throughput and concurrency
   guidance without promising unrealistic replacement claims.
5. Tighten JSON Schema examples because this is the bridge to non-Java schema
   authors.

The main product rule:

```text
If a feature does not make extracted fields more source-grounded, auditable, or
easier to adopt in Java, it does not belong in the short-term OSS roadmap.
```
