# Parser Quality Replication Plan

Date: 2026-06-17

## Current Truth

The OpenDataLoader Bench runner is now real enough to show that DocTruth parser
quality is still behind the strongest references. The latest optimized timeout
run on the vendored 200-PDF corpus produced:

| Engine | Overall | NID | TEDS | MHS |
| --- | ---: | ---: | ---: | ---: |
| DocTruth `doctruth-runtime-optimized-timeout` | 0.549 | 0.766 | 0.065 | 0.122 |
| OpenDataLoader | 0.831 | 0.902 | 0.489 | 0.739 |
| Docling | 0.882 | 0.898 | 0.887 | 0.824 |
| OpenDataLoader hybrid | 0.907 | 0.934 | 0.928 | 0.821 |

After the first replication pass, DocTruth has a measurable export-layer lift
but is still far from reference parity:

| Engine | Overall | NID | TEDS | MHS |
| --- | ---: | ---: | ---: | ---: |
| DocTruth `doctruth-runtime-replication-pass2` | 0.563 | 0.739 | 0.188 | 0.196 |

Pass2 is better than `doctruth-runtime-optimized-timeout` on overall score,
TEDS, and MHS, but it still loses NID and does not reproduce OpenDataLoader or
Docling quality. The pass2 work should be treated as a diagnostic and export
compatibility lift, not as completed parser-core parity.

This means the current gap is not only Markdown rendering. The largest missing
quality is:

- table reconstruction: `TEDS 0.065` versus `0.489-0.928`
- heading hierarchy: `MHS 0.122` versus `0.739-0.824`
- reading order/text normalization: `NID 0.766` versus `0.898-0.934`

The previous OpenDataLoader-inspired Rust slices ported useful local behavior,
but they did not reproduce the complete parser-quality pipeline. Do not treat
the XY-Cut++, filter, or export-layer slices as quality parity.

## Reference Pipelines

### OpenDataLoader Base

The benchmark adapter runs `opendataloader_pdf.convert(...)` or its JAR with:

```text
format = markdown
table_method = cluster
image_output = off
quiet = true
```

This is the target for the first parity milestone because it is Apache-2.0,
fast, and has published bench output.

### OpenDataLoader Hybrid

The hybrid reference starts `opendataloader_pdf.hybrid_server` and runs:

```text
hybrid = docling-fast
format = markdown
image_output = off
```

This is not a single Rust heuristic. It is a composition of OpenDataLoader's
layout/table/export path with Docling-assisted handling for hard cases. Treat
it as the high-accuracy target, not the first Rust-core baseline.

### Docling

The benchmark Docling runner uses:

```text
DocumentConverter().convert(pdf).document.export_to_markdown()
```

Docling is a strong reference for unified document modeling, table output, and
heading hierarchy. It should be used as a reference/oracle in evaluation and
triage, not as DocTruth's canonical schema.

## Canonical Boundary

DocTruth's canonical output remains:

```text
TrustDocument
content_blocks.json
parse_trace.json
clean Markdown + source map
audit/review package
```

External parser outputs are observations only. No external Markdown, Docling
document, OpenDataLoader result, or hybrid output becomes canonical until it is
normalized into `TrustDocument` and replayable evidence anchors.

Java/PDFBox remains wrapper, compatibility, and differential-oracle surface
only. Parser-quality work belongs in `runtime/doctruth-runtime`.

## Why Quality Is Still Low

The current DocTruth optimized run mostly emits text-layer line spans and
export-layer guesses. That helps narrative text but fails the main benchmark
metrics:

1. Tables are often not detected as structured tables, so TEDS is near zero.
   Export fallbacks fix simple cases but cannot recover complex rowspan,
   colspan, multi-header, or continuation tables.
2. Heading promotion is heuristic and not tied to a real section tree. MHS
   stays low because Markdown heading levels and heading/content grouping are
   wrong or missing.
3. Reading order still needs stronger paragraph joining, dehyphenation,
   header/footer/page-number suppression, tagged-structure trust scoring, and
   multi-column/sidebar ordering across real PDFs.
4. Scanned/no-text PDFs still need real OCR routing in the benchmark path.
5. We do not yet have an automated per-case diff loop that compares DocTruth,
   OpenDataLoader, Docling, and ground truth by failure category.

## Replication Strategy

### Phase A: Reference Oracle Harness

Status: complete for local vendored artifacts.

Build a dev-only reference lane that can run or consume:

- OpenDataLoader base predictions
- OpenDataLoader hybrid predictions
- Docling predictions
- DocTruth predictions
- ground-truth Markdown

The harness should produce per-document comparison records:

```text
document_id
fixture type
DocTruth scores
OpenDataLoader scores
Docling scores
metric deltas
top failing metric
failure bucket
paths to GT/prediction Markdown
paths to TrustDocument/content_blocks/parse_trace when available
```

Done when the report can answer: "which 20 PDFs lose the most score, and why?"

### Phase B: Metric-Specific Triage

Status: complete for local vendored artifacts.

Classify failures by the metric they damage:

| Metric | Failure buckets |
| --- | --- |
| NID | bad reading order, broken paragraph join, duplicated text, missing text, header/footer noise, soft hyphen artifacts |
| TEDS | table missed, row split wrong, column split wrong, rowspan/colspan missing, HTML/GFM rendering mismatch, table continuation missed |
| MHS | title missed, heading level wrong, heading text noisy, heading/content association wrong, false heading promotion |
| Speed/resource | slow page, timeout, worker startup cost, OCR/model route invoked incorrectly |
| Replay | quote not anchorable, bbox missing, parse trace span missing, source hash mismatch |

Done when every low-score case has a stable bucket and a reproducible fixture.

### Phase C: Reading Order and Text Normalization

Status: partial. Pass2 added page-number filtering and false table suppression,
but NID is still `0.739`, below the previous optimized-timeout `0.766` and far
below the OpenDataLoader/Docling reference range.

Target the OpenDataLoader base NID range first:

- prefer trustworthy tagged-PDF structure trees
- strengthen XY-Cut++ only where structure is absent or suspect
- suppress page numbers, repeated headers/footers, duplicate/background text
- dehyphenate line wraps
- join paragraph lines without flattening lists/tables
- preserve quote anchors through `parse_trace`

Short-term target:

```text
NID >= 0.84
NID-S >= 0.86
```

Mid-term target:

```text
NID >= 0.89
NID-S >= 0.89
```

### Phase D: Table Cluster Port

Status: partial. Pass2 fixed row/column range export and added guarded spatial
table fallback, lifting TEDS to `0.188`, but real Rust-core table clustering and
complex table structure remain pending.

Port OpenDataLoader-style `table_method=cluster` behavior into Rust-owned
DocTruth logic with attribution and tests:

- table presence detection
- bordered-grid detection
- whitespace/text-spatial clustering for borderless tables
- row and column boundary inference
- merged-cell inference
- table caption association
- continuation/adjacent-page table handling
- deterministic HTML table rendering for bench compatibility
- TrustTable/TrustUnit evidence and bbox preservation

Short-term target:

```text
TEDS >= 0.25
TEDS-S >= 0.30
```

Mid-term target:

```text
TEDS >= 0.45
TEDS-S >= 0.50
```

Hybrid target:

```text
TEDS >= 0.80
```

### Phase E: Heading and Section Tree

Status: partial. Pass2 export-layer heading promotion reduced missing-heading
failures, but MHS is still `0.196` and heading hierarchy mismatch is the largest
remaining failure bucket.

Build a real section model instead of export-only heading promotion:

- title detection from font size/weight/position
- heading detection from font/style/numbering/spacing
- heading level assignment
- heading/content grouping
- false-heading suppression for table cells, headers, sidebars, and captions
- Markdown heading rendering from the section tree
- `content_blocks.json` and `parse_trace.json` section linkage

Short-term target:

```text
MHS >= 0.45
MHS-S >= 0.55
```

Mid-term target:

```text
MHS >= 0.70
MHS-S >= 0.80
```

### Phase F: OCR and Model Routing

Benchmark scanned/no-text cases through the existing Rust-owned worker route:

- detect no-text or low-text pages
- route OCR through configured local worker
- preserve OCR bbox/confidence in TrustDocument
- block audit-grade when OCR confidence is low
- keep model workers optional and local-first

This phase should not make OCR mandatory for normal text-layer PDFs.

### Phase G: Optional Hybrid Advisor

Use Docling/OpenDataLoader hybrid as a dev/test advisor:

- compare DocTruth parse trace to Docling/OpenDataLoader output
- record disagreements as warnings or triage labels
- use disagreement cases to add Rust tests
- do not make Docling output canonical
- do not add heavy hybrid runtime as default OSS path

Hybrid can be an enterprise/high-accuracy mode later, but the OSS default must
remain local, Rust-owned, and dependency-conscious.

## TDD Shape

For each metric slice:

1. Pick the worst 5-20 real PDFs from the bench report.
2. Add minimal Rust fixtures or copied public bench cases where license allows.
3. Write RED tests at the Rust runtime boundary.
4. Implement the parser behavior in `runtime/doctruth-runtime`.
5. Run focused tests.
6. Run a partial OpenDataLoader Bench subset.
7. Run full 200-PDF bench before claiming score movement.
8. Record exact metrics and changed case IDs.

## Acceptance Targets

### Near-Term

```text
overall >= 0.65
NID >= 0.84
TEDS >= 0.25
MHS >= 0.45
full bench completes with bounded timeouts
```

### OpenDataLoader Base Parity

```text
overall >= 0.80
NID >= 0.89
TEDS >= 0.45
MHS >= 0.70
```

### High-Accuracy Reference Range

```text
overall >= 0.88
NID >= 0.90
TEDS >= 0.85
MHS >= 0.80
```

Reaching the high-accuracy range probably requires a hybrid/model-assisted path,
not only deterministic text-layer heuristics.

## Immediate Next Work

1. Extend the new Rust `parseTrace.pages[].textSpans[]` observation layer into
   real XY-Cut++ diagnostics and per-page debug span artifacts, so reading-order
   fixes can be tested before Markdown export.
2. Move table-cluster behavior from export-layer fallback into
   `runtime/doctruth-runtime`, with Rust fixtures for bordered, borderless,
   merged-cell, continuation, and OpenDataLoader-style `method="cluster"`
   cases.
3. Calibrate the Rust-owned section tree against real
   `heading_hierarchy_mismatch` failures: centered titles, sidebar labels,
   title/subtitle stacks, and false title-case body lines. The section metadata
   contract now exists; the remaining work is benchmark-grade inference.
4. Restore and lift NID with paragraph joining, dehyphenation, header/footer
   suppression, and safer multi-column ordering.
5. Run the OCR/model-worker path against no-text/scanned benchmark cases so
   zero-score OCR pages are not silently treated as text-layer failures.
6. Keep generated prediction artifacts ignored unless a small fixture is
   intentionally checked in for a RED test.
