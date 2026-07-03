# OpenDataLoader Pipeline Parity Design

## Goal

Make DocTruth converge on OpenDataLoader-quality parsing by aligning the
processor pipeline as a whole, not by tuning individual benchmark samples.

The target is not to make OpenDataLoader output canonical. `TrustDocument`
remains canonical. OpenDataLoader is the behavior reference for parser-quality
processors, benchmark fixtures, and full200 evaluation.

## Current Problem

DocTruth already has useful OpenDataLoader-inspired behavior:

- text filtering probes
- paragraph and structure probes
- heading/list/caption slices
- table border and classifier probes
- Java-core full200 benchmark runs
- Rust runtime/model-worker orchestration
- MNN OCR/table routing hooks

The remaining issue is structural. Many rules are implemented as focused
heuristics or case-family repairs. That raises full200 scores, but it does not
prove that DocTruth follows the same processor-level output behavior as
OpenDataLoader. This makes future changes fragile: fixing one low-score sample
can regress another layout class.

## Selected Approach

Use a dedicated OpenDataLoader pipeline-parity layer.

This layer does not create a second canonical schema. It records and enforces
the processor order, inputs, outputs, warnings, and parity status needed to
normalize OpenDataLoader-like behavior into DocTruth-owned `TrustDocument`
output.

Rejected alternatives:

- Low-score-sample tuning first: useful for triage, but it keeps the project in
  sample-patch mode.
- Rust/MNN replacement first: important for resource profile, but current
  quality gaps are mostly processor behavior and output semantics, not the
  runtime language.

## Reference Pipeline

The parity layer should model this processor order:

```text
PDF text extraction
-> text normalization
-> hidden/off-page/tiny/duplicate filtering
-> line grouping
-> paragraph merge
-> heading hierarchy
-> list grouping
-> caption binding
-> table border detection
-> borderless table clustering
-> table structure normalization
-> chart/table false-positive gate
-> OCR/table model routing
-> reading order
-> TrustDocument export
```

Every stage must answer:

```text
What does OpenDataLoader do?
What does DocTruth do now?
Is the DocTruth behavior matched, partial, missing, skipped, or blocked?
Which focused contract test proves it?
Which full200 bucket or case evidence proves it at corpus level?
```

## Components

### 1. Processor Parity Matrix

Add a checked-in matrix that lists upstream processor coverage. Each row should
include:

- processor name
- upstream source path or source area
- DocTruth owner module
- status: `matched`, `partial`, `missing`, `intentionally_skipped`, `blocked`
- focused test path
- full200 evidence artifact
- remaining gap

The matrix is an engineering control. It prevents vague claims such as "we
ported OpenDataLoader" when only selected behavior slices are implemented.

### 2. Pipeline Parity Module

Create a runtime-visible parity module that exposes processor metadata and
expected stage order. This module should not parse PDFs itself at first. Its
job is to make pipeline shape testable and to give focused processors a common
place to register behavior contracts.

The module should support JSON output so benchmark scripts, docs, and doctor
commands can all consume the same status.

### 3. Heuristic Rehoming

Move existing scattered behavior into named processor areas:

- text noise rules belong to the text/content filter processor
- line and paragraph rules belong to paragraph processor
- heading/list/caption rules belong to structure processor slices
- table repairs belong to table processor slices
- OCR rescue belongs to routing/model processor slices
- Markdown prediction repairs belong behind the owning processor, not as
  untracked global post-processing

This does not require a large rewrite in one commit. It requires every new
rule to land under a named processor with a focused contract test.

### 4. Processor Behavior Contract Tests

These are not tests for a single PDF id. They are tests for a behavior family.

Examples:

```text
ListProcessor:
- bullet list
- numbered list
- wrapped continuation
- nested list
- numbered heading must not be swallowed as a list

TableProcessor:
- bordered table
- borderless clustered table
- merged header cells
- multi-segment rowspans
- chart or survey figure must not become a table

ReadingOrderProcessor:
- two columns
- full-width heading between columns
- sidebar plus body
- header/footer furniture removal
```

The point is to stop case-specific fixes. A processor contract should fail when
a whole behavior class is broken, even if one benchmark sample happens to pass.

### 5. Benchmark Gate

Full200 is the stage gate, not the inner loop.

Focused contract tests run during processor porting. Full200 runs only after a
coherent set of processors is changed. Reports must include:

- overall, NID, TEDS, MHS
- parsed and failed counts
- latency and resource metadata
- low-score buckets by failure class
- source artifact path
- comparison against the previous accepted DocTruth run
- comparison against the OpenDataLoader reference run when available

## Data Flow

```text
PDF
-> current Java-core/OpenDataLoader-compatible parser or Rust parser shell
-> named processor behavior slices
-> TrustDocument
-> OpenDataLoader Bench-compatible prediction artifact
-> evaluator
-> parser-quality report
-> parity matrix update
```

OpenDataLoader outputs and benchmark predictions are observations. They do not
replace `TrustDocument`.

## Error Handling

Severe parser disagreement must be explicit. The runtime should emit warnings
or block audit-grade status when it sees:

- uncertain reading order
- failed quote anchoring
- missing visual bbox
- low-confidence table structure
- OCR rescue replacing readable text-layer output without a quality gate
- processor output conflict between Java-core and Rust/model route

## Acceptance Criteria

This design is accepted when:

1. The parity matrix exists and is checked by tests.
2. The processor order is exposed through runtime metadata.
3. Existing scattered heuristics are mapped to named processor owners.
4. Each new parity improvement uses a processor behavior contract test first.
5. Full200 reports are used only at stage gates and include low-score buckets.
6. No production parser path depends on Python/Torch/Docling residency.
7. `TrustDocument` remains the canonical output.

