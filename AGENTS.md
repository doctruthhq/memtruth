# DocTruth Agent Guide

DocTruth is the document evidence engine in the doctruthhq stack. It turns
documents into structured fields, exact source quotes, page/line/bbox citations,
provenance, parser warnings, audit JSON, and `TrustDocument` output.

## Runtime Architecture

DocTruth's parser/runtime core is Rust.

```text
Java SDK / CLI / API
  -> Rust native binding or Rust sidecar process
  -> runtime/doctruth-runtime
  -> evidence-native TrustDocument
```

`runtime/doctruth-runtime` is the authoritative home for:

```text
PDF parsing
page rasterization
layout detection
table recognition
OCR routing and OCR evidence
model/cache verification
benchmark-corpus execution
parser warnings
audit-grade parser decisions
evidence reconciliation
TrustDocument emission
```

The default Rust PDF substrate is `pdf_oxide`, not a Java/PDFBox rewrite in
Java and not a parallel parser core. Treat `pdf_oxide` as the replacement for
PDFBox's low-level duties: PDF bytes, text-layer extraction, structure-tree and
column-aware reading order entrypoints, page geometry, rendering, and bbox
evidence. `lopdf` is transitional low-level/debug support only and should not
grow into the long-term parser core.

Java remains only the stable enterprise-facing SDK, CLI, API, packaging,
lifecycle, and compatibility wrapper around the Rust core. Java/PDFBox is not a
parser runtime strategy. It may exist only as a legacy migration surface and
differential oracle for tests, never as the default parser path.

Do not add new parser-quality, OCR/table/layout, model-execution,
benchmark-corpus, audit-grade parser, or evidence-reconciliation behavior only
to Java. Java changes are aligned only when they expose, package, adapt,
compatibility-test, or error-map behavior owned by the Rust runtime.

## Product Boundary

DocTruth answers:

```text
Where did this extracted document field come from?
```

DocTruth should stay focused on document evidence. Do not expand it into agent
memory, team workflow, hosted SaaS governance, insurance scoring, a vector
database wrapper, or a general document chatbot. Commercial hosted governance
belongs in Infer Cloud. Agent memory and replay ledger behavior belongs in
Memtruth.

## Public Contracts

Keep these surfaces stable and versioned:

```text
TrustDocument
TrustUnit
TrustPage
TrustTable
EvidenceSpan/source-map semantics
audit JSON
parser warnings
benchmark-corpus manifests
Rust runtime stdin/stdout protocol
Java SDK/CLI compatibility contracts
```

When changing parser behavior, add tests at the Rust runtime boundary first.
Then add Java tests only for SDK/CLI packaging, adapter behavior, compatibility,
or error mapping.

## Parser Reference Boundaries

DocTruth can learn from strong parser projects, but they must not create
competing canonical outputs:

```text
pdf_oxide       Rust PDF substrate
Kreuzberg       Rust runtime/model/cache/worker architecture reference
Docling         unified document model and lossy export reference
MinerU          layered markdown/content-list/middle/debug output reference
OpenDataLoader  Apache-2.0 geometry, XY-Cut++, content filters, table rules
DocTruth        TrustDocument, citations, audit gates, source maps, replay
```

`TrustDocument` is the canonical contract. External parser outputs, Markdown,
OpenDataLoader JSON, Docling-style JSON, MinerU-style `middle.json`, and model
worker responses are observations that must be normalized into DocTruth-owned
contracts before they can be audit-grade.

Kreuzberg implementation code must not be copied because its code license is
not compatible with DocTruth's OSS direction. OpenDataLoader PDF v2+
Apache-2.0 implementation ideas may be ported only with attribution, source
commit notes, and NOTICE updates. Prefer Rust-owned ports and behavior tests
over vendoring Java classes.

OpenDataLoader Bench is vendored under
`third_party/opendataloader-bench/` at the source commit recorded in its
`SOURCE.md`. Treat it as the default external parser-quality benchmark
foundation, not as a blocker waiting for DocTruth-owned human review. It
already provides PDFs, ground-truth Markdown, prediction/evaluation artifacts,
and evaluator code for reading-order, table, heading, and speed metrics.

When parser-quality evidence is needed, first build or update a DocTruth ->
OpenDataLoader Bench adapter:

```text
DocTruth Rust runtime output
  -> OpenDataLoader Bench-compatible prediction markdown/artifact
  -> OpenDataLoader Bench evaluator / evaluation.json
  -> DocTruth benchmark report external_metrics
  -> audit-grade parser-quality gate
```

Do not claim parser-quality work is blocked only because DocTruth lacks its own
human-reviewed corpus. The DocTruth-owned human-reviewed corpus and review
workstation are follow-up assets for evidence-specific labels. They supplement
OpenDataLoader Bench; they do not replace it as the first external
parser-quality gate.

If multiple parser signals disagree, do not hide the conflict. Record parser
provenance, emit warnings, and block audit-grade status for severe conflicts
such as uncertain reading order, failed quote anchoring, missing visual bbox,
or low-confidence table structure.

## Verification

For Rust parser/runtime changes:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml
sh scripts/smoke-doctruth-runtime.sh
git diff --check
```

For Rust model-worker or corpus changes, also run the relevant smoke:

```bash
sh scripts/smoke-doctruth-runtime-model-worker.sh
sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh
```

For Java SDK/CLI compatibility changes:

```bash
mvn test
mvn verify -P recorded
git diff --check
```

Do not claim the parser-runtime PRD complete while any parser-quality,
model/cache, layout/table/OCR, corpus, audit-grade, or evidence-reconciliation
behavior remains Java-only. If a Java path exists for compatibility, it must be
documented and tested as wrapper/legacy/oracle behavior, not product core.

## Contribution Rules

- Use TDD for non-trivial behavior changes.
- Keep generated artifacts and private fixture corpora out of git.
- Do not commit secrets, customer documents, API keys, or production-like data.
- Add ADRs for dependencies that affect runtime, model execution, storage,
  protocol, security, networking, cryptography, policy, public API shape, or
  release packaging.
- Prefer small, reviewable units, but split by responsibility rather than rigid
  line-count rules.
- One concept per commit and PR.
