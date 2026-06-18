# DocTruth PDF Parser Runtime PRD

Status: draft
Owner: doctruthhq maintainers
Scope: DocTruth parser/runtime layer
Last updated: 2026-06-13

## 0. Non-Negotiable Runtime Direction

DocTruth parser ownership is Rust-core by contract.

```text
Rust core/runtime:
  owns PDF parsing, reading order, layout/table/OCR routing, evidence spans,
  parser warnings, audit-grade decisions, benchmark execution, and
  TrustDocument emission.

Java:
  is only SDK, CLI, API, release packaging, lifecycle, and compatibility
  wrapper around the Rust core.

Java/PDFBox:
  is legacy migration surface and differential oracle only. It must never be
  the default parser core, the product direction, or the place where new parser
  quality work lands.
```

Missing Rust runtime is an installation/configuration error for the main parser
path. It is not a reason to silently parse with Java/PDFBox. Java/PDFBox may be
selected only through explicit legacy/oracle controls such as
`ParserBackendMode.PDFBOX` or `--backend pdfbox`.

## 1. Summary

DocTruth cannot be credible if its source evidence is wrong. The product promise
is not merely "extract text from PDFs"; it is:

```text
Every extracted field can be traced to the correct source page, text span,
layout region, table cell, and bounding box.
```

That means PDF parsing quality is a first-order product requirement. A wrong
reading order, wrong table cell, wrong section boundary, or wrong bounding box
breaks the evidence chain and makes downstream LLM extraction unverifiable.

This PRD defines the next parser runtime direction: a high-accuracy,
model-assisted, evidence-native PDF engine inspired by the runtime shape of
projects such as Kreuzberg, Docling, MinerU, and OpenDataLoader PDF, while
keeping DocTruth's own implementation, compatible licensing, and
evidence/audit semantics.

## 2. Problem

Earlier DocTruth PDF parsing used a Java/PDFBox baseline. That baseline is now
legacy/oracle only because real-world documents expose failure modes that
directly damage evidence quality:

```text
multi-column reading order
left/right resume layouts
sidebar sections swallowing main-column text
tables with missing or wrong cell boundaries
borderless tables
merged cells
scanned PDFs requiring OCR
headers/footers polluting source spans
wrong bbox unions after section coalescing
```

If these errors survive into `TrustDocument`, `EvidenceSpan`, or `Citation`,
then the audit trail becomes formally present but substantively wrong.

## 3. Product Thesis

DocTruth should become an evidence-first document runtime:

```text
Kreuzberg-style Rust runtime and local model operations
+ Docling/MinerU-style layered document contracts
+ OpenDataLoader-style geometric reading order and safety filters
+ DocTruth-level citation, provenance, confidence, audit, and replay semantics
```

DocTruth should not compete on "number of supported file formats" first. It
should compete on correctness of source grounding:

```text
field -> quote -> page -> line -> bbox -> table cell -> parser/model metadata
```

## 4. Benchmark Reference

DocTruth should not merge reference projects as equal parser cores. It should
use them as layered references:

```text
Rust PDF substrate:
  pdf_oxide by default for PDF bytes, object parsing, text extraction,
  structure-tree-aware reading order, XY-Cut column-aware reading order,
  page geometry, rendering, content-stream safety checks, line-table
  heuristics, and bbox evidence. lopdf is not a DocTruth runtime dependency.

Geometry and reading order:
  pdf_oxide's native ReadingOrder::ColumnAware first. OpenDataLoader-style
  XY-Cut++ scenarios and filters are used as additional behavioral references
  where they improve resume/sidebar/header/footer/table cases.

Runtime and model operations:
  Kreuzberg-style Rust core, language wrappers, local model cache, model
  manifest, feature-gated heavy capabilities, and sidecar/worker handoff.

Document representation:
  Docling/MinerU-style lossless document model, readable block stream,
  intermediate page/block/line/span trace, and lossy Markdown/HTML exports.

Evidence and trust:
  DocTruth-owned TrustDocument, TrustUnit, source refs, warnings, audit gates,
  source maps, benchmark reports, and replay-ready artifacts.
```

This is how the system gets additive benefits instead of conflicting
heuristics: each reference project informs one layer, and `TrustDocument`
remains the single canonical contract that all parser observations must flow
through.

### PDFBox Replacement Boundary

The Rust replacement for Java/PDFBox is not a single one-for-one library swap.
It is this boundary:

```text
PDFBox today:
  PDF bytes -> text/page geometry/rendering/table heuristics -> Java objects

DocTruth v1 target:
  pdf_oxide -> Rust parser observations -> DocTruth geometry/table/OCR/model
  modules -> TrustDocument -> Java wrapper/CLI/MCP
```

Current implementation status:

```text
pdf_oxide owns:
  text-layer page extraction
  ColumnAware / XY-Cut reading order entrypoint
  span bbox extraction
  page MediaBox geometry
  rendered page PNG hashes
  raw content-stream parsing for parser-safety checks
  line-table primitive extraction

DocTruth owns:
  normalized bboxes
  TrustUnit/TrustTable/TrustPage contracts
  content_blocks.json and parse_trace.json
  warnings and audit-grade decisions
  source maps and replayable evidence artifacts
```

This means `pdf_oxide` can replace the PDFBox substrate without replacing
DocTruth's evidence model. `pdf_oxide` may emit Markdown/HTML/structured data,
but DocTruth must still normalize through `TrustDocument` so audit and replay
semantics stay stable.

### Reference Composition Guardrails

The reference projects do not compete if each one stays in its lane:

| Layer | Primary reference | DocTruth decision |
| --- | --- | --- |
| PDF substrate | `pdf_oxide` | Default Rust PDF backend for bytes, text, page geometry, rendering, structure-tree and XY-Cut reading-order entrypoints |
| Runtime packaging | Kreuzberg | Rust core first; Java/Python/JS are wrappers or launchers, not parser owners |
| Model operations | Kreuzberg | Local manifest/cache/doctor/worker handoff; heavy models opt-in |
| Reading-order edge cases | `pdf_oxide` + OpenDataLoader PDF | Use `pdf_oxide` first; port/verify OpenDataLoader-style XY-Cut++ cases where they improve two-column/sidebar/cross-layout behavior |
| Parser safety filters | OpenDataLoader PDF | Hidden/off-page/tiny/duplicate/background text filters must become Rust warnings and audit gates |
| Unified document contract | Docling | Lossless canonical model, lossy exports, provenance-rich chunks |
| Layered output products | MinerU | Markdown, flat content blocks, middle/trace structure, debug artifacts |
| Evidence/trust | DocTruth | Source refs, quote hashes, bbox/table-cell citations, audit gates, benchmark reports, replay packages |

Conflict rule:

```text
No external parser output is canonical.
No external schema is canonical.
No Java parser path is canonical.
TrustDocument is canonical.
```

Current guardrail status: `ArchitectureContractTest` asserts this composition
table and conflict rule so future docs changes cannot quietly promote
Kreuzberg, Docling, MinerU, OpenDataLoader, or Java/PDFBox into the canonical
DocTruth contract.

If `pdf_oxide`, an OpenDataLoader-style rule, a model worker, and a tagged-PDF
structure tree disagree, DocTruth should not silently pick a winner in strict
mode. It should record parser provenance, emit a warning when the disagreement
is material, and block audit-grade output for severe cases such as uncertain
reading order, missing visual bbox, low-confidence table structure, or failed
quote anchoring.

Kreuzberg is a useful engineering benchmark because it combines Rust core,
language bindings, CLI/API/MCP deployment, ONNX-based layout detection, table
structure recognition, model caching, and feature-gated heavy capabilities.

Important Kreuzberg reference points:

- Layout detection uses RT-DETR v2 over rendered page images and detects 17
  document layout classes such as text, table, title, form, list item,
  key-value region, headers, footers, captions, and figures.
- The parser core direction is Rust/native. Current Kreuzberg-style Rust PDF
  backend learning should track `pdf_oxide` for text/page extraction and
  rendering-oriented Rust workflows. Other language packages should be
  bindings, wrappers, or launchers around that core, not parallel parser
  implementations.
- Table structure recognition is configurable after table-region detection.
  Kreuzberg documents these model choices:
- Token-efficient wire formats are useful for LLM/RAG pipelines when full JSON
  is too verbose.
- GFM-quality Markdown/HTML rendering matters because downstream agents depend
  on fenced code blocks, table nodes, escaping, and cross-format parity.
- HTML-to-Markdown should avoid lossy intermediate round-trips when the source
  is already HTML.
- Streaming parsers are important for large documents and batch workloads.

| Model | Role | Approx size | Intended use |
| --- | --- | ---: | --- |
| RT-DETR v2 | page layout detection | 169 MB | complex layouts, multi-column PDFs, forms, scanned PDFs |
| TATR | table structure recognition | ~29-30 MB | default, fast, general-purpose tables |
| SLANet-plus | table structure recognition | 7.78 MB | smallest local/edge model |
| SLANeXT Wired | table structure recognition | ~365 MB | bordered/gridlined tables |
| SLANeXT Wireless | table structure recognition | ~365 MB | borderless tables |
| SLANeXT Auto | table structure recognition | ~737 MB | highest-accuracy mixed-table routing |

Licensing constraint:

Kreuzberg code is licensed under Elastic License 2.0. DocTruth must treat it as
a product/architecture benchmark only. Do not copy implementation code into
DocTruth. Model artifacts must be evaluated independently by their own licenses
and provenance.

References:

- Kreuzberg Layout Detection Guide: https://docs.kreuzberg.dev/guides/layout-detection/
- Kreuzberg Features: https://docs.kreuzberg.dev/features/
- Kreuzberg layout models: https://huggingface.co/Kreuzberg/layout-models
- Kreuzberg license: https://github.com/kreuzberg-dev/kreuzberg/blob/main/LICENSE

Docling is a useful product and contract benchmark because it centers the
pipeline around a unified document representation, parser backends, pipelines,
lossless JSON serialization, lossy Markdown/HTML exports, provenance items, and
chunking metadata for downstream AI use.

Important Docling reference points:

- A single document model represents text, tables, pictures, captions, lists,
  hierarchy, headers/footers, layout bounding boxes, and provenance.
- JSON is the lossless representation. Markdown and HTML are useful consumption
  exports but cannot carry every metadata field.
- Parser backends and pipelines construct and enrich the document model.
- Chunks should carry enough metadata to preserve section context and source
  provenance for RAG/agent use.

References:

- Docling document model and architecture: https://arxiv.org/html/2501.17887v1
- Docling technical report: https://arxiv.org/html/2408.09869v3
- Docling supported formats: https://docling-project.github.io/docling/usage/supported_formats/
- Docling document converter: https://docling-project.github.io/docling/reference/document_converter/
- Docling document reference: https://docling-project.github.io/docling/reference/docling_document/
- Docling chunking concepts: https://docling-project.github.io/docling/concepts/chunking/

OpenDataLoader PDF is a useful parser-algorithm benchmark because its current
core is Apache-2.0, its output contract centers bounding boxes and reading
order, and its deterministic parser includes XY-Cut++ reading-order logic,
tagged-PDF structure-tree support, header/footer filtering, hidden/off-page
content filtering, and table border/cluster processing.

Important OpenDataLoader reference points:

- `XYCutPlusPlusSorter` is Apache-2.0 and can be ported into DocTruth's Rust
  runtime as a DocTruth-owned `reading_order::xy_cut_plus_plus` module.
- Its XY-Cut++ behavior covers cross-layout elements, adaptive horizontal vs
  vertical cuts, narrow-outlier filtering, two-column layouts, sidebars, and
  row/column ordering.
- Its content filtering removes hidden text, out-of-page content, duplicated
  chunks, background artifacts, tiny text, invalid characters, and whitespace
  noise before semantic grouping.
- Its tagged-PDF path uses the PDF structure tree when available, instead of
  always guessing reading order from geometry.
- Its table flow combines bordered-table processing, cluster-based table
  detection, cell normalization, nested table limits, and adjacent table
  continuation checks.
- Its batch guidance is operationally important: avoid repeatedly starting a
  heavy parser process for every page or file when a persistent runtime or
  batch call can amortize startup.

Licensing constraint:

OpenDataLoader PDF v2+ is Apache-2.0. If DocTruth ports implementation ideas or
tests from OpenDataLoader, preserve the Apache header/attribution, add a NOTICE
entry for Hancom/OpenDataLoader PDF, and record the source commit. Do not copy
from pre-2.0 MPL-licensed revisions.

References:

- OpenDataLoader PDF: https://github.com/opendataloader-project/opendataloader-pdf
- OpenDataLoader PDF license: https://github.com/opendataloader-project/opendataloader-pdf/blob/main/LICENSE
- OpenDataLoader PDF NOTICE: https://github.com/opendataloader-project/opendataloader-pdf/blob/main/NOTICE
- XY-Cut++ sorter: https://github.com/opendataloader-project/opendataloader-pdf/blob/main/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/readingorder/XYCutPlusPlusSorter.java
- XY-Cut++ tests: https://github.com/opendataloader-project/opendataloader-pdf/blob/main/java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/readingorder/XYCutPlusPlusSorterTest.java

OpenDataLoader Bench is vendored in
`third_party/opendataloader-bench/` and should become DocTruth's
parser-quality foundation, not a replacement for DocTruth's evidence benchmark.
Its public benchmark focuses on the substrate quality that evidence depends on:

```text
reading order
table fidelity
heading hierarchy
parse speed
```

The integration target is an adapter, not a fork:

```text
DocTruth Rust runtime
  -> OpenDataLoader Bench prediction format
  -> OpenDataLoader metrics and evaluation.json
  -> DocTruth benchmark report external_metrics
  -> DocTruth evidence/replay/audit metrics
```

Use OpenDataLoader Bench metrics as the lower parser-quality gate:

```text
NID   reading-order/edit-distance quality
TEDS  table-structure similarity
MHS   heading hierarchy similarity
speed parser throughput/latency
```

Then keep DocTruth-only evidence gates above it:

```text
bbox_coverage
bbox_iou
quote_anchor_accuracy
evidence_span_accuracy
source_map_validity
audit_grade_pass_rate
replay_integrity
```

Policy: a parser-quality failure must block audit-grade evidence. If reading
order, table fidelity, heading hierarchy, or speed/resource gates are below the
declared threshold for a corpus profile, downstream evidence spans may still be
emitted for review, but they must not be promoted as audit-grade by default.

Licensing and execution posture:

- OpenDataLoader Bench is Apache-2.0 and is vendored with its license,
  third-party notices, PDFs, ground-truth Markdown, prediction artifacts,
  evaluator code, and charts.
- Do not vendor or execute AGPL/GPL/commercial engines from the benchmark suite
  in DocTruth CI. Keep such engines as external published prediction artifacts
  only when useful for comparison.
- The DocTruth runner should execute DocTruth's Rust runtime and permissive
  reference engines only.

References:

- OpenDataLoader Bench: https://github.com/opendataloader-project/opendataloader-bench
- OpenDataLoader Bench license: https://github.com/opendataloader-project/opendataloader-bench/blob/main/LICENSE

### Benchmark Learning Status

This table is the source of truth for what has been learned, implemented, and
verified from the reference projects. "Complete" means the behavior is covered by
DocTruth-owned tests or smoke scripts. "Partial" means the contract or adapter is
implemented, but the broad accuracy or Rust-core ownership requirement is still
open.

| Source | Learned capability | DocTruth status | Evidence | Remaining gap |
| --- | --- | --- | --- | --- |
| Kreuzberg | Rust parser/runtime as the product core | Complete for Goal 1 defaultization, partial for broad parser-quality depth | `runtime/doctruth-runtime` has `parse_pdf`, `benchmark_corpus`, model-worker handoff, packaged sidecar, Java CLI/MCP/SDK sidecar wiring, OCR runtime-first selection, path-first SDK backend selection, Rust-default CLI shorthand output, missing-runtime failures for default TrustDocument parsing, and `pdf_oxide` text-layer extraction, page geometry, rendered image hashes, bbox-backed line units, content-stream safety checks, and line-table extraction | Future parser-quality phases must broaden labeled accuracy coverage and real-world table/OCR/model calibration |
| Kreuzberg | `pdf_oxide`-style Rust PDF backend | Complete for default PDF substrate and table/debug MVP | Current Rust runtime uses `pdf_oxide` for text-layer page extraction, span bbox evidence, column-order post-processing, page MediaBox geometry, default rendered PNG page hashes, raw content-stream safety checks, and line-table extraction; `parserRun.pdfBackend.current` reports `pdf_oxide` and `status` reports `DEFAULT` | Broaden against real-world PDFs and keep model-assisted layout/table/OCR accuracy gates calibrated |
| Kreuzberg | Local model cache and manifest-driven model handoff | Complete for cache/manifest/handoff, partial for production execution | Cache warm, SHA verification, model descriptors, runtime hints, Java and Rust doctor output, Java and Rust worker request metadata, Rust runtime real RT-DETR/TATR artifact entrypoint, and Rust SLANeXT/OCR worker protocol smokes | Production model execution is still through external Python workers, not in-process Rust, and broad accuracy/release artifact evidence is still pending |
| Kreuzberg | RT-DETR-style layout detection | Complete for adapter/smoke and Rust runtime real-artifact entrypoint, partial for accuracy | Synthetic ONNX RT-DETR decoder smokes, opt-in public `Kreuzberg/layout-models` RT-DETR smoke, and `DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1` Rust runtime smoke | Broad labeled multi-layout corpus and calibrated layout-quality targets |
| Kreuzberg | TATR-style table structure recognition | Complete for adapter/smoke and Rust runtime real-artifact entrypoint, partial for accuracy | Synthetic TATR decoder smokes, opt-in public Xenova TATR ONNX smoke with rendered-page input and row/column post-processing, and `DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1` Rust runtime smoke | Calibrated table normalization and labeled real-world table corpus |
| Kreuzberg | SLANeXT/PaddleOCR-style server table recognition | Complete for adapter/runtime protocol, opt-in Java-side real smoke, and generated Rust-route real smoke; partial for accuracy | Packaged `doctruth-slanext-table-worker`, Java and Rust runtime worker smokes, fake worker smoke, opt-in real PaddleOCR/SLANeXT smoke, and `DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE=1` through `doctruth-runtime parse_pdf` in an isolated `paddleocr+paddlepaddle` environment | Broad borderless/mixed-table corpus and calibrated SLANeXT/PaddleOCR table accuracy |
| Kreuzberg | Feature-gated heavy capabilities | Complete | Real model and OCR smokes are opt-in, skip safely by default, and release workflow has explicit real-model gate wiring | Remote release run evidence still needed before claiming release artifact quality |
| Kreuzberg | Token-efficient wire format and GFM-quality output | Complete for local contracts | Compact LLM output, JSONL/Markdown renderers, source maps, streaming writer paths, GFM table rendering, HTML review anchors | Exact Kreuzberg TOON format is not copied or claimed |
| Kreuzberg | Streaming and large-document posture | Partial | Writer-based render paths and Rust sidecar protocol exist | True streaming parse for multi-GB documents is not complete |
| Docling | Unified document model | Complete for v1 contract | `TrustDocument`, `TrustUnit`, `TrustPage`, `TrustTable`, provenance, warnings, parser/model metadata | Contract can still grow for images/captions/forms as model coverage expands |
| Docling | Lossless JSON with lossy Markdown/HTML exports | Complete | Deterministic JSON/audit/Markdown/HTML/plain/compact render contracts and source-map sidecars | Export parity should be rechecked when new unit kinds are added |
| Docling | Provenance-first chunks for AI/RAG | Complete for v1 | Chunk/source-map/evidence contracts, compact LLM output, MCP evidence tools, citation verification | Broader chunking strategy can improve after real corpus feedback |
| Docling | Parser backend/pipeline separation | Complete for Goal 1 defaultization, partial for later legacy-API migration | Parser presets, explicit Java oracle mode, sidecar backend, local worker protocols, Rust runtime commands, SDK backend modes, path-first Rust SDK parsing, MCP Rust parsing, and Rust-default CLI shorthand output | Java/PDFBox is not a primary parser core. Legacy document-first extraction remains compatibility-only until that older extraction API is reworked around the Rust runtime. |
| OpenDataLoader PDF | XY-Cut++ reading order | Complete for Rust MVP, partial for broad corpus | Rust runtime has an attributed OpenDataLoader-style XY-Cut++ sorter covering cross-layout elements, adaptive horizontal/vertical cuts, narrow-outlier gap retry, two-column layouts, row-section preference, and sidebars | Broaden against labeled real-world PDF corpus and keep Java out of parser ownership |
| OpenDataLoader PDF | Tagged-PDF structure tree preference | Complete for Rust MVP, partial for broad semantic tag export | Rust runtime uses `pdf_oxide` canonical page reading order so trustworthy Tagged-PDF structure trees beat geometric ordering, emits `parserRun.readingOrder` and `parseTrace.readingOrder`, and falls back to XY-Cut with a structured warning when `/MarkInfo /Suspects true` marks the tree unreliable | Broaden against real tagged PDFs and expose richer role/heading/list/table semantics through `TrustDocument` without making external parser schemas canonical |
| OpenDataLoader PDF | Parser safety/content filters | Complete for Rust MVP, partial for broad visual validation | Reference content filters remove hidden/off-page/tiny/duplicate/background text and whitespace artifacts before grouping; Rust runtime now filters duplicate, whitespace-only, off-page, tiny, near-white/background-like, and invisible render-mode text-layer spans, emits severe parser-safety warnings, and blocks audit-grade output | Add robust rendered-page background comparison and broaden warning taxonomy against labeled real-world fixtures |
| OpenDataLoader PDF | Table border/cluster heuristics | Complete for Rust MVP, partial for broad table accuracy | Rust runtime normalizes `pdf_oxide` text-spatial borderless table detection plus `pdf_oxide` content-stream line-table extraction into `TrustDocument` tables; covered behavior includes bordered grids, merged cells, row spans, and adjacent-page continuations | Broaden table metrics against labeled real-world fixtures and calibrate model-assisted table recognition |
| OpenDataLoader Bench | Parser-quality foundation | Vendored, runner wired, first full baseline recorded locally | `third_party/opendataloader-bench/` supplies public parser-quality concepts for reading order, table fidelity, heading hierarchy, speed, ground-truth/prediction/evaluation artifacts, and NID/TEDS/MHS-style metrics. `scripts/run-doctruth-opendataloader-bench.sh` exports DocTruth Rust runtime predictions into OpenDataLoader Bench shape and runs the Rust evaluator by default; the official evaluator is explicit oracle-only. | Improve DocTruth Markdown/table/heading export and parser robustness until the real OpenDataLoader Bench baseline is competitive enough to act as an audit-grade parser-quality gate |
| RapidOCR/MNN | Local OCR worker behind strict protocol | Complete for adapter/runtime protocol and generated real Rust-route OCR smoke, partial for MNN/labeled quality | Packaged RapidOCR worker, fake readiness tests, isolated RapidOCR + ONNXRuntime smoke, generated OCR corpus gate, Rust runtime OCR worker smoke, and `DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE=1` through `doctruth-runtime parse_pdf` | MNN backend install path and labeled real-world scanned-PDF OCR corpus |
| DocTruth-specific | Evidence-grade audit and replay boundary | Complete for v1 contracts | Severe warning taxonomy, audit-grade blocking, source hash, bbox/table-cell evidence, review package, MCP document evidence tools | Parser accuracy still depends on broad labeled corpus and Rust-core migration |

## 5. Goals

### G1. Evidence-Grade PDF Structure

DocTruth must emit a layout-aware `TrustDocument` with source objects that are
stable enough for field-level citation:

```text
Page
LayoutRegion
TextBlock
LineSpan
TableRegion
TableCell
EvidenceSpan
TrustUnit
```

Every object that can support an extracted field must carry:

```text
page number
normalized bbox
raw text
reading-order index
parser backend
model backend when used
confidence
source hash or page image hash
```

Current Java/PDFBox baseline status: `PdfBoxParserBackend` now renders each page
at 72 DPI with PDFRenderer and records the rendered page pixel dimensions plus a
SHA-256 hash of the rendered PNG bytes in `TrustPage.imageHash`. The SDK
`PdfPageImageRenderer.writePngs(...)` and CLI
`doctruth render-pages <document> -o <dir>` can also persist deterministic
`page-%04d.png` review artifacts plus a `page-images.json` manifest. The CLI
`doctruth review-package <document> -o <dir>` writes a local static review
package with `review.html`, `trust-document.json`, page image artifacts,
`content_blocks.json`, `parse_trace.json`, `layout-debug.html`, and
`span-debug.html`. The Phase 250 debug HTML artifacts carry trace-id data
attributes that match `parse_trace.json`, so layout and span visual review uses
the same trace ids as the machine-readable page/block/line/span trace.
Rust `doctruth-runtime` now reads PDF MediaBox dimensions and default rendered
PNG page bytes through `pdf_oxide`, then hashes those bytes for
`TrustPage.imageHash`. `DOCTRUTH_RUNTIME_PAGE_RENDERER` remains an explicit
override for compatibility checks; otherwise render failures fall back to a
stable content/dimension hash. Runtime tests compare `imageHash` against
`pdf_oxide` rendered PNG bytes.

### G2. Model-Assisted Layout And Tables

DocTruth should keep a fast heuristic/text-layer baseline, but add optional
model-assisted paths for hard PDFs:

```text
layout detection
table detection
table structure recognition
OCR routing
region-aware reading order
cell-level evidence spans
```

Current status: model-assisted presets now have an explicit local model-worker
protocol instead of only falling through to heuristic PDFBox parsing. When
`doctruth.model.command`, `DOCTRUTH_MODEL_COMMAND`, or `LOCAL_MODEL_COMMAND` is
configured, `TrustDocumentParser` sends the preset, source hash, source bytes,
and required model descriptors to the worker over JSON stdin/stdout. A
`TABLE_LITE` contract test and CLI smoke prove a configured worker can return a
full `TrustDocument` with model-produced `TrustTable` and `TABLE_CELL` units,
`parserRun.backend=rust-sidecar+model-worker`, optional worker-level
provenance such as `workerBackend`, and no `model_unavailable_fallback`.
This is a runtime boundary and replay contract, not production RT-DETR/TATR/
SLANeXT accuracy yet. DocTruth now also ships `scripts/doctruth-onnx-model-worker`,
a local JSON model-worker adapter that imports ONNXRuntime, loads a
SHA-verified cached ONNX artifact, executes one session run, and returns a
`TrustDocument` through the same Java model-worker path. The ONNX smoke creates
a tiny identity model locally and proves real ONNXRuntime loading/execution,
cache warm, doctor, and parse integration. A second ONNX smoke now creates a
TATR/DETR-like model with `pred_logits` and `pred_boxes`; the worker decodes
the table/cell detections into `TrustTable` and `TABLE_CELL` units. A
low-confidence table smoke verifies table/cell structure detections below
`0.85` preserve the table for review/replay while emitting severe
`table_structure_low_confidence` and downgrading the document to
`NOT_AUDIT_GRADE`. A third ONNX smoke creates an RT-DETR/DETR-like layout
model with the same `pred_logits`/`pred_boxes` shape and verifies
`task=layout-detection` produces bbox-bearing layout `TEXT_BLOCK` units in
reading order. A low-confidence layout smoke now verifies detections below
`0.85` preserve the region for review/replay while emitting severe
`layout_low_confidence` and downgrading the document to `NOT_AUDIT_GRADE`.
These prove local decoder contracts over
synthetic ONNX outputs, but not curated CI-owned model artifact coverage or
real-world parser accuracy. `scripts/smoke-doctruth-real-rtdetr-artifact.sh`
is an opt-in bridge for one public document-layout RT-DETR artifact: with
`DOCTRUTH_REAL_RTDETR_SMOKE=1`, it downloads or reuses
`Kreuzberg/layout-models` `rtdetr/model.onnx`, writes a SHA-pinned manifest,
warms the local model cache, and runs the Java CLI model-worker harness with
`task=layout-detection`. The worker supports RT-DETR's `images` and
`orig_target_sizes` inputs, ImageNet-normalizes the rendered page image for the
`images` input, and decodes `labels`/`boxes`/`scores` into bbox-bearing layout
`TEXT_BLOCK` units using the documented 17 document layout classes.
`scripts/smoke-doctruth-real-tatr-artifact.sh` is an opt-in bridge for one
public TATR artifact: with
`DOCTRUTH_REAL_TATR_SMOKE=1`, it downloads or reuses
`Xenova/table-transformer-structure-recognition` `onnx/model_quantized.onnx`,
writes a SHA-pinned manifest, warms the local model cache, and runs the existing
real model artifact harness through Java CLI plus `doctruth-onnx-model-worker`.
The worker now renders the first PDF page with local `pdftoppm` when available,
preprocesses it through Pillow into a 4D `[1, 3, H, W]` tensor, and reports
`metrics.inputSource=rendered_page`; non-vision and unavailable-renderer paths
fall back to `synthetic_tensor`. For the public TATR artifact, the worker now
uses the real Table Transformer label set (`table`, `table row`,
`table column`, `table column header`, projected row headers, and spanning
cells) and builds provisional cell evidence from row/column intersections
instead of treating every non-table detection as a flat cell. The opt-in smoke
asserts multi-row and multi-column cell output on a generated grid PDF. This
proves real ONNXRuntime execution plus first-pass TATR post-processing, not
production table accuracy yet because TATR-specific normalization calibration,
SLANeXT parity, and labeled table accuracy are still separate work. The packaged
ONNX adapter is split into
an executable `doctruth-onnx-model-worker` shim and same-directory
`doctruth_onnx_worker_lib.py` support module so decoder growth stays within the
package boundary. `scripts/smoke-doctruth-runtime-real-model-artifacts.sh` is
the Rust-runtime real artifact entrypoint: with
`DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1`, it downloads or reuses the public
RT-DETR and TATR ONNX artifacts, prepares SHA-pinned model manifests and the
Rust runtime model cache, invokes `doctruth-runtime` `parse_pdf` with
`DOCTRUTH_RUNTIME_MODEL_COMMAND=scripts/doctruth-onnx-model-worker`, and
asserts `parserRun.backend=rust-sidecar+model-worker` while preserving the
worker's original backend as `parserRun.workerBackend`. This proves public
RT-DETR/TATR artifact execution can be controlled from the Rust runtime path.
SLANeXT and OCR now have matching generated real-route Rust runtime smokes.
This still does not prove broad production parser accuracy. The ONNX worker split keeps
project LOC limits intact while source installs, release tarballs, Homebrew
formulae, and release smoke tests still exercise the real packaged command.
DocTruth now
also ships `scripts/doctruth-slanext-table-worker`, a PaddleOCR/SLANeXT JSON
model-worker adapter for `table-server` style table extraction. The packaged
fake-runtime smoke proves worker doctor readiness, direct JSON worker output,
Java CLI model-worker integration, and table-cell preservation without bundling
PaddleOCR or SLANeXT model binaries. `scripts/smoke-doctruth-real-slanext-artifact.sh`
is the opt-in real runtime hook for environments that have PaddleOCR/SLANeXT
installed. The real smoke has been verified in an isolated Python 3.10 venv
with PaddleOCR 3.7.0 and PaddlePaddle 3.3.1; the adapter handles PaddleOCR
3.7 `TableRecResult.json.res` output, HTML-like table structure tokens, and
flat 8-number quadrilateral bboxes. This proves runtime integration on a
generated grid PDF, not broad SLANeXT table accuracy.
`scripts/smoke-doctruth-real-model-suite.sh` is the single release/CI entrypoint
for running the public real-model smoke set together. It defaults to a safe
skip, and with `DOCTRUTH_REAL_MODEL_SUITE=1` runs RT-DETR, TATR, and SLANeXT;
`DOCTRUTH_SLANEXT_PYTHON` can point only the SLANeXT step at an isolated
PaddleOCR venv without disturbing the ONNXRuntime Python used by RT-DETR/TATR.
The suite is included in source installs and release tarballs. The release
workflow installs `poppler-utils`, ONNXRuntime/Pillow/Numpy, and
PaddleOCR/Paddle, then runs the suite with `DOCTRUTH_REAL_MODEL_SUITE=1` before
publishing release artefacts. These Python dependencies should remain pinned to
a verified compatible set, currently ONNXRuntime 1.26.0, Pillow 12.x,
`numpy<2.4`, PaddleOCR 3.7.0, and PaddlePaddle 3.3.1. Ordinary CI runs the
suite's safe skip path to catch packaging regressions without downloading large
models on every PR.

Ordinary CI also runs `scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
That smoke creates generated multi-layout, table, and OCR fixtures, writes a
`qualityProfile: "parser-accuracy"` manifest with `multi-layout`, `table`,
`ocr`, `bbox`, and `source-map` coverage tags, and gates the corpus through
`benchmark-corpus`. It proves the parser-accuracy corpus contract and metric
plumbing; it is not a substitute for the broad real-world human-labeled corpus.

### G3. Rust Core With Java Wrapper Compatibility

This is a product/runtime decision, not an optional implementation idea:
DocTruth's parser/runtime core is Rust. Java remains only the SDK, CLI, API,
packaging, lifecycle, and compatibility wrapper that calls into Rust through a
native binding or a sidecar process.

New parser-quality behavior must land in `runtime/doctruth-runtime` first:
text extraction, page rasterization, layout detection, table recognition, OCR,
model-cache verification, benchmark-corpus execution, parser warnings, and
evidence reconciliation. Java may expose, package, adapt, and compatibility-test
those capabilities, but it must not become the primary home for new parser
logic.

```text
DocTruth Java wrapper
  -> JNI/native library OR sidecar process
  -> Rust parser runtime
  -> evidence-native TrustDocument
```

The public Java API must not force users to understand Rust.

Allowed Java responsibilities:

```text
stable SDK/API facade
CLI command surface
Maven packaging
backward-compatible ParsedDocument/Citation adapters
sidecar/native process lifecycle
error mapping
public API compatibility tests
release packaging checks
```

Disallowed Java-first responsibilities:

```text
new default PDF parsing logic
new OCR/table/layout model execution logic
new parser-quality benchmark ownership
new evidence reconciliation semantics
new audit-grade parser decisions that Rust cannot reproduce
```

Java/PDFBox is a legacy migration surface and regression oracle only. It is not
a fallback product path and not the parser-runtime architecture.

### G4. Local-First Runtime

DocTruth must work locally without network calls by default. Heavy models are
downloaded only when explicitly enabled or when a preset requires them.

```text
default install: no large model download
first layout run: download verified model
doctor: verify cache, SHA256, backend availability
offline mode: use existing cache only
```

Current status: `doctruth doctor --json` reports parser availability, model
cache state, OCR worker readiness, and now configured model-worker readiness
under `models.worker`. The model-worker readiness check uses the same
local-first rule as OCR: it only probes an explicitly configured executable with
`--doctor`, reports `available` separately from `ready`, carries structured
`statusCode`/`message`, timeout, loaded model ids, and worker-reported
`rssMb`/`peakMemoryMb`, and does not download models or run inference.
Model-assisted parse requests sent to a configured worker now also include
`modelCacheDirectory` and per-model `cachePath`, `cacheStatus`, `actualSha256`,
and `actualSizeBytes`, derived from the local cache verifier. This gives real
ONNX/TATR/SLANeXT workers a stable handoff point without treating missing or
SHA-mismatched artifacts as ready. The ONNX worker direct resource smoke now
asserts real parse-time `metrics.wallMs`, `metrics.inferenceWallMs`, `rssMb`,
and `peakMemoryMb` from an actual ONNXRuntime session. This is worker-internal
measurement, not an OS-level profiler or real production model load benchmark.
Local model descriptors can also be supplied through `doctruth.model.manifest`
or `DOCTRUTH_MODEL_MANIFEST`, keyed by preset id. The packaged model-worker
smoke now creates a SHA-matched local artifact and manifest and verifies that a
configured worker receives `cacheStatus=READY`.
`doctruth doctor --json` now also reads `DOCTRUTH_MODEL_MANIFEST`, verifies all
manifest model artifacts in `DOCTRUTH_MODEL_CACHE`, and reports `allReady` plus
per-artifact identity, cache path, status, actual SHA-256, and actual size.
This gives developers a no-inference preflight for local model readiness before
they run a model-assisted preset. Manifest entries now also preserve runtime
hints (`task`, `backend`, `format`, `precision`, `license`) through
`doctruth cache warm --json`, `doctruth doctor --json`, and the local model
worker request. That keeps model identity/SHA verification separate from model
execution hints while giving future real ONNX/TATR/SLANeXT workers enough
metadata to route the correct runtime path.

Current Rust runtime status: `doctruth-runtime --doctor` also reports the local
model pipeline directly, not only through the Java CLI wrapper. It includes
native text extraction, document-structure/reading-order slots, layout/table/OCR
capability slots, the configured model manifest path, model cache directory,
per-preset model identities, `READY` / `MISSING` / `SHA_MISMATCH` cache status,
actual SHA-256 and size, worker configured/available/ready separation,
worker-reported memory fields, and runtime RSS/peak memory. This doctor path
does not download models or run inference, so it remains safe for local-first
install checks and CI capability reporting.

### G5. Measurable Parser Quality

Parser quality must be evaluated with fixtures and metrics, not screenshots
alone.

Required metrics:

```text
external_parser_quality:
  opendataloader_nid
  opendataloader_teds
  opendataloader_mhs
  opendataloader_speed

doctruth_parser_quality:
reading_order_f1
section_boundary_f1
table_region_iou
table_cell_f1
bbox_iou
quote_anchor_accuracy
evidence_span_accuracy
ocr_text_accuracy
parser_latency_p50/p95
rss_peak_mb
model_cache_size_mb

doctruth_evidence_quality:
  source_map_validity
  audit_grade_pass_rate
  replay_integrity
```

Current benchmark status: `ParserBenchmarkRunner` now reports
`section_boundary_f1` by comparing recovered heading-like section boundary
lines against expected Markdown boundaries, so corpus manifests can gate the
PRD section-boundary metric directly. It reports `evidence_span_accuracy` by
checking whether expected text lines are covered by actual units with evidence
span ids, without requiring generated internal span ids to be stable across
label and parser outputs. It also reports `ocr_text_accuracy` for OCR-backed
`TrustDocument` output by comparing OCR region text against the expected
Markdown text. Non-OCR cases score this metric as `1.0` so existing
text-layer/table corpora are not penalized. Benchmark corpus manifests can set
per-case `preset`, including `preset: "ocr"`, and the CLI `benchmark-corpus`
smoke includes generated section-boundary, evidence-span, and scanned-PDF OCR
cases gated by `section_boundary_f1`, `evidence_span_accuracy`, and
`ocr_text_accuracy`. The same smoke must also include a wrong-label OCR corpus
that exits non-zero and names the failing case plus `ocr_text_accuracy`, so OCR
labels cannot silently drift. Benchmark cases also carry runtime observations and report
`rss_peak_mb` plus `model_cache_size_mb`; `fromPdf(...)` records local JVM
memory/cache observations as a fallback, while configured workers can supply
stronger resource measurements through the benchmark case contract.
Benchmark corpus manifests now also distinguish generated fixtures from
human-labeled accuracy corpora. A manifest with `"kind": "human-labeled"` must
include `labeling.labelSetVersion`, `labeling.reviewedAt`,
`labeling.reviewer`, and non-empty `labeling.requiredMetrics`; every required
metric must have an explicit `minimums` or `maximums` threshold. The CLI JSON
output includes `kind`, `labelSetVersion`, and `requiredMetrics` so CI and
release reports cannot silently treat generated fixture gates as human-labeled
accuracy evidence. This completes the corpus contract and smoke gate for
human-labeled labels. Parser-accuracy cases now also carry case-level
`labelId` and `tags`, and `benchmark-corpus --json` emits those fields for
each case so a passing CI report can be traced back to the reviewed label set
and required coverage category. Parser-accuracy manifests must also declare
`labeling.reviewType`, currently either `generated-seed` or `human-reviewed`.
The generated seed corpus uses `generated-seed`; the future real-world accuracy
corpus must use `human-reviewed`. The public W3C remote-PDF smoke now also
declares `kind: "human-labeled"` and verifies this metadata through CLI JSON
output, but it is still a small contract fixture rather than the actual large
real-world labeled corpus. For a corpus that wants to claim parser accuracy,
the manifest must add `qualityProfile: "parser-accuracy"` plus
`labeling.requiredTags` and `labeling.minCasesPerTag`; the loader rejects the
corpus when required coverage tags such as multi-layout, table, or OCR have too
few cases. When `labeling.reviewType` is `human-reviewed`, the manifest must
also declare `labeling.minTotalCases`, and the loader rejects reports with fewer
total cases than that declared minimum. Human-reviewed parser-accuracy cases
must also include `sourceSha256`; DocTruth verifies the SHA-256 for both local
`source` files and remote `sourceUrl` cache entries before treating the label as
valid. Generated seed corpora can remain small with
`reviewType: "generated-seed"` and may omit source pins because they are
plumbing gates, not accuracy evidence. Human-reviewed parser-accuracy manifests
must declare the core parser-quality metric set in `labeling.requiredMetrics`:
`reading_order_f1`, `quote_anchor_accuracy`, `bbox_coverage`, `bbox_iou`,
`evidence_span_accuracy`, `table_cell_f1`, and `ocr_text_accuracy`. Each
declared metric must still have an explicit threshold, even when a generated
contract fixture uses a conservative threshold such as `bbox_iou: 0.0`; broad
accuracy claims require stronger human-reviewed thresholds and recorded corpus
runs. Human-reviewed parser-accuracy manifests must also declare the core
coverage tags in `labeling.requiredTags`: `multi-layout`, `table`, `ocr`,
`bbox`, and `source-map`; this prevents a broad corpus from passing while
silently omitting a major document class or evidence surface. CLI JSON emits
`qualityProfile`,
`reviewType`,
`requiredTags`, `minCasesPerTag`, and `minTotalCases` when present, so CI
reports can prove corpus scale, coverage, and label-review posture instead of
only proving that thresholds passed on a small fixture.

### G6. LLM-Efficient And Streaming Runtime

DocTruth must support AI consumption without making callers choose between
verbose lossless JSON and ungrounded plain text.

Required capabilities:

```text
lossless TrustDocument JSON for audit and replay
compact evidence wire format for LLM/RAG pipelines
GFM-quality Markdown output
HTML review output with stable anchors
HTML passthrough when source HTML can be converted directly
streaming parser and renderer paths for large files
```

The compact wire format may learn from TOON-style serialization, but DocTruth
should not commit to Kreuzberg's naming or exact format until licensing,
interoperability, and parser-contract requirements are reviewed. The product
requirement is token-efficient, deterministic, evidence-preserving
serialization.

## 6. Non-Goals

DocTruth should not become:

```text
a general RAG framework
a general document chatbot
a vector database wrapper
a hosted parser SaaS by default
a clone of Kreuzberg
a wholesale wrapper around Kreuzberg internals
a confused merge of Kreuzberg, Docling, MinerU, and OpenDataLoader pipelines
```

DocTruth may support multiple formats, but PDF evidence correctness is the
priority because PDF is where citation grounding most often fails.

## 7. User Experience

### SDK / Wrapper API

The Java API is a wrapper around the Rust runtime. It is not the parser owner.
Calls that parse into `TrustDocument` must route to `doctruth-runtime` by
default.

```java
var doc = DocTruth.withProvider(provider)
    .parsePdf("resume.pdf")
    .withParser(ParserPreset.STANDARD)
    .parse();

var result = doc.extractJson(schema)
    .withEvidence()
    .runJson();
```

For parser-only SDK use, the static entrypoint must also accept an explicit
parser preset:

```java
var doc = TrustDocumentParser.parse(path, ParserPreset.STANDARD);
```

If a model-assisted preset such as `STANDARD`, `TABLE_LITE`, `TABLE_SERVER`, or
`OCR` is requested while required local models are unavailable, the Rust runtime
may still emit a heuristic `TrustDocument` for inspection, but it must include a
severe `model_unavailable_fallback` parser warning and evaluate as
`NOT_AUDIT_GRADE`. The caller must never receive silent heuristic success for a
requested model-assisted parse. Java must not implement an independent
model-assisted parser path.

### CLI

```bash
doctruth parse resume.pdf --preset standard --out trust-document.json
doctruth parse resume.pdf --layout --table-model tatr --bboxes
doctruth doctor models
doctruth cache warm --model tatr
```

### Output Formats

DocTruth parser output must serve multiple consumers. The canonical internal
shape is `TrustDocument` JSON, but the most common downstream consumer may be an
LLM or agent. Markdown is therefore a first-class product output, not a demo
format.

Required output modes:

| Format | Primary consumer | Requirement |
| --- | --- | --- |
| JSON | SDKs, storage, audit pipelines | Lossless structure with pages, regions, tables, spans, parser/model metadata |
| Markdown | LLMs, agents, human review | Reading-order text with headings, lists, tables, and stable evidence anchors |
| Content Blocks JSON | LLM/RAG ingestion, cleanup, indexing | Flat reading-order blocks derived from the canonical parse |
| Parse Trace JSON | parser QA, audit debugging, sourceRefs | Page -> block -> line -> span intermediate evidence layer |
| HTML | review UI, bbox overlays | Layout-aware visual inspection with source regions and table cells |
| JSONL | batch/indexing pipelines | One source object, block, table, cell, or evidence span per line |
| Audit JSON | compliance/replay systems | Signed or hashable extraction evidence package |
| Compact Wire | LLM/RAG pipelines | Token-efficient deterministic representation of evidence-bearing content |

Markdown must preserve source grounding. It should not flatten the document into
untraceable prose. Every block that can be cited should carry a stable anchor:

```markdown
## Work Experience {#ev:span_042 page=1 bbox="320,140,910,410"}

Executive, Quality Assurance
Malaysia University of Science and Technology | Jun 2025 - Present

| Company | Role | Dates |
| --- | --- | --- |
| IMC Industries | Finance Admin | Sept 2024 - Present |
<!-- table:tbl_003 page=1 bbox="120,360,880,520" -->
```

However, not every consumer wants anchors inline. DocTruth output must separate
the canonical evidence-preserving representation from clean consumption
renderings:

```text
canonical output
  lossless, evidence-preserving, replayable, contains anchors and metadata

clean output
  easy to clean, easy to chunk, easy for LLMs to consume, minimal syntax noise
```

Pure Markdown mode is allowed and useful:

```text
markdown_clean
  no inline evidence anchors
  no HTML comments
  no bbox metadata
  no parser/model metadata in the body
  stable page/section breaks only when useful
```

But clean output must be derived from the same canonical parse. The caller can
choose to omit evidence from the rendered body, but DocTruth should still be
able to emit a sidecar source map when requested:

```text
document.md
document.doctruth-map.json
```

The source map links clean Markdown offsets back to evidence spans:

```json
{
  "content_hash": "sha256:...",
  "anchors": [
    {
      "markdown_start": 128,
      "markdown_end": 244,
      "evidence_span_id": "span_042",
      "page": 1,
      "bbox": [320, 140, 910, 410]
    }
  ]
}
```

DocTruth should expose MinerU-style layered parser products without copying the
MinerU schema verbatim:

```text
markdown_clean
  final human/LLM-readable rendering
  no evidence required in body

content_blocks.json
  flat reading-order block stream
  best default for LLM/RAG ingestion and cleanup

parse_trace.json
  page -> block -> line -> span intermediate evidence layer
  best default for parser QA, sourceRefs, bbox debugging, and replay inspection

trust.json
  canonical DocTruth replay/evidence contract
  stable public object model for SDKs, MCP, MemTruth, and audit exports
```

The split matters because Markdown alone is not an evidence source. It is a
rendering. `content_blocks.json` is allowed to be easy to clean and consume.
`parse_trace.json` must preserve the parser's intermediate observations,
including discarded blocks and low-confidence spans, so bugs in reading order,
multi-column layout, sidebars, headers/footers, OCR, and table segmentation can
be replayed and debugged without rerunning the parser.

`content_blocks.json` should contain only readable content in final reading
order. Typical block types:

```text
text
heading
list
table
image
chart
equation
code
header
footer
page_number
aside_text
```

Each content block should carry:

```text
block_id
type
page
bbox
reading_order
text or structured body
heading_level when applicable
source_unit_ids[]
evidence_span_ids[]
warnings[]
```

`parse_trace.json` should preserve the deeper intermediate structure:

```text
pages[]
  page_index
  page_size
  preproc_blocks[]
  reading_blocks[]
  discarded_blocks[]
  images[]
  tables[]
  equations[]

block
  block_id
  type
  bbox
  reading_order
  confidence
  model_run_id
  lines[]

line
  line_id
  bbox
  text
  spans[]

span
  span_id
  type
  content
  bbox
  score
  source_object_id
  evidence_span_id
```

The parser should also emit visual QA artifacts equivalent in purpose to
layout/span debug PDFs:

```text
layout debug artifact
  visualizes layout blocks and reading order

span debug artifact
  visualizes text/OCR spans, dropped text, equations, and segmentation
```

Current Phase 250 status: `doctruth review-package` writes
`content_blocks.json`, `parse_trace.json`, `layout-debug.html`, and
`span-debug.html` alongside the canonical review package files. The debug HTML
uses `data-trace-block-id`, `data-trace-line-id`, and `data-trace-span-id`
attributes that are verified against `parse_trace.json`. This satisfies the
review-package visual trace artifact contract, but it remains a deterministic
projection from the current parser contract. It is not a claim that Rust-native
real model/OCR execution, production parser-model accuracy, or the broad
human-reviewed parser accuracy corpus are complete.

Current Rust runtime real-model handoff status: the runtime has a
safe-by-default smoke,
`scripts/smoke-doctruth-runtime-real-model-suite.sh`, that routes
`doctruth-runtime` `parse_pdf` through `DOCTRUTH_RUNTIME_MODEL_COMMAND`,
verifies model-assisted parser metadata, and can be pointed at a compatible
real worker with `DOCTRUTH_RUNTIME_REAL_MODEL_COMMAND`. This proves the Rust
runtime is the control point for model-assisted parsing. It does not by itself
prove production RT-DETR, TATR, SLANeXT, or OCR model accuracy; those still
require opt-in real artifact runs and labeled corpus reports.

Current Rust runtime SLANeXT/OCR status: `doctruth-runtime` can route
`table-server` to `doctruth-slanext-table-worker` and `ocr` to
`doctruth-rapidocr-mnn-worker`. The RapidOCR worker now supports both its
original image OCR protocol and a `parse_pdf` worker protocol that returns
`{ok:true, document}` for Rust runtime consumption. The local smokes
`scripts/smoke-doctruth-runtime-slanext-worker.sh` and
`scripts/smoke-doctruth-runtime-ocr-worker.sh` verify TrustDocument output,
`TABLE_CELL`/`OCR_REGION` units, runtime-normalized
`parserRun.backend=rust-sidecar+model-worker`, and packaged distribution.
These protocol and local-runtime gates use fake PaddleOCR/RapidOCR modules.
They are supplemented by
`scripts/smoke-doctruth-runtime-real-ocr-corpus.sh`, which runs a generated
scanned-PDF fixture through real RapidOCR + ONNXRuntime via
`doctruth-runtime parse_pdf` and has been recorded locally with
`DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE=1`. The analogous real SLANeXT Rust
route script, `scripts/smoke-doctruth-runtime-real-slanext-artifact.sh`, now
creates or reuses an isolated Python environment, installs `paddleocr` and
`paddlepaddle`, and has been recorded locally with
`DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE=1`. Neither generated OCR nor the
SLANeXT single-fixture hook replaces labeled accuracy reports.

Current parser-accuracy corpus status: JSON and readable
`benchmark-corpus` output expose `kind`, `qualityProfile`, `reviewType`,
`labelSetVersion`, `requiredMetrics`, `requiredTags`, `minCasesPerTag`,
`minTotalCases`, and per-case `labelId`/`tags`. This makes generated and
human-reviewed parser accuracy runs auditable in CI logs, but it does not
replace the missing broad public human-reviewed PDF corpus.

For LLM consumption, Markdown should support:

```text
stable heading hierarchy
reading-order-correct sections
GFM-compatible fenced code blocks
GFM-compatible table nodes
safe bracket and pipe escaping
tables rendered as Markdown tables when structure is reliable
HTML table fallback when rowspan/colspan cannot be represented safely
inline evidence anchors
page breaks
low-confidence warnings
source span ids
token-budget-friendly chunking
```

Markdown, HTML, plain text, and compact wire output must be rendered from the
same `TrustDocument` source, with cross-format parity checks for headings,
tables, lists, code blocks, anchors, and warnings.

When the source is HTML, DocTruth should preserve high-quality HTML-to-Markdown
conversion output directly where possible instead of converting through an
intermediate representation that destroys heading levels, tables, links,
bracket escaping, or code blocks. The canonical `TrustDocument` still records
the source map and parser provenance.

The parser must expose output profiles:

```text
markdown_llm
  compact Markdown for model context; anchors may be inline or sidecar

markdown_review
  human-readable Markdown with page markers and warnings

markdown_clean
  pure Markdown body optimized for LLM ingestion and downstream cleaning

markdown_anchored
  Markdown body with inline evidence anchors for citation-aware agents

plain_text
  clean text and tab-separated table rows for cleanup, keyword search, and
  simple LLM context; not audit-grade without JSON/source-map sidecars

json_full
  lossless parser output

json_evidence
  compact evidence spans for DocTruth/MemTruth ingestion

html_review
  visual review surface with bbox anchors and page-scoped overlay layers

compact_llm
  token-efficient evidence-preserving wire format for LLM/RAG pipelines;
  preserves optional bbox metadata for citeable units and supports writer-based
  output for file/export paths
```

Current SDK streaming writer status:

```text
TrustDocument.writeMarkdownClean(writer)
TrustDocument.writeMarkdownAnchored(writer)
TrustDocument.writeMarkdownReview(writer)
TrustDocument.writePlainText(writer)
TrustDocument.writeJsonLines(writer)
TrustDocument.writeCompactLlm(writer)
TrustDocument.writeJsonFull(writer)
TrustDocument.writeJsonEvidence(writer)
TrustDocument.writeAuditJson(writer)
TrustDocument.writeHtmlReview(writer)
TrustDocument.writeMarkdownSourceMap(writer)
TrustDocument.writeCompactLlmSourceMap(writer)
```

These writer APIs must be byte-identical to their string-returning counterparts
while avoiding one full-payload write into caller-owned writers. Parser
ingestion still materializes a `TrustDocument`. CLI `--out` file export now
routes all current TrustDocument output formats through writer paths, and
TrustDocument stdout output uses the same writer dispatch. Source-map sidecar
file serialization also uses a writer path, and SDK/CLI source-map sidecar
writers can write directly from `TrustDocument` without requiring callers to
materialize a `TrustRenderedDocument`. The compatibility
`toMarkdownWithSourceMap()` / `toCompactLlmWithSourceMap()` APIs still return
`TrustRenderedDocument`, and source-map JSON still includes full rendered text
by contract. Canonical and evidence hash inputs use writer-backed digest paths
instead of aggregate JSON strings. Benchmark size metrics use writer-backed byte
counters for full JSON and compact LLM output. `verify-source-map` hashes
rendered and source files with streaming file reads. CLI parse and SDK path
parse source hashing also use streaming file reads. SDK input-stream parsing now
copies input incrementally into a temporary file instead of calling
`InputStream.readAllBytes()`, then uses the same Rust-runtime path as file
parsing so source hashes and page-image metadata remain consistent. The
byte-array upload API still necessarily receives bytes already materialized by
the caller. Java/PDFBox remains available only when a caller explicitly selects
the legacy/oracle backend.

LLM-facing Markdown must be deterministic: the same parser version, preset,
model versions, and source hash should produce byte-stable output unless the
caller opts into non-deterministic post-processing.

### Cleanability Requirements

All rendered outputs must be easy to clean and post-process:

```text
no random IDs in visible body unless explicitly requested
stable whitespace normalization
stable heading levels
stable table formatting
no hidden proprietary markers in clean modes
no irreversible lossy rewrite unless warning is emitted
sidecar source maps instead of inline noise when requested
round-trip hash linking between clean output and canonical parse
cross-format parity for headings, tables, lists, links, code blocks, and anchors
streaming render support for large documents
```

DocTruth should expose cleaning-safe flags:

```bash
doctruth parse resume.pdf --format markdown --profile clean
doctruth parse resume.pdf --format markdown --profile anchored
doctruth parse resume.pdf --format markdown --profile clean --source-map
```

Clean mode is not audit-grade by itself. It is a consumption view. Audit-grade
status belongs to the canonical parse plus evidence map.

### MCP / Skill Runtime

The MCP tool should expose document evidence primitives, not just raw text:

```text
doctruth.parse_document
doctruth.get_layout_regions
doctruth.get_table_cells
doctruth.get_evidence_span
doctruth.verify_citation
```

When MemTruth uses DocTruth as a sidecar, it should receive evidence-native
objects:

```text
SourceDocument
EvidenceSpan
ClaimCandidate
TableCellEvidence
ReplayObject
```

## 8. Runtime Presets

### `lite`

Default local mode. No heavy model download.

```text
PDF text layer
heuristic line/block grouping
basic table heuristics
page/line/bbox when available
```

Acceptance:

```text
single-column PDFs parse correctly
simple resumes preserve section boundaries
no model cache required
```

### `standard`

Default quality mode for serious extraction.

```text
text-layer parser
layout detection when heuristics are uncertain
TATR table recognition
model cache verification
```

Acceptance:

```text
multi-column reading order improves over lite
table region and common cell structure are preserved
citations can point to table cells or layout regions
```

### `table-lite`

Smallest table model mode.

```text
SLANet-plus or equivalent small model
resource-constrained local environments
fast approximate table structure
```

Acceptance:

```text
small model cache
reasonable accuracy on simple bordered tables
clear confidence degradation on hard tables
```

### `table-server`

High-quality table mode.

```text
SLANeXT Wired/Wireless/Auto or equivalent licensed model set
GPU/CoreML/CUDA/TensorRT when available
cell-level table evidence
```

Acceptance:

```text
borderless and merged-cell tables improve materially over standard
model metadata is written into audit JSON
```

### `ocr`

Scanned PDF mode.

```text
page rasterization
OCR backend plugin
layout detection
text-region and bbox reconciliation
```

Acceptance:

```text
scanned pages produce source spans with confidence
low-confidence OCR never becomes silent audit-grade evidence
ParserPreset.OCR routes v1 TrustDocument parsing through the configured local OCR worker
doctruth parse/review-package --preset ocr produce OCR_REGION units with OCR provenance
OCR unit confidence is propagated from the local worker into TrustUnitEvidence
OCR confidence below 0.85 emits severe ocr_low_confidence and blocks audit-grade
```

Local OCR runtime strategy:

```text
Rust runtime owns the stable worker protocol, page rasterization, confidence
gate, and TrustDocument reconciliation. Java SDK/CLI wrappers may launch,
configure, and error-map the runtime, but must not own independent OCR evidence
logic.

RapidOCR/MNN is the preferred first local worker implementation candidate
because it can run locally without calling a hosted OCR API, but it must be
wrapped behind the DocTruth JSON stdin/stdout worker protocol and verified by
doctor/smoke before being treated as available.

The generic Java jar must not bundle OCR model binaries by default. Model files
belong in an explicit local cache or user-supplied worker install, with SHA-256,
engine name, model version, device, precision, timeout, and fallback recorded in
ParserRun/model metadata.
```

RapidOCR/MNN acceptance:

```text
doctruth doctor --json reports a real rapidocr-mnn worker as executable and ready
doctruth parse scanned.pdf --preset ocr works with that worker without Python import errors
worker stdout carries text, per-region bbox, page number, confidence, engine, and warnings
low-confidence worker output remains reviewable but not audit-grade
smoke covers both success and low-confidence paths with the real adapter contract
raw rapidocr CLI failures are surfaced as structured worker_unavailable or worker_protocol_error warnings
```

Current adapter status: `scripts/doctruth-rapidocr-mnn-worker` is a DocTruth-owned
JSON worker adapter around RapidOCR. It is packaged into source installs and CLI
release tarballs as `bin/doctruth-rapidocr-mnn-worker`, is discoverable by
`doctruth doctor --json` when on `PATH`, and exposes a `--doctor` self-test that
imports and initializes RapidOCR before reporting `ready=true`. Doctor JSON now
separates executable availability from runtime readiness. A fake RapidOCR Python
module plus Java CLI `parse --preset ocr` smoke proves the
adapter/protocol/package boundary and self-test contract. The adapter also
handles RapidOCR 3.8-style array-like `boxes` / `txts` / `scores` output without
NumPy truth-value failures. `scripts/smoke-doctruth-rapidocr-real.sh` is an
opt-in real runtime smoke: when `DOCTRUTH_RAPIDOCR_REAL_SMOKE=1` is set, it
creates or reuses an isolated venv, installs RapidOCR plus the ONNXRuntime
backend, checks worker `--doctor`, runs direct OCR, then verifies Java CLI
`parse --preset ocr` over a generated scanned PDF. Strict MNN backend readiness
is now separately smoke-tested by `scripts/smoke-doctruth-rapidocr-mnn-backend.sh`:
when `DOCTRUTH_RAPIDOCR_BACKEND=mnn` is set, worker `--doctor` must distinguish
RapidOCR availability from actual `MNN`/`mnn` module availability and report
`backend=mnn`, `backendReady`, and `backendVersion`.

## 9. Core Data Contracts

### Naming

`ParsedDocument` is an implementation-flavored name. The product contract should
use `TrustDocument`.

```text
TrustDocument
  canonical, evidence-carrying document representation

ContentBlock
  flat reading-order block for LLM/RAG ingestion and cleanup

ParseTrace
  parser intermediate evidence layer with page/block/line/span observations

TrustUnit
  smallest stable citeable unit inside a TrustDocument

ParsedDocument
  optional internal or backward-compatible Java implementation name
```

Do not treat `TrustDocument` as automatically trusted. It is a document object
that carries trust evidence, parser provenance, warnings, and audit-gate state.
Whether it is audit-grade is decided later by the evidence gate.

Avoid `TrustedDocument` for the core type because it overclaims. A document with
severe parser warnings is still a `TrustDocument`, but it is not audit-grade.

Use `TrustUnit` for the smallest citeable atom that can support downstream
evidence. A `TrustUnit` may be backed by a text block, line span, table cell,
figure caption, key-value region, or OCR region.

### TrustDocument

```text
doc_id
source_filename
source_hash
pages[]
sections[]
tables[]
metadata
parser_run
outputs[]
audit_grade_status
warnings[]
```

### ContentBlock

```text
block_id
document_id
type
page
bbox
reading_order
heading_level
text
body
source_unit_ids[]
evidence_span_ids[]
warnings[]
```

`ContentBlock` is not the audit source of truth. It is a clean, flat,
reading-order projection for LLM/RAG consumers. It must always be derivable from
`TrustDocument` plus `ParseTrace`, and every block that is citeable must point
back to source units or evidence spans.

### ParseTrace

```text
trace_id
document_id
parser_run_id
pages[]
warnings[]
```

`ParseTrace` is the audit/debug intermediate layer. It is allowed to be more
verbose and more parser-shaped than `TrustDocument`, but it must be deterministic
enough for tests, replay, and visual QA.

### TracePage

```text
page_index
page_number
page_size
preproc_blocks[]
reading_blocks[]
discarded_blocks[]
images[]
tables[]
equations[]
```

### TraceBlock

```text
block_id
type
bbox
reading_order
confidence
model_run_id
lines[]
source_unit_ids[]
evidence_span_ids[]
warnings[]
```

### TraceLine

```text
line_id
bbox
text
spans[]
```

### TraceSpan

```text
span_id
type
content
bbox
score
source_object_id
evidence_span_id
```

### RenderedOutput

```text
output_id
format
profile
content_hash
source_doc_id
parser_run_id
created_at
warnings[]
anchors[]
```

### OutputAnchor

```text
anchor_id
output_id
evidence_span_id
page
bbox
char_start
char_end
markdown_heading_path
```

### TrustUnit

```text
unit_id
document_id
unit_kind
page
bbox
reading_order
text
source_object_id
evidence_span_ids[]
confidence
warnings[]
```

### Page

```text
page_number
width
height
text_layer_available
image_hash
layout_regions[]
```

### LayoutRegion

```text
region_id
page_number
kind
bbox
confidence
reading_order
model_run_id
```

### TableRegion

```text
table_id
page_number
bbox
confidence
cells[]
html
markdown
model_run_id
```

### TableCell

```text
cell_id
table_id
row_start
row_end
col_start
col_end
bbox
text
confidence
source_text_spans[]
```

### EvidenceSpan

```text
span_id
source_id
page
line_start
line_end
char_start
char_end
bbox
quote
quote_hash
layout_region_id
table_cell_id
confidence
```

### ParserRun

```text
parser_version
preset
backend
models[]
ocr_backend
started_at
duration_ms
warnings[]
```

### ModelRun

```text
model_name
model_version
model_sha256
model_license
backend
device
precision
confidence_threshold
```

## 10. Contract Tests To Lock

These tests are inspired by Kreuzberg and Docling behavior, but they lock
DocTruth contracts only. They must not copy implementation code or private test
fixtures from either project.

### `TrustDocumentContractTest`

Locks the unified document model.

```text
PDF/DOCX/XLSX/CSV -> TrustDocument
```

Assertions:

```text
each source block has a stable id
each source block has page provenance when the format can provide it
each page-space bbox is normalized and valid
reading_order_index is stable and monotonic within page/region scope
headers, footers, and furniture are not silently merged into body text
parser_run records backend, preset, version, warnings, and duration
source_hash is stable for the same input bytes
```

Why this exists:

Docling's central lesson is that downstream exports should come from a unified
document representation. DocTruth's equivalent is `TrustDocument`.

### `RenderedOutputContractTest`

Locks the split between canonical truth and consumption views.

Assertions:

```text
json_full is lossless for TrustDocument fields
json_evidence preserves evidence spans, source ids, and parser/model metadata
compact_llm is deterministic and materially smaller than json_full
compact_llm preserves evidence ids, section hierarchy, table ids, bbox metadata, and warnings
compact_llm file output uses an incremental writer path
compact_llm source-map sidecars resolve compact text offsets back to units and evidence spans
compact_llm benchmark metrics report size reduction, round-trip health, and source-map coverage
markdown_clean has no inline bbox/provenance/internal ids
markdown_clean plus source map can resolve back to evidence spans
markdown_anchored includes stable evidence anchors
markdown_review includes page markers and warnings
html_review exposes bbox-compatible anchors
html_review exposes page surfaces with page dimensions and image hashes
html_review renders page-scoped visual bbox overlay nodes for units, tables, and cells
render-pages writes deterministic page PNG artifacts and a hash-bound manifest
review-package writes local static HTML review packages with page images and TrustDocument JSON
plain_text contains readable text/table content without Markdown/evidence syntax
source-map verification fails when rendered content or source hash changes
Audit JSON includes source, canonical document, and evidence hashes
Audit JSON can be signed or wrapped through the shared SDK SignatureProvider
Audit JSON can be replay-verified against full TrustDocument JSON
markdown output is GFM-compatible for tables, code fences, links, and escaping
Markdown/HTML/plain/compact outputs preserve cross-format section parity
clean markdown alone is never audit-grade
same source hash + parser config produces byte-stable output
```

Why this exists:

Docling treats JSON as lossless and Markdown/HTML as lossy exports. DocTruth
keeps that idea but adds evidence source maps and audit gates.

### `ReadingOrderContractTest`

Locks layout correctness where basic PDF text extraction usually fails.

Fixture classes:

```text
single_column_resume.pdf
two_column_resume.pdf
left_sidebar_resume.pdf
right_sidebar_resume.pdf
academic_two_column.pdf
header_footer_noise.pdf
rotated_page.pdf
```

Assertions:

```text
single-column body order is preserved
two-column documents do not interleave unrelated columns
sidebar metadata does not interrupt main-column work history
section headings attach to the correct following body
headers and footers are classified or warned, not repeated as body content
ambiguous multi-column pages emit reading_order_uncertain
```

Why this exists:

DocTruth's evidence chain is broken if a field cites text that was assembled in
the wrong reading order.

### `TableExtractionContractTest`

Locks table structure and cell-level evidence.

Fixture classes:

```text
bordered_table.pdf
borderless_table.pdf
merged_cell_table.pdf
resume_skill_matrix.pdf
invoice_line_items.pdf
```

Assertions:

```text
each TableRegion has page, bbox, confidence, and parser/model provenance
each TableCell has row/column indexes
merged cells preserve row_span and col_span
table markdown does not lose row/column meaning when structure is reliable
HTML fallback is used when Markdown cannot represent rowspan/colspan safely
json_full keeps table structure as data, not only as text
field citations can point to table cells
low-confidence table structure emits table_structure_low_confidence
```

Why this exists:

Kreuzberg's table contract is useful: table output should include cell-level
row/column indexing, merged-cell support, and Markdown or JSON output. DocTruth
adds the requirement that extracted fields cite the cell, not merely the page.

### `CitationContractTest`

Locks source grounding.

Assertions:

```text
each EvidenceSpan has source_document_id, page, quote, quote_hash, and confidence
quote can be re-matched against TrustDocument text
bbox is inside page bounds when present
visual claims require bbox or a severe warning
table-derived claims include table_cell_id
quote_anchor_failed prevents audit-grade status
```

Why this exists:

Docling provenance points back to page and layout. DocTruth must go further by
requiring quote rematch and evidence-grade citation semantics.

### `AuditGateContractTest`

Locks DocTruth's stricter product promise.

Severe warnings that block audit-grade:

```text
reading_order_uncertain
table_structure_low_confidence
quote_anchor_failed
bbox_missing_for_visual_claim
model_sha_mismatch
ocr_low_confidence
```

Assertions:

```text
strict extraction cannot become audit-grade with severe parser warnings
non-severe warnings remain visible in audit JSON
fallback from model-assisted mode to heuristic mode is recorded
strict mode fails instead of silently falling back
```

Why this exists:

Parser uncertainty must be visible. DocTruth should never convert uncertain
layout into fake certainty.

### `ModelRuntimeContractTest`

Locks local model behavior.

Assertions:

```text
lite preset does not download heavy models
offline mode never performs network access
model SHA mismatch fails or emits a severe blocking warning
standard preset records model name, version, SHA, backend, device, and precision
fallback_reason is recorded when model-assisted parsing is unavailable
doctor reports model cache state, backend availability, and memory estimate
doctor reports local OCR worker executable readiness, engine, fallback engine, timeout, and disabled state
configured model workers receive manifest-defined local model descriptors and READY cache status
```

Why this exists:

Kreuzberg's model manifest and model-cache behavior are strong product
precedents. DocTruth needs the same operational clarity with stricter audit
semantics.

### `ParserApiContractTest`

Locks developer-facing entrypoints.

Assertions:

```text
parse from file path
parse from bytes
parse batch
parse via streaming input
parse with preset
render markdown/json/html/audit outputs
render large documents without materializing every output format in memory
same document + same parser config -> stable TrustDocument hash
unsupported formats fail with stable error codes
sidecar crash maps to structured ParseException
```

Why this exists:

Kreuzberg's file/bytes, single/batch, sync/async matrix is a good API-shape
benchmark. DocTruth should keep its Java API idiomatic while covering the same
workflow surface.

### `HtmlPassthroughContractTest`

Locks HTML input and HTML-to-Markdown conversion behavior.

Assertions:

```text
HTML headings preserve hierarchy in TrustDocument and Markdown
HTML tables preserve row/column structure when representable
HTML links preserve href and label
fenced code blocks are not flattened into prose
brackets, pipes, and Markdown-sensitive characters are escaped safely
HTML-to-Markdown conversion avoids lossy intermediate round-trips
source map resolves Markdown ranges back to HTML source nodes where available
```

Why this exists:

HTML documents should not lose structure just because DocTruth normalizes them
through a document model. The renderer must preserve useful HTML semantics for
LLM/RAG consumption.

### `ChunkingContractTest`

Locks LLM/RAG consumption.

Assertions:

```text
chunks do not cross unrelated sections by default
chunk metadata includes heading path, page, source ids, and evidence span ids
table chunks preserve table identity
caption/figure chunks preserve nearby context
clean text chunks can resolve back through source map
oversized chunks split without losing evidence anchors
```

Why this exists:

Docling's chunking model preserves metadata for downstream AI workflows.
DocTruth needs the same retrieval usefulness while keeping replayable evidence.

## 11. Quality Gates

### Evidence Gate

An extraction is not audit-grade when:

```text
source span has no stable page anchor
quote cannot be re-matched
bbox is missing where visual evidence is required
table field lacks table cell or row/column context
OCR confidence is below threshold
parser emitted severe layout warnings
model SHA does not match the expected value
```

Parser audit packages must be tamper-evident at the SDK boundary:

```text
source_hash
canonical_hash
evidence_hash
signature_provider_applied
package_file_written_with_exact_signed_payload
replay_verifier_checks_full_trust_document_json
```

The local replay verifier must compare Audit JSON against full TrustDocument
JSON and fail on mismatched document id, source hash, canonical hash,
audit-grade status, parser run metadata, evidence hash, or evidence payload.
The CLI contract is:

```text
doctruth verify-audit <trust-document.json> <audit.json>
```

This SDK-level package signing does not by itself provide external
timestamping, key rotation, notarization, legal hold, or WORM storage. Those
remain separate enterprise/runtime milestones.

### Parser Warnings

Warnings must be structured and visible:

```text
reading_order_uncertain
multi_column_ambiguous
table_structure_low_confidence
layout_low_confidence
ocr_low_confidence
bbox_missing
header_footer_contamination
section_boundary_uncertain
model_unavailable_fallback
markdown_anchor_missing
markdown_table_lossy
```

No silent fallback from model-assisted parsing to heuristic parsing when the
caller requested strict evidence.

Current Rust runtime contract status:

```text
doctruth-runtime parse_pdf preset=table-lite
TrustDocumentParser.parse(path, ParserPreset.STANDARD)
TrustDocumentParser.parse(bytes, filename, ParserPreset.TABLE_LITE)
TrustDocumentParser.parse(inputStream, filename, preset)
TrustDocumentParser.parseBatch(paths, preset)
doctruth parse --preset table-lite --format json
```

These entrypoints preserve the parsed output for local inspection while adding
blocking `model_unavailable_fallback` warnings when the selected preset requires
models that are not available under the current local/offline policy. Each
missing required model must be represented by its own warning that includes the
model identity and expected SHA-256, so audit/replay tools can distinguish
missing layout, table, and OCR capabilities. The Rust runtime owns this
fallback/audit contract for its protocol and all Java wrapper paths. It can
route model-assisted presets to configured workers, including real RT-DETR/TATR
artifact smokes and SLANeXT/OCR worker-protocol smokes, but it still does not
execute ONNX, PaddleOCR/SLANeXT, RapidOCR, or MNN models in the Rust process
itself. Java/PDFBox compatibility code must not become a parallel model-worker
implementation.

Current Rust-first default status: the Rust runtime is no longer binary-only;
its protocol entrypoints are callable through the `doctruth-runtime` library
crate, while `src/main.rs` is a thin process wrapper. The product direction is:
Rust runtime is the parser core, `pdf_oxide` is the default Rust PDF backend
direction, and Java/PDFBox is wrapper/oracle/legacy compatibility only.
Path-first SDK parsing and CLI parsing must use the Rust runtime by default;
missing runtime is an installation error, not a normal Java fallback.
Java/PDFBox is available only when the caller explicitly selects
`ParserBackendMode.PDFBOX` or equivalent legacy/oracle mode.
The path-first SDK parser exposes explicit backend selection:
`DocTruth.withProvider(provider).parsePdf(path).withParser(preset).backend(AUTO)`
uses the configured Rust runtime, `.backend(PDFBOX)` is an explicit Java/PDFBox
oracle mode, and `.backend(SIDECAR)` requires a configured runtime. CLI parsing
follows the same rule: default Rust runtime, explicit `--backend pdfbox` only
for oracle/legacy comparison. Source install and release tarballs now ship
`bin/doctruth-runtime`, and the `bin/doctruth` launcher exports
`DOCTRUTH_RUNTIME_COMMAND` automatically when that same-directory runtime is
present.

Current implementation status: `doctruth-runtime` uses `pdf_oxide` for
text-layer page extraction, text span bbox evidence, DocTruth-owned column-order
post-processing, page MediaBox geometry, default rendered PNG page hashes,
content-stream safety checks, and line-table/table-debug extraction. It reports
`parserRun.pdfBackend.current = pdf_oxide` and `status = DEFAULT`. `lopdf` is
not a runtime dependency or a default parser-core component.

## 12. Evaluation Corpus

The parser benchmark must include:

```text
simple single-column PDFs
two-column resumes
left-sidebar resumes
right-sidebar resumes
academic multi-column PDFs
forms with key-value regions
bordered tables
borderless tables
merged-cell tables
scanned PDFs
mixed text-layer + image PDFs
documents with headers/footers
documents with rotated pages
```

Every fixture should have expected outputs for at least:

```text
reading order
section boundaries
table cells
field evidence anchors
bbox overlays
parser warnings
```

Corpus fixtures must be executable from a manifest, not only described in
documentation. The manifest contract is:

```text
corpus name
case name
source fixture path
or remote sourceUrl + sourceSha256
sourceSha256 for every human-reviewed parser-accuracy case
expected clean Markdown path
expected TrustDocument JSON path
minimum metric thresholds
maximum metric thresholds for lower-is-better metrics
paths resolved relative to the manifest file
remote fixtures cached beside the manifest after SHA-256 verification
missing fixtures fail with case-specific diagnostics
each labeled case must include an expected TrustDocument JSON label
```

The manifest runner should reuse the same benchmark metrics and threshold gate
as direct in-code benchmark cases. A generated fixture corpus is useful for
regression protection. `scripts/smoke-doctruth-real-pdf-corpus.sh` now adds a
small public W3C PDF fixture with a fixed SHA-256, a human-authored
`TrustDocument` label, `kind: "human-labeled"` metadata, and required metric
thresholds. This proves the remote-real-PDF human-labeled corpus path. Larger
human-labeled multi-layout/OCR/table corpora are still required before claiming
real-world parser accuracy, and those corpora should use
`qualityProfile: "parser-accuracy"` coverage tags so a single easy fixture
cannot satisfy the release gate.

The generated parser-accuracy seed corpus smoke exists to keep this release
gate executable in CI until those real-world labels are populated. It also
asserts that case-level `labelId` and `tags` survive into CLI JSON output.

Rust-first continuation status: `doctruth-runtime` now owns a native
`benchmark_corpus` protocol command in addition to `parse_pdf`. The command
loads manifest-relative source PDFs, expected clean Markdown, expected
TrustDocument JSON labels, parser-accuracy label metadata, case `labelId` and
`tags`, optional `sourceSha256` verification, required tag coverage, and metric
minimums. Native metrics now include `reading_order_f1`,
`quote_anchor_accuracy`, `bbox_coverage`, `bbox_iou`,
`evidence_span_accuracy`, `table_cell_f1`, and `ocr_text_accuracy`; the
expected-document metrics are computed against the checked-in
`TrustDocument` JSON label for each case. Each corpus case can now declare
`preset`, so model-assisted cases are measured through the same Rust
model-worker handoff as direct `parse_pdf`. Human-reviewed parser-accuracy
manifests require `labeling.minTotalCases` and per-case `sourceSha256`, and the
Rust command rejects missing pins or SHA mismatches before parser metrics are
accepted. Human-reviewed parser-accuracy manifests must also declare the core
parser-quality metric set in `requiredMetrics`, so a broad corpus cannot pass
while silently omitting bbox, table, OCR, or evidence-span quality gates. The
same manifests must declare the core coverage tags `multi-layout`, `table`,
`ocr`, `bbox`, and `source-map`, so required coverage cannot shrink to a single
easy layout bucket. The Rust protocol also accepts `report_path` and writes the
same `doctruth.parser-benchmark.report.v1` recorded report artifact shape used
by the Java CLI `--report-out` path, with manifest, label/review metadata,
manifest hash, threshold criteria, metrics, and per-case label/tag/source-hash
evidence. The Rust protocol also accepts `verify_benchmark_report` with
`report_path`, so runtime-produced recorded reports can be validated without
rerunning the parser and without going back through the Java CLI.
`scripts/smoke-doctruth-runtime-benchmark-corpus.sh` proves this path without
the Java CLI by running a `table-lite` case through a configured worker. This
migrates the corpus gate skeleton to Rust, but it is still a generated/local
gate; real-world parser accuracy still requires broad human-reviewed fixtures
and labeled real model/OCR quality evidence.

Rust model-runtime migration status: `doctruth-runtime parse_pdf` now checks
`DOCTRUTH_RUNTIME_MODEL_COMMAND` or `DOCTRUTH_MODEL_COMMAND` for model-assisted
presets such as `table-lite`. When configured, Rust sends a JSON stdin request
containing source path, source hash, preset, offline/download policy, and
required model descriptors, then returns the worker's `TrustDocument` JSON.
Invalid worker output fails with stable `MODEL_WORKER_FAILED` diagnostics.
`scripts/smoke-doctruth-runtime-model-worker.sh` proves this path without the
Java CLI. This moves the model-worker handoff into the Rust runtime. RT-DETR/
TATR now have an opt-in Rust-runtime real-artifact entrypoint, and SLANeXT/OCR
have Rust-runtime worker-protocol smokes plus generated real-route Rust runtime
smokes. ADR 0011 accepts this worker boundary for v1: the runtime owns
orchestration, manifests, request envelopes, validation, normalization, and
benchmark execution, while ONNXRuntime, PaddleOCR/SLANeXT, RapidOCR, and MNN
may execute in isolated local workers.

The benchmark metrics include both parser-quality gates and LLM/replay output
gates. `compact_llm_size_reduction` is computed as the UTF-8 byte reduction
relative to `json_full`; `compact_llm_round_trip` must be `1.0` when the
source-map-rendered compact text exactly matches `toCompactLlm()`; and
`compact_llm_source_map_coverage` measures citeable units that can be resolved
from compact source-map entries. `strict_warning_false_negative_rate` compares
expected severe parser or unit-local warning codes from the labeled
`TrustDocument` against actual severe warning codes and is enforced through the
manifest's `maximums` gate. `section_boundary_f1` is enforced through normal
manifest `minimums` and treats merged/missing heading boundaries as recall or
precision loss. `evidence_span_accuracy` is also enforced through `minimums`
and measures expected text-line coverage by actual evidence-bearing units. Each
parsed case also records `parser_latency_ms`, `rss_peak_mb`, and
`model_cache_size_mb`; corpus output reports aggregate `parser_latency_p50` and
`parser_latency_p95` plus `compact_llm_size_reduction_min`; latency gates such
as `parser_latency_p95` are enforced through `maximums` at the corpus aggregate
level in both Java and Rust benchmark runners, and compact-corpus gates such as
`compact_llm_size_reduction_min` are
enforced through aggregate `minimums`. Resource metrics are per-case benchmark
observations unless a worker/runtime reports stronger process-level peak memory.

The CLI must expose this gate directly:

```text
doctruth benchmark-corpus <manifest.json>
doctruth benchmark-corpus <manifest.json> --json
doctruth benchmark-corpus <manifest.json> --json --report-out parser-report.json
doctruth benchmark-corpus <manifest.json> --offline
doctruth verify-benchmark-report parser-report.json
```

The command must be covered by a smoke script that creates generated PDF and OCR
fixtures, writes expected Markdown and `TrustDocument` labels, verifies a
passing corpus, verifies that generic threshold failures and OCR wrong-label
failures exit non-zero with diagnosable metric names, and verifies that offline
mode refuses uncached remote fixtures before any network request. Parser-accuracy
runs should write a recorded report artifact with
`reportFormat: doctruth.parser-benchmark.report.v1`, the resolved manifest path,
`manifestSha256`, label/review metadata, copied `minimums`/`maximums`, actual
`caseCount` and `casesPerTag` coverage, copied `coverageRequired`, computed
`coverageSatisfied`, fixture-type coverage, OpenDataLoader-inspired behavior
coverage, replay `validityInputs`, metrics, and per-case
label/tag/fixture/behavior/source-hash/replay evidence. Manifests may also
declare `externalEvaluations.opendataloader` pointing at an OpenDataLoader-style
`evaluation.json`; reports then copy the evaluation reference under
`externalEvaluations`, persist its SHA-256 and imported values under
`externalMetrics.opendataloader`, and flatten NID, TEDS, MHS, and speed into
`metrics.opendataloader_nid`, `metrics.opendataloader_teds`,
`metrics.opendataloader_mhs`, and `metrics.opendataloader_speed` for normal
threshold gates. This is an imported parser-quality signal only: OpenDataLoader
schemas are not canonical, and TrustDocument remains the evidence/replay
contract. The adapter can also export OpenDataLoader Bench-style prediction
artifacts to an explicit output directory: `markdown/<document_id>.md` files
and `summary.json`, with `externalArtifacts.opendataloaderPrediction` recording
the artifact path, engine, and document count. These artifacts are for external
evaluator compatibility only; they do not replace TrustDocument or parser trace
evidence. Fixture taxonomy is
declared with `requiredFixtureTypes`, `minCasesPerFixtureType`, case
`fixtureTypes`, `casesPerFixtureType`, `fixtureCoverageRequired`, and
`fixtureCoverageSatisfied`; recorded reports also include `fixtureResults`,
which lists each fixture/layout bucket's case count, cases, aggregate metrics,
and pass/fail status against copied thresholds. It covers simple single-column,
two-column, sidebar-resume, table, borderless-table, scanned-OCR, invoice, and
mixed-layout fixtures. Behavior taxonomy is declared with `requiredBehaviors`,
`minCasesPerBehavior`, case `behaviors`, `casesPerBehavior`,
`behaviorCoverageRequired`, and `behaviorCoverageSatisfied`; it covers
OpenDataLoader-inspired XY-Cut edge cases, parser safety filters,
structure-tree preference, and table border/cluster heuristics. `validityInputs`
must state whether the recorded report can be replayed from source hashes,
manifest hash, parser configuration, model/cache manifest state, thresholds,
expected labels, and the actual `TrustDocument` output. Each case must include a
`replay` object for `sourceRefReplayable`, `quoteReplayable`, and
`evidenceSpanReplayable`, plus the actual `TrustDocument` output and
`actualTrustDocumentSha256` so the recorded report can prove its parser-quality
and replay claims are bound to the real parsed document, not only copied
metrics.

Current OpenDataLoader Bench runner status: `scripts/run-doctruth-opendataloader-bench.sh`
builds `doctruth-runtime`, runs Rust `opendataloader_prediction` over the
vendored `third_party/opendataloader-bench/pdfs/` corpus, writes
`prediction/doctruth-runtime/markdown/*.md`, `summary.json`, `errors.json`, and
`prediction-report.json`, and then runs Rust `opendataloader_evaluate_prediction`
by default to produce `evaluation.json`. The official upstream OpenDataLoader
Python evaluator remains available only through explicit `--evaluator official`
or oracle/baseline scripts; it is not the default DocTruth prediction/evaluation
path. `scripts/smoke-doctruth-opendataloader-evaluator-parity.sh` provides a
skip-safe fixture-level parity smoke between the Rust evaluator and the official
upstream evaluator for exact text, heading-level normalization, and table
wrapper/header normalization. This is not yet a full-corpus proof that the Rust
evaluator can replace the official oracle for all APTED/lxml/rapidfuzz edge
cases. Legacy Python/OpenDataLoader hybrid baseline scripts are fail-closed and
require `DOCTRUTH_ALLOW_PYTHON_ORACLE=1` before launching the heavy oracle path.
The legacy Python prediction adapter also refuses direct command-line execution
without the same opt-in; importing it from legacy smoke tests remains a test
helper boundary. Even `--evaluator official` is fail-closed behind the opt-in so
the Python/APTED/lxml/rapidfuzz upstream evaluator cannot be launched by
accident. The default Rust runner and MNN promotion runner must not call the
Python prediction adapter. The first full local baseline on 200 vendored PDFs parsed 199
documents and failed one scanned/no-text-layer document. It reported
`overall_mean=0.509092484964239`, `nid_mean=0.7591850124827885`,
`teds_mean=0.0`, and `mhs_mean=0.0025571766718785185`, with
`total_elapsed=389.71747279167175` seconds and one extreme slow sample
`01030000000141` at about 180 seconds.

The first export-layer optimization adds conservative Markdown heading
promotion, TrustDocument table-to-HTML rendering, and a narrow line-span table
fallback for `No.`/number/name/value table patterns. The next full local run
still parsed 199 of 200 documents, but improved the OpenDataLoader aggregate to
`overall_mean=0.5492221210080162`, `nid_mean=0.7665022379711967`,
`teds_mean=0.06498004117639267`, and `mhs_mean=0.12239636974611434`.
This is an honest baseline, not a pass gate: reading order has a usable
text-layer foundation, while table fidelity, heading hierarchy, OCR fallback,
and slow-sample timeout/parallelism remain required parser-quality work before
DocTruth can claim OpenDataLoader/Docling level extraction quality.

The Rust-owned runner supports `--timeout-seconds` without returning to the
Python prediction adapter. When this option is present, `opendataloader_prediction`
spawns the current `doctruth-runtime` binary per document, sends a normal
`parse_pdf` request over stdin, kills the child on timeout, writes an empty
Markdown artifact, and records `errorCode=PARSE_TIMEOUT` in `summary.json` and
`errors.json`. Without this option, prediction stays on the faster in-process
Rust path. Historical context: the legacy Python adapter used the same kind of
per-document isolation to keep full-corpus iteration from being dominated by a
single pathological PDF; a 30-second run completed in `239.5388069152832`
seconds, marked `01030000000141` as timed out, kept the scanned/no-text-layer
failure `01030000000165`, and retained nearly identical aggregate quality:
`overall_mean=0.549140667373931`, `nid_mean=0.7663393307030263`,
`teds_mean=0.06498004117639267`, and `mhs_mean=0.12239636974611434`.

Current structure-tree preference status: the Rust runtime now asks `pdf_oxide`
for canonical page reading order, which prefers a trustworthy Tagged-PDF
`/StructTreeRoot` before geometric inference. `parserRun.readingOrder` and
`parseTrace.readingOrder` record whether the chosen source is `structure-tree`
or fallback `xy-cut`. When a tagged PDF sets `/MarkInfo /Suspects true`, the
runtime falls back to XY-Cut and emits a non-severe
`structure_tree_suspect_fallback` warning. This proves the reading-order
preference and replay trace boundary; richer role/heading/list/table semantic
export from tags remains a later parser-quality expansion.

Current table-migration status: borderless/text-spatial table extraction uses
`pdf_oxide` `detect_tables_from_spans` and normalizes the result through
DocTruth `TrustDocument` table cells. Bordered-grid, merged-cell, row-span, and
adjacent-page continuation extraction now use `pdf_oxide` content-stream
primitives. `lopdf` is no longer a `doctruth-runtime` dependency or default
parser-core component. This completes the Rust MVP table migration while broad
real-world table accuracy and model-assisted calibration remain parser-quality
follow-ups.

Current parser-safety status: the Rust runtime has OpenDataLoader-style
content-safety filters for duplicate positioned text, whitespace-only spans,
off-page spans, tiny spans, near-white/background-like spans, and invisible
render-mode text. These filters emit severe warnings such as
`duplicate_text_filtered`, `whitespace_text_filtered`, `off_page_text_filtered`,
`tiny_text_filtered`, `background_text_filtered`, and `hidden_text_filtered`,
then mark the parse `NOT_AUDIT_GRADE`. Robust rendered-page background
comparison remains a later parser-quality expansion, not a default parser-core
blocker.
The CLI must also verify a recorded report without rerunning the parser, so CI
can prove that an archived parser-quality report still matches its manifest,
thresholds, coverage counts, copied coverage requirements, metric values, and
source pins. Recorded reports must also prove that aggregate metrics are
consistent with the per-case metrics they summarize, that coverage satisfaction
matches actual case tags, fixture types, and behavior tags, that replay validity
inputs remain present, that imported OpenDataLoader metrics still match the
referenced `evaluation.json` and its hash, and that case replay fields match the
metrics/source hashes they summarize. They must also recompute each case's
`actualTrustDocumentSha256` from the embedded `actualTrustDocument` and replay
case-level parser-quality metrics against the manifest's expected Markdown and
expected `TrustDocument` labels, so a report cannot be altered by changing only
the aggregate, only external metrics, only coverage fields, only case-level
replay evidence, only the parser output hash, or only the embedded parser
output.
Cached remote
fixtures remain usable offline after SHA-256 verification.
`scripts/smoke-doctruth-real-ocr-corpus.sh` is an opt-in runtime corpus smoke:
when `DOCTRUTH_REAL_OCR_CORPUS_SMOKE=1` is set, it installs or reuses an
isolated RapidOCR + ONNXRuntime environment, verifies the RapidOCR worker
doctor, generates a scanned-PDF fixture, and gates `ocr_text_accuracy` through
`benchmark-corpus`. This proves the real OCR runtime can feed the corpus gate on
a generated scanned fixture, not broad real-world OCR accuracy.

## 13. Architecture

### Phase Architecture

```text
Java API
  |
  | existing Java ParsedDocument / Citation compatibility
  | new TrustDocument contract
  v
Rust Runtime Adapter
  |
  +-- Rust core native binding
  |
  +-- Rust sidecar process
  |
  +-- Java PDFBox compatibility/oracle mode
        only when explicitly selected for migration and differential tests

Rust core
  |
  +-- text layer parser
  +-- page rasterizer
  +-- layout detector
  +-- table recognizer
  +-- OCR backend
  +-- model/cache verifier
  +-- benchmark corpus runner
  +-- evidence reconciler
  +-- TrustDocument emitter
```

The dependency direction must stay one-way: Java calls Rust; Rust does not
depend on Java parser internals.

### Why Sidecar First

Sidecar is the safest first bridge:

```text
no JNI packaging complexity at the beginning
crash isolation
easier model cache management
same runtime usable by CLI and MCP
Java SDK can keep stable contracts
```

Native binding can come after contracts stabilize.

## 14. TDD Execution Mode

This PRD should be implemented with milestone-sized batch TDD, not with one
micro-feature per loop and not with the entire PRD as one giant failing test
set.

For each milestone:

```text
1. derive the concrete contract from this PRD
2. write all RED tests for that milestone first
3. run the focused test set and confirm failures are caused by missing behavior
4. implement the milestone in one coherent development pass
5. rerun focused tests
6. rerun required smoke tests
7. update PRD/planning status with what is proven and what remains unproven
```

Milestone scope should be large enough to avoid thrashing, but small enough
that failures remain diagnosable. Good milestone boundaries are:

```text
signed audit package and replay package integrity
labeled parser benchmark corpus harness
model runtime interface and cache/fallback contracts
layout-region detection contract
table-region and cell-recognition contract
OCR routing and low-confidence warning contract
HTML review overlay/source-map contract
streaming parse/render contract
```

Do not batch unrelated hard problems into one milestone. For example,
model-assisted layout detection, OCR, external notarization, and WORM/legal
hold are separate milestones even though they all support audit readiness.

Completion requires current evidence, not intent:

```text
focused unit tests for the milestone
public API snapshot update when public surface changes
CLI or runtime smoke when user-facing behavior changes
full Maven test suite when Java contracts change
Cargo tests and runtime smoke when Rust runtime changes
git diff --check
```

If a milestone only writes partial scaffolding, mark it as scaffolding. Do not
claim parser quality, replay completeness, or audit-grade readiness unless the
tests and smoke prove that specific claim.

## 15. Implementation Phases

### Phase 0: Contract Freeze

Deliverables:

```text
TrustDocument v1 draft
ContentBlock projection contract
ParseTrace intermediate evidence contract
LayoutRegion contract
TableRegion/TableCell contract
EvidenceSpan contract
ParserRun/ModelRun metadata
strict parser warning taxonomy
```

Exit criteria:

```text
old Java API remains source-compatible
new contracts can represent current parser output
audit JSON can include parser/model metadata
clean Markdown/content blocks are derived from the canonical parse
parse trace can represent page/block/line/span observations
```

### Phase 0A: Layered Parser Output Contract

Deliverables:

```text
markdown_clean profile
content_blocks.json profile
parse_trace.json profile
trust.json profile
content block source-unit/evidence-span links
parse trace page/block/line/span ids
discarded block trace contract
layout/span debug artifact contract
```

Exit criteria:

```text
content_blocks.json preserves reading order without inline evidence noise
parse_trace.json preserves page/block/line/span/bbox/source refs
clean Markdown can be regenerated from content blocks
TrustDocument evidence spans can be traced back to parse trace spans
visual debug artifacts can be generated from the same trace ids
```

### Phase 1: Java Baseline Hardening

Deliverables:

```text
multi-column section regression suite
sidebar/main-column fixtures
table fixture suite
header/footer contamination tests
parser warnings
evidence gate integration
```

Exit criteria:

```text
current PDFBox path fails visibly instead of silently
known cross-column bugs are covered by tests
all current unit tests pass
```

### Phase 2: Rust Sidecar MVP

Deliverables:

```text
doctruth-runtime binary
JSON stdin/stdout protocol
streaming parse protocol
parse_pdf command
benchmark_corpus command
configured model-worker handoff
doctor command
model cache directory
SHA256 verification
Java sidecar adapter
CLI adapter
```

Exit criteria:

```text
Java SDK can call sidecar parser
CLI can use the same runtime
sidecar crash returns structured ParseException
model cache can be verified offline
```

### Phase 3: Layout Detection

Deliverables:

```text
ONNX runtime integration
RT-DETR-compatible layout model adapter
layout region output
reading-order reconciliation
confidence thresholds
CoreML/CUDA provider detection where available
```

Exit criteria:

```text
layout regions are visible in TrustDocument JSON
multi-column reading order improves on benchmark corpus
low-confidence layout emits warnings
```

Current status: ONNXRuntime loading, RT-DETR/DETR-like layout output decoding,
confidence warnings, and resource metrics are covered by synthetic ONNX smokes.
`scripts/smoke-doctruth-real-rtdetr-artifact.sh` now validates a public
document-layout RT-DETR artifact from `Kreuzberg/layout-models` through the
same cache/model-worker/parse path. It proves rendered-page input,
`orig_target_sizes`, `labels`/`boxes`/`scores` decoding, and Java CLI
integration. The repository still does not bundle RT-DETR weights by default
or claim broad document-layout accuracy without labeled corpus results.

### Phase 4: Table Recognition

Deliverables:

```text
TATR-compatible table model adapter
small table model preset
server table model preset
table cell reconstruction
cell-level EvidenceSpan
HTML/Markdown/JSON table output
```

Exit criteria:

```text
table fields cite cells, not only page-level blocks
merged cells preserve row/col span
borderless table fixtures improve over heuristic baseline
```

Current status: Java/PDFBox now recovers generated bordered-grid tables, a
conservative class of borderless aligned text matrices, generated bordered
tables with horizontal merged cells, and generated bordered tables with vertical
row spans into `TrustTable`, `TrustTableCell`, and `TABLE_CELL` units with
normalized bboxes. Generated merged cells preserve `rowRange`/`columnRange` span
data and are gated by `table_cell_f1` in generated PDF benchmark fixtures.
Java/PDFBox also now merges adjacent generated bordered-table continuation
pages with repeated headers, dedupes the continuation header, and keeps
continued cell units on their original source page. The Rust
`doctruth-runtime` now has parity for generated bordered-grid tables, short
aligned borderless text matrices, generated horizontal merged cells, and
generated vertical row spans through content-stream text points. It now also
merges adjacent generated bordered-table continuation pages with repeated
headers, dedupes the continuation header, and keeps continued `TABLE_CELL`
units on their original source page. Explicit Cargo contract tests, runtime
smoke, and Java CLI sidecar smoke cover these JSON paths. A separate Java CLI
sidecar borderless smoke also covers JSON, Markdown, and plain-text rendering.
`TABLE_LITE` also has a configurable model-worker path that can return
model-produced tables through the same `TrustDocument` contract and CLI JSON
smoke. Its request now supports manifest-defined local model descriptors and
SHA-verified READY cache artifacts. The opt-in real model artifact smoke can be
run with `DOCTRUTH_REAL_MODEL_EXPECTED_TASK=table-structure-recognition` to
validate user-supplied TATR/SLANeXT-compatible ONNX artifacts through the same
cache/model-worker/parse path. `scripts/smoke-doctruth-real-tatr-artifact.sh`
now validates the public Xenova Table Transformer quantized ONNX artifact
through that same cache/model-worker/parse path and also verifies the direct
worker uses a rendered PDF page as model input. The smoke now also exercises
the real TATR row/column label set and requires multi-row/multi-column
intersected cell output. This is still mostly heuristic/generated-fixture table
support until labeled real-world table accuracy and additional production table
models are checked in or supplied by CI. `doctruth-slanext-table-worker`
provides the PaddleOCR/SLANeXT adapter boundary for the `table-server` path, and
`scripts/smoke-doctruth-slanext-table-worker.sh` covers that protocol with a
fake PaddleOCR module. The real SLANeXT smoke remains opt-in because the generic
DocTruth package must not bundle PaddleOCR/Paddle/model binaries by default.
It has been verified with PaddleOCR 3.7.0/PaddlePaddle 3.3.1 in an isolated
Python 3.10 environment. `doctruth-runtime` can now route `table-server`
through the SLANeXT worker protocol, and the generated real PaddleOCR/SLANeXT
smoke has now been recorded through that Rust-runtime route.
The packaged `smoke-doctruth-real-model-suite.sh` combines RT-DETR, TATR, and
SLANeXT runtime smokes so release jobs can run the same model gate instead of
calling each script manually.

### Phase 5: OCR Routing

Deliverables:

```text
text-layer quality detector
OCR backend interface
page image rasterization
OCR text + bbox reconciliation
OCR confidence gate
```

Current status: the Rust runtime owns page image hashes through `pdf_oxide`
rendering by default, while Java page-image helpers remain package/review
compatibility utilities. `doctruth review-package` bundles review HTML,
TrustDocument JSON, and page image artifacts into a single local directory.
`ParserPreset.OCR` now routes v1 `TrustDocumentParser` and CLI TrustDocument
outputs through the Rust runtime and configured local OCR/model-worker
protocol (`DOCTRUTH_RUNTIME_MODEL_COMMAND`, `DOCTRUTH_OCR_COMMAND` /
`doctruth.ocr.command`, default engine `mnn`) and marks recovered units as
`OCR_REGION` with `rust-sidecar+model-worker` parser provenance when the Rust
runtime route is used. OCR page confidence is propagated into
`TrustUnitEvidence`; confidence below `0.85` emits a severe
`ocr_low_confidence` warning on the unit and makes the
document `NOT_AUDIT_GRADE` while still preserving the recovered text for
review/replay.
The generic jar still does not bundle RapidOCR/MNN models, and the raw local
`rapidocr` CLI is not treated as verified unless wrapped behind the worker
protocol. `doctruth-rapidocr-mnn-worker` now provides that wrapper and is
packaged with the CLI. It also provides `--doctor` readiness JSON so
`doctruth doctor --json` can distinguish an executable worker from a RapidOCR
runtime that can actually import and initialize. On the current development
machine the default global Python/RapidOCR environment still reports
`rapidocr_unavailable` because its NumPy install is incompatible, but an
isolated RapidOCR + ONNXRuntime backend smoke now passes and proves direct
worker OCR plus Java CLI scanned-PDF OCR. An opt-in real OCR corpus smoke now
uses the same RapidOCR worker behind `benchmark-corpus` and gates
`ocr_text_accuracy` on a generated scanned-PDF label. Strict MNN doctor mode now
requires a real importable `MNN`/`mnn` backend module before reporting backend
readiness; the CLI release smoke also verifies this field contract with a fake
backend module. Rust runtime page image hash parity is now covered by
`pdf_oxide` rendered PNG runtime tests, and `doctruth-runtime` can route `ocr`
through the RapidOCR worker protocol.
Persisted Rust page image artifact output, real MNN OCR recognition quality,
and labeled real-world OCR accuracy remain separate work.

Exit criteria:

```text
scanned PDFs produce evidence spans
low-confidence OCR cannot become audit-grade silently
OCR output is replayable through ParserRun metadata
```

### Phase 6: MCP/Skill Distribution

Deliverables:

```text
doctruth MCP server
skill package
runtime bootstrap
doctor checks
document evidence tools
model cache warmup
compact_llm wire output
GFM-quality Markdown renderer
HTML passthrough renderer path
```

Exit criteria:

```text
an agent can parse a document through MCP
the response includes evidence spans and bbox references
MemTruth can store DocTruth evidence as replayable source objects
LLM-facing output is compact, deterministic, and source-map resolvable
```

Current status: `doctruth mcp` now provides a local stdio MCP gateway with
`initialize`, `tools/list`, and `tools/call` support for
`doctruth.parse_document`, `doctruth.get_layout_regions`,
`doctruth.get_table_cells`, `doctruth.get_evidence_span`, and
`doctruth.verify_citation`, plus `doctruth.warm_model_cache` for local model
cache preflight. The document tools parse a local document through the v1
`TrustDocumentParser` contract and return MCP `structuredContent` containing
compact LLM text, JSON evidence units, bbox-bearing layout regions, table cell
bboxes, citation verification, audit status, source hash, and source-map
entries. The model cache tool verifies caller-supplied local model descriptors
against a cache directory and reports READY/MISSING/SHA_MISMATCH without
implicit downloads. A packaged smoke verifies the shaded CLI can parse
generated PDFs through MCP and return evidence spans, bbox references, table
cells, citation verification, and model cache readiness. A local skill package
now lives under
`skills/doctruth/` with a concise `SKILL.md`, agent metadata, and a bootstrap
script that writes a stdio MCP config pointing to `doctruth mcp`; a smoke test
verifies the package and config writer. This is still a local single-user
stdio gateway; remote/distributed MCP deployment remains outside this slice.

The standalone CLI also now supports `doctruth cache warm <manifest.json>
--preset <preset> [--cache <dir>] [--offline] [--json]`. It installs
manifest-defined local, `file://`, or HTTP(S) model artifacts into the
deterministic cache filename, then verifies SHA-256 with the shared cache
verifier. Remote downloads stream through JDK `HttpClient` into a temp file
before entering the cache, and `--offline` refuses remote model sources before
any network request. This establishes the install/preflight contract for future
real ONNX/TATR/SLANeXT model artifacts. Manifest runtime hints are preserved
through cache, doctor, and worker JSON so a later real model worker can
distinguish layout detection, table structure, backend, format, precision, and
license requirements. Curated real model URLs and production execution are
still not implemented for RT-DETR/TATR/SLANeXT, but ONNXRuntime smokes now
prove the local ONNX execution boundary plus synthetic RT-DETR/DETR-like
layout and TATR/DETR-like table decoder contracts over `pred_logits`/
`pred_boxes`.

## 16. Acceptance Metrics

Minimum parser benchmark gates for a beta runtime:

```text
single-column reading_order_f1 >= 0.98
two-column reading_order_f1 >= 0.92
section_boundary_f1 >= 0.90
table_region_iou >= 0.85
table_cell_f1 >= 0.80 for standard
quote_anchor_accuracy >= 0.97
bbox_iou >= 0.80 for cited visual spans
strict parser warning false-negative rate <= 2%
```

Runtime gates:

```text
lite p95 parse latency <= 1.5s for 3-page text-layer PDF
standard p95 parse latency <= 8s CPU for 3-page PDF
large-document streaming path avoids loading all pages and all rendered outputs into memory at once
compact_llm output is at least 25% smaller than json_full on the benchmark corpus
GFM renderer preserves fenced code blocks, tables, links, and bracket escaping
HTML passthrough avoids lossy intermediate conversion for HTML sources
model cache verifies SHA256 before use
offline mode never attempts network download
local OCR worker readiness is reported by doctor
sidecar RSS and peak model memory are reported by doctor
ONNX worker parse response reports wall time, inference time, RSS, and peak memory
```

Current status: the Rust sidecar `--doctor` response now reports `rssMb` and
`peakMemoryMb` from local process memory without adding runtime dependencies.
The Rust protocol contract and runtime smoke assert these fields. With no model
loaded, `peakMemoryMb` represents process high-water or RSS fallback rather than
production model peak memory.

## 17. Open Questions

```text
Which Apache/MIT-compatible model artifacts can be redistributed or referenced?
Should DocTruth ship model download manifests or only model adapters?
Should table-server presets live in OSS, or only as optional user-supplied models?
Should embedded native/JNI runtime replace the sidecar as the default once the Rust library core is mature?
What is the minimum fixture corpus size before claiming parser-runtime alpha?
Should compact_llm use an existing TOON-compatible syntax or a DocTruth-owned compact evidence format?
Which Rust Markdown renderer should be the default for GFM parity?
Should DocTruth keep `pdf_oxide` as the default OSS Rust PDF backend, or support a secondary PDFium-compatible backend only for specific enterprise/runtime environments?
```

## 18. Product Boundary

DocTruth parser runtime owns:

```text
document parsing
layout detection
OCR routing
table structure recognition
source grounding
evidence spans
parser/model provenance
audit-grade gating
```

DocTruth parser runtime does not own:

```text
agent memory
long-term replay ledger
general RAG retrieval
hosted team review workflow
business-domain extraction templates
```

MemTruth consumes DocTruth evidence. It should not re-parse documents when
DocTruth can provide source-grounded evidence spans.
