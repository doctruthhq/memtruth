# DocTruth Agent Guide

DocTruth is the document evidence engine in the doctruthhq stack. It turns
documents into structured fields, exact source quotes, page/line/bbox citations,
provenance, parser warnings, audit JSON, and `TrustDocument` output.

## Runtime Architecture

DocTruth's current parser-quality core is Java/PDFBox with
OpenDataLoader-compatible processors. This is the quality source of truth until
OpenDataLoader benchmark parity is reached and a separate Rust-core ADR is
accepted.

```text
Java SDK / CLI / API
  -> Java/OpenDataLoader-compatible parser core
  -> TrustDocument
  -> Rust runtime shell for corpus/model/process orchestration
  -> evidence-native TrustDocument
```

Java/OpenDataLoader-compatible parser core is the current quality source of
truth for:

```text
PDF parsing
PDFBox compatibility
text extraction
layout geometry
reading order
table heuristics
heading reconstruction
parser warnings
source refs
TrustDocument normalization
```

Rust owns the runtime shell and Python replacement boundary:

```text
warm backend process lifecycle
benchmark-corpus execution
OpenDataLoader Bench prediction packaging
resource accounting
model/cache verification
MNN model worker protocol
Python/Torch/Docling replacement
fail-closed model routing
```

`runtime/doctruth-runtime` is therefore the authoritative home for the local
runtime shell, model-worker boundary, benchmark runner, resource reports, and
future Rust parser modules. It is not allowed to silently replace the Java
quality core until benchmark parity proves that replacement.

`pdf_oxide` remains a useful Rust PDF substrate candidate and future parser
module, but it is not the current default parser-quality source of truth for
OpenDataLoader parity work.

Java remains the stable enterprise-facing SDK, CLI, API, packaging, lifecycle,
and current parser-quality backend. Java/PDFBox is not legacy-only in the
current OpenDataLoader parity plan.

Do not add new parser-quality, OCR/table/layout, model-execution,
benchmark-corpus, audit-grade parser, or evidence-reconciliation behavior only
to Rust when the Java/OpenDataLoader-compatible backend is the quality source of
truth. Rust changes are aligned when they expose, package, run, measure, or
model-augment behavior owned by the Java parser core.

## Resource Gates

Parser/model resource acceptance is profile-based. Do not use one absolute RSS
number as a universal product gate.

The product-level hard gates are:

```text
no Python/Torch/Docling production residency
lazy model startup
measurable model unload / idle recovery
materially lower resource use than the measured heavy oracle on the same
  machine and corpus
no unexplained regression from a previously accepted named profile
```

Each accepted parser profile must record:

```text
profile name
model manifest and model SHAs
platform and architecture
corpus scope
measurement command
cold-load RSS
warm steady RSS
peak RSS
idle-after-unload RSS
cold latency
warm latency
```

Absolute RSS numbers are profiling budgets first. They become regression guards
only after a benchmark report pins the exact profile. For example, if a Mac
ARM64 `edge-model` profile with a specific MNN manifest measures 451MB warm
steady RSS, that value belongs to that measured profile. The acceptance rule is
that future runs must not materially regress from that profile without an
updated benchmark report and rationale. Do not rewrite that as a global rule
such as `edge-model steady RSS <= 600MB`, and do not express acceptance as an
arithmetic shortcut such as `451MB + steady RSS <= 600MB`.

Before that first report exists, use comparative evidence instead of a fixed
number: no Python/Torch/Docling production residency, lazy model startup,
measurable unload behavior, and materially lower resource use than the measured
heavy oracle on the same machine and corpus.

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
For parser-quality behavior in the current OpenDataLoader parity plan, add Java
backend tests first, then Rust runtime tests for process lifecycle, packaging,
resource accounting, model-worker routing, and benchmark output.

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
commit notes, and NOTICE updates. Prefer Java parser-core ports for parser
quality first, with Rust ports added only after benchmark evidence supports
them.

OpenDataLoader Bench is vendored under
`third_party/opendataloader-bench/` at the source commit recorded in its
`SOURCE.md`. Treat it as the default external parser-quality benchmark
foundation, not as a blocker waiting for DocTruth-owned human review. It
already provides PDFs, ground-truth Markdown, prediction/evaluation artifacts,
and evaluator code for reading-order, table, heading, and speed metrics.

When parser-quality evidence is needed, first build or update a DocTruth ->
OpenDataLoader Bench adapter:

```text
DocTruth Java/OpenDataLoader-compatible parser output
  -> TrustDocument
  -> Rust runtime shell packaging
  -> OpenDataLoader Bench-compatible prediction markdown/artifact
  -> OpenDataLoader Bench evaluator / evaluation.json
  -> DocTruth benchmark report external_metrics
  -> audit-grade parser-quality gate
```

OpenDataLoader parity is measured, not asserted. A behavior is considered
ported only when it has a Java parser-core contract test, a Rust contract test
at the shell boundary when runtime packaging is affected, an upstream source
reference, and either a focused OpenDataLoader Bench case or a full200 report
showing the effect. Until full200 reaches the accepted baseline, DocTruth should be
described as OpenDataLoader-inspired and progressively porting parity, not
OpenDataLoader-equivalent.

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

For Java parser-quality changes:

```bash
mvn test
mvn verify -P recorded
git diff --check
```

For Rust runtime-shell, model-worker, or corpus changes:

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

For Java SDK/CLI compatibility-only changes:

```bash
mvn test
mvn verify -P recorded
git diff --check
```

Do not claim complete OpenDataLoader parity while parser-quality,
model/cache, layout/table/OCR, corpus, audit-grade, or evidence-reconciliation
behavior lacks benchmark evidence. If a Rust parser path exists, it must be
documented and tested as experimental or secondary until it matches the Java
quality core on the benchmark gate.

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
