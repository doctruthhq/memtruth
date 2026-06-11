# DocTruth PDF Parser Runtime PRD

Status: draft
Owner: doctruthhq maintainers
Scope: DocTruth parser/runtime layer
Last updated: 2026-06-12

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
projects such as Kreuzberg, while keeping DocTruth's own clean-room
implementation, Apache-compatible licensing, and evidence/audit semantics.

## 2. Problem

Current DocTruth PDF parsing has a solid Java/PDFBox baseline, but real-world
documents expose failure modes that directly damage evidence quality:

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
Kreuzberg-level parser/runtime quality
+
DocTruth-level citation, provenance, confidence, and audit semantics
```

DocTruth should not compete on "number of supported file formats" first. It
should compete on correctness of source grounding:

```text
field -> quote -> page -> line -> bbox -> table cell -> parser/model metadata
```

## 4. Benchmark Reference

Kreuzberg is a useful engineering benchmark because it combines Rust core,
language bindings, CLI/API/MCP deployment, ONNX-based layout detection, table
structure recognition, model caching, and feature-gated heavy capabilities.

Important Kreuzberg reference points:

- Layout detection uses RT-DETR v2 over rendered page images and detects 17
  document layout classes such as text, table, title, form, list item,
  key-value region, headers, footers, captions, and figures.
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

### G3. Rust Core Without Breaking Java SDK

The Java API remains the enterprise integration surface. A future Rust core
should sit behind the existing Java contracts.

```text
DocTruth Java SDK
  -> JNI/native library OR sidecar process
  -> Rust parser runtime
  -> evidence-native TrustDocument
```

The public Java API must not force users to understand Rust.

### G4. Local-First Runtime

DocTruth must work locally without network calls by default. Heavy models are
downloaded only when explicitly enabled or when a preset requires them.

```text
default install: no large model download
first layout run: download verified model
doctor: verify cache, SHA256, backend availability
offline mode: use existing cache only
```

### G5. Measurable Parser Quality

Parser quality must be evaluated with fixtures and metrics, not screenshots
alone.

Required metrics:

```text
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
```

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
```

DocTruth may support multiple formats, but PDF evidence correctness is the
priority because PDF is where citation grounding most often fails.

## 7. User Experience

### Java SDK

```java
var doc = DocTruth.withProvider(provider)
    .fromPdf("resume.pdf")
    .withParser(ParserPreset.STANDARD)
    .parse();

var result = doc.extractJson(schema)
    .withEvidence()
    .runJson();
```

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

json_full
  lossless parser output

json_evidence
  compact evidence spans for DocTruth/MemTruth ingestion

html_review
  visual review surface with bbox anchors

compact_llm
  token-efficient evidence-preserving wire format for LLM/RAG pipelines
```

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
```

## 9. Core Data Contracts

### Naming

`ParsedDocument` is an implementation-flavored name. The product contract should
use `TrustDocument`.

```text
TrustDocument
  canonical, evidence-carrying document representation

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
compact_llm preserves evidence ids, section hierarchy, table ids, and warnings
markdown_clean has no inline bbox/provenance/internal ids
markdown_clean plus source map can resolve back to evidence spans
markdown_anchored includes stable evidence anchors
markdown_review includes page markers and warnings
html_review exposes bbox-compatible anchors
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

### Parser Warnings

Warnings must be structured and visible:

```text
reading_order_uncertain
multi_column_ambiguous
table_structure_low_confidence
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

## 13. Architecture

### Phase Architecture

```text
Java API
  |
  | existing Java ParsedDocument / Citation compatibility
  | new TrustDocument contract
  v
Parser Adapter
  |
  +-- Java PDFBox baseline
  |
  +-- Rust core native binding
  |
  +-- Rust sidecar process
        |
        +-- text layer parser
        +-- page rasterizer
        +-- layout detector
        +-- table recognizer
        +-- OCR backend
        +-- evidence reconciler
```

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

## 14. Implementation Phases

### Phase 0: Contract Freeze

Deliverables:

```text
TrustDocument v1 draft
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

### Phase 5: OCR Routing

Deliverables:

```text
text-layer quality detector
OCR backend interface
page image rasterization
OCR text + bbox reconciliation
OCR confidence gate
```

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

## 15. Acceptance Metrics

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
sidecar RSS and peak model memory are reported by doctor
```

## 16. Open Questions

```text
Which Apache/MIT-compatible model artifacts can be redistributed or referenced?
Should DocTruth ship model download manifests or only model adapters?
Should table-server presets live in OSS, or only as optional user-supplied models?
Should Java default to sidecar or embedded native runtime after Rust core exists?
What is the minimum fixture corpus size before claiming parser-runtime alpha?
Should compact_llm use an existing TOON-compatible syntax or a DocTruth-owned compact evidence format?
Which Rust Markdown renderer should be the default for GFM parity?
```

## 17. Product Boundary

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
