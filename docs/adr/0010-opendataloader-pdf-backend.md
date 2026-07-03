# ADR 0010: Use OpenDataLoader as the default PDF parser backend

- **Status**: Accepted
- **Date**: 2026-07-04
- **Deciders**: doctruthhq maintainers

## Context

DocTruth's useful public contract is not "be a PDF parser". It is:

```text
document -> structured fields -> citation -> audit JSON
```

The current Java SDK and CLI already own the user-facing contract:

- stable parser entry points
- parsed section model
- schema-bound extraction
- citation matching
- audit JSON
- CLI output shape

The prior Rust-first parser direction produced useful runtime work, but it made
parser quality the main product blocker. Current evidence shows that the Rust
parser path is not yet strong enough to become the default PDF backend, while
OpenDataLoader already provides a stronger Java-accessible PDF layout engine.

OpenDataLoader PDF Core is Apache-2.0 and is published as:

```text
org.opendataloader:opendataloader-pdf-core:2.2.1
```

Its public Java entry point writes JSON/text/markdown/PDF outputs from a PDF
file and configuration. The JSON output includes document-level metadata,
ordered semantic objects, page numbers, text content, tables, and bounding box
coordinates.

## Decision

DocTruth will use OpenDataLoader as the default PDF parser backend.

DocTruth remains the canonical SDK, CLI, parsed-document model, extraction
contract, citation/audit layer, and compatibility surface. OpenDataLoader is an
internal parser backend, not the DocTruth public contract.

The CLI exposes the backend only for inspection and compatibility:

```bash
doctruth parse contract.pdf --parser opendataloader
doctruth parse contract.pdf --parser pdfbox
```

`opendataloader` is the default. `pdfbox` remains available as a legacy parser
and differential oracle.

OpenDataLoader hybrid backends remain off by default. The default local path
must not require Python, Torch, Docling, hosted callbacks, or external model
servers.

## Dependency Strategy

OpenDataLoader's POM uses veraPDF version ranges. DocTruth pins the relevant
veraPDF versions through dependency management so CI does not resolve a moving
range on every build.

The OpenDataLoader dependency also brings a large PDF accessibility/layout
stack. That cost is accepted because it replaces parser-quality work that is
not where DocTruth should spend most OSS effort.

## Rust and Go Boundary

Rust or Go parser/runtime work remains valuable only after measurement shows a
specific bottleneck:

- peak RSS from OpenDataLoader on a named corpus/profile
- cold/warm latency by document type
- table-heavy and scan-heavy failure modes
- temporary object retention or unload behavior
- CLI startup/package cost

Future native work should target those measured hotspots. It should not fork
DocTruth back into an independent parser engine by default.

Acceptable follow-up native work includes:

- replacing a memory-heavy ODL subpath behind the same DocTruth backend
  contract
- implementing a small local sidecar for a measured CPU/RSS bottleneck
- normalizing ODL JSON faster with a native streaming adapter
- building a profile harness that compares ODL, PDFBox, and native candidates

Non-goals:

- reimplement all PDF parsing in Rust before the SDK is useful
- require a sidecar for normal Java SDK use
- make OpenDataLoader CLI output DocTruth's public format
- add Python/Docling production residency to the default parser path

## Consequences

### Positive

- DocTruth can improve PDF parsing quality immediately without delaying on a
  from-scratch parser.
- The SDK stays focused on evidence, extraction, and audit behavior.
- The CLI can expose parser choice while preserving one DocTruth JSON contract.
- Rust/Go optimization work becomes evidence-driven instead of ideology-driven.

### Costs

- The Java dependency graph becomes larger.
- OpenDataLoader currently writes JSON files instead of returning a public
  object graph, so DocTruth needs a temporary-output bridge.
- The backend has static state and logging behavior that DocTruth must isolate
  at the wrapper boundary.
- veraPDF dependencies require explicit version pinning for reproducible CI.

## Revisit Triggers

1. OpenDataLoader's default Java-only path fails a documented corpus/profile
   that matters to DocTruth's primary extraction use cases.
2. The dependency graph creates an unacceptable packaging or licensing issue.
3. A measured native implementation beats OpenDataLoader on quality and
   resource use while preserving the same DocTruth public contract.
4. OpenDataLoader exposes a stable in-memory Java object API that makes the
   temporary JSON bridge unnecessary.
