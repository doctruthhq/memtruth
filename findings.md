# DocTruth v1 Parser Runtime Findings

## Current State

- Repository branch is `feat/v1-trust-document-runtime-tdd`.
- `docs/pdf-parser-runtime-prd.md` is committed as `a22c7b6 docs: add v1 parser runtime prd`.
- Worktree has pre-existing dirty changes unrelated to the PRD commit:
  CLI parse/Markdown/OCR files and tests are modified or untracked.
- Project is a Java 25 Maven single module.
- Existing public parser model includes `ParsedDocument`, `ParsedSection`,
  `TextSection`, `TableSection`, `FigureSection`, `SourceLocation`,
  `BoundingBox`, and `Citation`.
- Current PDF parsing path is Java/PDFBox based, with layout-related classes:
  `PdfDocumentParser`, `PdfPageBlockExtractor`, `PdfVisualTextLayout`,
  `PdfLineSegment`, `PdfSemanticSectionCoalescer`, and related helpers.

## PRD Requirements Extracted

- `TrustDocument` is the canonical evidence-carrying document representation.
- `TrustUnit` is the smallest stable citeable unit inside a `TrustDocument`.
- JSON full output must be lossless for the contract.
- Clean Markdown is a consumption view and not audit-grade by itself.
- Source maps must resolve rendered output back to evidence spans.
- Compact LLM wire format must be deterministic and materially smaller than
  full JSON while preserving evidence ids and hierarchy.
- Parser uncertainty must be represented as structured warnings.
- Severe parser warnings block audit-grade status.
- Backend design should allow PDFBox baseline now and Rust runtime later.

## Implementation Constraints

- Do not copy Kreuzberg implementation code; use the PRD behavior contracts only.
- Avoid broad refactors until contract tests require them.
- Existing dirty changes may be user/previous work; do not revert them.
- `ArchitectureContractTest` enforces public records with at most 5 components.
  Therefore v1 PRD shapes such as `TrustDocument` and `TrustUnit` must be
  decomposed into small records instead of one wide public record.
- Existing code style uses immutable public records with compact constructors,
  null/blank validation, `List.copyOf`, and focused contract tests.

## Current Contract Slice

- The implemented slice is a Java public-contract/runtime-baseline slice, not a
  real Rust parser runtime.
- `TrustDocument` now supports lossless/evidence JSON, clean Markdown, compact
  LLM wire, Markdown source-map rendering, HTML review anchors, and LLM/RAG
  chunks with unit/evidence ids.
- `TrustHtml.toMarkdownPassthrough` is intentionally conservative and uses
  existing dependencies. A richer HTML5/GFM renderer should be a separate ADR
  and dependency decision.
- The baseline parser backend is `PdfBoxParserBackend`; it proves the backend
  SPI and local/offline path while leaving the Rust runtime behind the same
  contract for a later implementation.
- `ModelRuntimePolicy` currently locks local-first policy behavior: lite mode
  has no required models, offline required models produce severe warnings, and
  online required models declare network access.
- CLI now has two parse surfaces:
  old `--json`/`--markdown` remain backward-compatible `ParsedDocument`
  renderings, while new `--format ... --profile ...` emits v1
  `TrustDocument` outputs.
- Doctor is now closer to PRD runtime readiness: it reports parser backend
  availability, model cache location, required model count, no-network lite
  mode, and JVM memory estimates. It still does not verify real model SHA files
  because no real model manifest/cache implementation exists yet.
- `TrustDocumentParser` now exposes file/bytes/input-stream/batch entrypoints
  over the Java/PDFBox baseline. It is a contract-compatible parser API, not a
  real Rust sidecar.
- `DocTruthDocument.withParser(ParserPreset).parse()` makes the PRD-style SDK
  path usable without breaking the existing extraction-oriented
  `fromPdf(...).extractJson(...)` flow.
- `ModelCacheVerifier` now verifies local artifact existence and SHA-256 for
  model descriptors, returning severe warnings for missing or mismatched files.
  It does not download models or run ONNX.
- `ParserBenchmarkRunner` is a lightweight metric runner for parsed
  `TrustDocument` fixtures. It now has an acceptance-threshold gate through
  `requireMinimums(...)`, so CI can fail when a metric falls below a configured
  minimum. `ParserBenchmarkCase` can also carry an expected `TrustDocument`;
  when present, the runner reports `bbox_iou` and `table_cell_f1` for
  layout/table quality gates. The labeled PDF benchmark corpus and real
  parser-quality targets still need real fixtures before parser quality can be
  claimed.
- `SidecarParserBackend` now proves the Java-side Phase 2 protocol boundary:
  JSON request over stdin, canonical `TrustDocument` JSON over stdout, and
  structured crash/invalid-response error mapping. This is not the Rust
  `doctruth-runtime` binary; it is the Java adapter that the binary can satisfy.
- CLI sidecar wiring is now present for TrustDocument outputs:
  `doctruth parse <pdf> --backend sidecar --runtime <path> --preset standard --format ...`.
  Summary and legacy ParsedDocument outputs remain PDFBox-only because the
  sidecar returns the v1 `TrustDocument` contract, not the old Java
  `ParsedDocument` model.
- A real Rust `doctruth-runtime` binary now exists under
  `runtime/doctruth-runtime`. It is intentionally a protocol MVP:
  `--doctor`, stdin `parse_pdf`, and stable JSON errors are implemented.
- Historical note: the first real Rust text-layer slice used `pdf-extract`.
  The current runtime has since moved page text extraction to `pdf_oxide`.
  A text-layer PDF can produce citeable `LINE_SPAN` units with
  text, page, reading order, evidence span id, confidence, and a page-level bbox
  fallback.
- Page-level text extraction now uses `pdf_oxide` column-aware page text, so
  multi-page text-layer PDFs produce one `TrustPage` per page. Text-bearing
  pages are split into stable line-level units, which is a better fit for
  evidence replay than the earlier page-level block fallback.
- Missing or unreadable PDFs now fail with stable runtime error JSON:
  `PDF_EXTRACTION_FAILED`.
- The Rust text-layer slice is still not fully layout-grade: page text uses
  `pdf_oxide` column-aware extraction, while precise positioned bboxes, table
  extraction, and rendered page hashes still come from transitional
  `lopdf`/`pdftoppm` support.
- Adding `pdf_oxide` materially increases the Rust dependency tree because its
  rendering path brings PDF/font/image/rendering dependencies. ADR 0010 should
  be refreshed before release to record the updated backend tradeoff.
- `scripts/smoke-doctruth-runtime.sh` is the repeatable local smoke for the
  runtime binary. It builds/tests the crate, checks `--doctor`, generates a
  real PDF fixture, and validates the extracted `TrustDocument` text unit.
- `scripts/smoke-doctruth-cli-sidecar.sh` is the repeatable end-to-end smoke
  for the Java CLI plus Rust sidecar. It builds the shaded CLI and runtime,
  generates a real text-layer PDF, runs sidecar mode, validates JSON full
  `LINE_SPAN` output, and validates clean Markdown plus source-map output.
- The next parser-quality phase should add measurable parser-quality fixtures
  and then improve Rust output beyond page-level fallback: precise bbox
  evidence, column-aware reading order, and table/layout/OCR model execution
  behind separate tests and dependency ADRs.
- The CLI sidecar smoke proves integration, not parser quality. It does not
  validate real-world layout PDFs, precise bboxes, multi-column reading order,
  table cells, OCR, or model-assisted layout/table extraction.
- `ParserBenchmarkCase.fromPdf(...)` now lets parser-quality tests start from
  actual PDF files. This closes an important testability gap: benchmark gates
  can now exercise the parser before scoring `TrustDocument` output.
- `ParserBenchmarkRunner` now reports `bbox_coverage` for every case. This is
  weaker than human-labeled `bbox_iou`, but it catches regressions where a
  parser emits citeable units without any bbox anchors.
- The current real-PDF benchmark fixture uses generated PDFs and the Java/PDFBox
  baseline. It is not a substitute for a labeled real-world PDF corpus with
  human-reviewed expected bboxes, table cells, OCR text, and reading order.
- `ParserBenchmarkCase.fromPdf(..., expectedDocument)` now closes the next
  benchmark gap: quality gates can parse a real PDF and immediately compare the
  parsed `TrustDocument` against expected bbox labels through `bbox_iou`.
- The current expected-bbox fixture uses broad manual normalized boxes and a
  conservative IoU threshold. That is useful for regression protection, but the
  PRD still needs human-reviewed labeled fixtures for precise bbox quality.
- The Java/PDFBox baseline now has a conservative bordered-table path. PDF
  graphics extraction records vertical separators, a full-grid detector maps
  text positions into row/column cells, and generated real-PDF fixtures can
  pass `table_cell_f1` against expected `TrustTable` cells.
- Detected bordered-table regions now suppress overlapping `TEXT_BLOCK` output
  before appending `TableSection`s. This keeps clean Markdown and LLM-facing
  output from duplicating table cell text.
- `TableSection` now carries an optional table-region bbox. The Java/PDFBox
  bordered-table path preserves that region into `TrustTable.boundingBox`, and
  benchmark cases can gate it with `table_region_iou`.
- `TableSection` now also carries immutable per-cell `TableCellRegion` entries
  for simple bordered-grid tables. The Java/PDFBox path propagates those cell
  bboxes into `TrustTableCell.boundingBox` and each emitted `TABLE_CELL`
  `TrustUnitLocation.boundingBox`, so downstream evidence consumers can anchor
  individual cell values rather than only whole table regions.
- Clean Markdown table output now uses GFM pipe-table shape for `TrustTable`
  (`| header | ... |`, separator row, body rows). Markdown source-map rendering
  uses the same table shape and maps each rendered cell value back to its
  `TABLE_CELL` unit id and evidence span ids.
- The Markdown renderer is still a focused local renderer, not a full Comrak
  stack. HTML/Djot/plain cross-format parity, complete escaping rules, and
  richer block-node rendering remain future PRD work.
- The Rust sidecar now has a narrow bordered-grid table extraction path. It
  directly uses `lopdf` with default features disabled to parse content stream
  operations, detects simple `m/l/S` grid lines, maps `Td/Tj` text positions
  into cells, and emits `TrustTable`, `TrustTableCell`, and `TABLE_CELL` units
  with normalized bboxes.
- Runtime and CLI sidecar smoke now cover both line-level text extraction and
  generated bordered-table extraction. The CLI smoke proves Java can consume
  sidecar table JSON and render the resulting clean Markdown table.
- The bordered-table path is intentionally narrow. It does not claim borderless
  table recognition, merged-cell inference, multi-page table continuation,
  OCR-backed table extraction, model-assisted table structure recognition, or
  full Java/Rust parser parity.
- The Rust sidecar now emits positioned `LINE_SPAN` bboxes for simple text-layer
  PDFs when content-stream text positions are available. This removes the
  `runtime_bbox_page_fallback` warning for the covered `Tf`/`Td`/`Tj` path and
  gives downstream evidence consumers a smaller anchor than the whole page.
- The positioned-text bbox path is still approximate. It estimates width from
  text length and font size and does not yet account for full font metrics,
  text matrices, rotations, multi-column reading order, complex transforms, or
  real-world labeled bbox accuracy.
- `compact_llm` now has deterministic `t|` table records and `w|` parser/unit
  warning records in addition to `doc|` and `u|` records. This moves it closer
  to the PRD requirement that compact output preserve replay/evidence context
  rather than becoming untraceable compressed prose.
- The compact wire format is still intentionally local and minimal. It is not a
  finalized TOON-compatible syntax, does not yet encode full bbox/table-cell
  geometry inline, and was not yet corpus-measured at this point in the work.
- Rust protocol tests must not share temp PDF filenames across parallel tests.
  A process id plus timestamp was not unique enough on macOS under concurrent
  cargo tests; a process-local atomic sequence is now included in generated
  fixture paths.
- `html_review` now emits bbox-compatible attributes for citeable units that
  have a normalized bbox: `data-bbox="x0,y0,x1,y1"` plus
  `data-bbox-space="normalized-0-1000"`. This gives review UI and overlay code
  a stable bridge from HTML nodes back to page-space evidence anchors.
- `html_review` now also emits semantic table/cell review nodes for structured
  tables. Tables carry `data-trust-table-id`, page, and optional normalized
  bbox attributes; cells carry `data-trust-cell-id`, optional
  `data-trust-unit-id`, evidence span ids, optional normalized bbox attributes,
  and escaped cell text.
- The HTML review renderer is still a simple semantic HTML output. It does not
  yet render page images, visual table-region overlays, visual cell overlays,
  or a complete browser review UI.
- `writeMarkdownClean(...)` and `writeJsonLines(...)` now use incremental
  writer paths instead of rendering the full output string and writing it in one
  call. This improves large-output behavior for LLM-facing Markdown and batch
  JSONL, while keeping byte-for-byte parity with `toMarkdownClean()` and
  `toJsonLines()`.
- `TrustRenderedDocument` now carries `sourceHash` and `contentHash`.
  `toMarkdownWithSourceMap()` computes `contentHash` from the byte-stable clean
  Markdown text, and CLI `--source-map` sidecars include both hashes so clean
  Markdown can be tied back to its source and exact rendered content.
- `markdown_anchored` now includes bbox metadata inside the evidence anchor
  when a citeable unit has a normalized bbox, while `markdown_clean` remains
  free of bbox/provenance/internal ids.
- `markdown_review` now includes both parser warnings and unit-scoped warnings
  with unit id, severity, code, and message. This makes low-confidence anchors
  and estimated evidence visible in review/replay output.
- `plain_text` is now a first-class clean consumption profile across SDK, CLI,
  PDFBox capabilities, and sidecar capabilities. It renders text blocks plus
  tab-separated table rows from the same `TrustDocument` source and intentionally
  omits Markdown table separators, evidence anchors, bbox metadata, and hashes.
- Plain text is useful for cleanup, keyword search, and simple LLM context, but
  must not be treated as audit-grade output by itself. Replay/evidence workflows
  still need `json_full`, `json_evidence`, or Markdown plus source-map sidecars.
- `verify-source-map` now verifies clean Markdown source-map sidecars against
  the rendered file's content hash and, when supplied, the original source
  document hash. This closes the local tamper-detection loop for rendered
  Markdown/source-map pairs.
- Source-map verification is still local hash validation. It is not yet signed
  audit packaging, timestamping, WORM storage, or external notarization.
- `TrustDocument` Audit JSON now includes `canonicalHash` and `evidenceHash`
  in addition to `sourceHash`, parser run metadata, audit-grade status, and
  evidence units. This makes parser audit output explicitly hashable for local
  replay/compliance storage.
- Audit JSON hashability is still not an external signature. Separate work is
  needed for signing keys, timestamping, key rotation, WORM/legal hold, or
  notarized checkpoints.

## 2026-06-14 Goal 1 Rust Default Audit

- SDK default evidence: `TrustDocumentParser.parse(...)` and path-first
  `TrustDocumentParserBuilder.backend(AUTO)` now require
  `DocTruthRuntime.requireConfiguredCommand(...)` or a builder-provided runtime.
  That is aligned with "missing Rust runtime is install/config error".
- CLI default evidence: `ParseCommand` keeps `ParserBackendChoice.AUTO` as the
  default and routes summary/v1 formats through `SidecarParserBackend`; explicit
  `--backend pdfbox` is required for Java/PDFBox legacy/oracle mode.
- Runtime discovery evidence: `DocTruthRuntime` resolves
  `doctruth.runtime.command`, `DOCTRUTH_RUNTIME_COMMAND`, or source-tree
  `runtime/doctruth-runtime/target/{release,debug}/doctruth-runtime`, and the
  source-tree path can be disabled for missing-runtime tests.
- Open implementation gap: sidecar child-process environment does not yet map
  Java properties such as model/OCR worker commands into
  `DOCTRUTH_RUNTIME_MODEL_COMMAND`/`DOCTRUTH_MODEL_COMMAND`, so model-assisted
  Rust-default execution can depend on how the caller configured workers.
- `html_review` now wraps review nodes inside page containers. Each page
  surface exposes `data-trust-page-number`, page width, page height,
  text-layer availability, and source-derived page image hash, and the renderer
  scopes unit/table/cell anchors under the matching page.
- The page-aware HTML review output is enough for downstream overlay tooling to
  bind DOM nodes to page geometry. It is still not a full browser reviewer: it
  does not render page images, draw bbox overlays, provide click/hover
  inspection, or implement an auditor console.
- `compact_llm` now preserves optional bbox metadata on unit records using a
  suffix such as `|bbox=100,100,500,200`. This keeps the compact LLM/RAG path
  from silently dropping evidence positioning when the parser has a normalized
  bbox.
- The compact wire syntax remains DocTruth-owned and intentionally minimal. It
  has not yet been validated as TOON-compatible.
- Compact LLM output now has a public `TrustDocument.writeCompactLlm(Writer)`
  path and CLI `--format compact --out` uses that writer. The writer is
  byte-stable against `toCompactLlm()` and writes incrementally through the
  chunked writer helper.
- Compact LLM output now also has `TrustDocument.toCompactLlmWithSourceMap()`
  and CLI `--format compact --source-map`. The source-map records rendered
  offsets for compact unit text fields, so compact LLM/RAG context can be
  verified and traced back to unit ids plus evidence span ids.
- Compact source-map support currently maps unit text fields only. Table summary
  records and warning records are still un-mapped metadata records, and the
  compact wire is still not a finalized TOON-style format.
- `ParserBenchmarkRunner` now reports compact LLM corpus metrics:
  `compact_llm_size_reduction`, `compact_llm_round_trip`, and
  `compact_llm_source_map_coverage`. These reuse the existing threshold gate so
  corpus manifests can enforce LLM/RAG efficiency and replayability alongside
  parser quality.
- Streaming support is still partial: current parser paths still materialize
  `TrustDocument`. SDK writer paths now cover clean Markdown, JSONL, compact
  LLM, JSON full, JSON evidence, Audit JSON, anchored/review Markdown, plain
  text, and HTML review, and CLI `--out` routes all current TrustDocument
  output formats through writer paths. Stdout, source-map sidecar
  serialization, and deterministic hash inputs still use aggregate render
  paths.
- Do not run multiple Maven test invocations concurrently in this repository
  against the same `target/` directory. It can create misleading broad
  `cannot find symbol` compile failures from target-directory races.
- Future PRD work should use milestone-sized batch TDD: write all RED tests for
  one coherent milestone first, then implement and verify the milestone as a
  unit. Do not batch the entire PRD or unrelated hard systems into one failure
  set.
- `TrustDocument` audit JSON now supports the same SDK-level
  `SignatureProvider` path as `ExtractionResult`: callers can identity-pass,
  sign, or wrap audit JSON before writing it to a package file. This completes
  local signed/wrapped package output at the SDK boundary, not external
  timestamping, key management, notarization, legal hold, or WORM storage.
- `ParserBenchmarkCorpus` now makes parser-quality fixtures executable from a
  JSON manifest with manifest-relative source paths, expected Markdown labels,
  expected `TrustDocument` JSON labels, and shared metric minimums. This closes
  the harness gap for reproducible corpus runs, but the actual human-labeled
  real-world PDF corpus remains unbuilt.
- Internal `TrustDocumentJson.fromJsonFull(...)` now tolerates blank page
  `imageHash` values because current Java adapter output can produce them. This
  lets benchmark labels written from `toJsonFull()` round-trip without relaxing
  core fields such as doc id, source hash, parser run, unit ids, or evidence
  fields.
- `doctruth benchmark-corpus <manifest.json> [--json]` now exposes the labeled
  corpus runner to local CLI/CI use. It returns exit code 1 for threshold
  failures through `CliException`, and exit code 2 for command usage mistakes.
- Benchmark corpus smoke should not depend on Python-only PDF libraries such as
  `reportlab`; the current smoke writes a minimal text-layer PDF directly so it
  can run in a lean OSS checkout.
- Clean Markdown now preserves fenced code blocks and inline Markdown links as
  text-block content, while GFM table-cell rendering escapes brackets, pipes,
  and backslashes. This closes the immediate GFM escaping contract without
  introducing a full Markdown renderer dependency.
- `TrustAuditVerifier` now provides local replay verification for
  `TrustDocument` Audit JSON against full TrustDocument JSON. The verifier
  checks document id, source hash, canonical hash, audit-grade status, parser
  run metadata, evidence hash, and evidence payload. The CLI exposes the same
  contract as `doctruth verify-audit <trust-document.json> <audit.json>`, and
  sidecar smoke validates it on real CLI-generated outputs.
- `html_review` now has both semantic bbox anchors and a page-scoped visual
  overlay layer. The overlay layer emits unit/table/cell overlay nodes with
  `data-trust-bbox-overlay`, `data-trust-overlay-for`, and percent CSS derived
  from normalized 0-1000 bboxes. This is still static review HTML, not a full
  interactive auditor console.
- Static parser-only SDK entrypoints now accept explicit parser presets:
  `TrustDocumentParser.parse(path, preset)`, bytes/input-stream variants, and
  `parseBatch(paths, preset)`. This closes a product gap where callers could
  only use the lite PDFBox path from the simple parser API. Model-assisted
  presets currently run the same local heuristic/PDFBox baseline for inspection
  but record severe `model_unavailable_fallback` warnings and evaluate as
  `NOT_AUDIT_GRADE` when required models are unavailable. Real ONNX
  layout/table/OCR execution is still not implemented.
- Model-unavailable fallback warnings are now per required model rather than a
  single generic parser warning. Each warning carries the model identity and
  expected SHA, which makes it possible for future doctor/audit/replay tooling
  to explain whether layout detection, table recognition, or OCR routing was
  missing.
- `json_full` and Audit JSON now have SDK writer APIs:
  `TrustDocument.writeJsonFull(Writer)` and `writeAuditJson(Writer)`. They are
  tested for byte parity with the string renderers and chunk writes into the
  caller-owned writer. This improves large-output export behavior for replay
  formats, but parser ingestion still materializes `TrustDocument` and
  canonical hashing/evidence hashing still compute deterministic hash inputs.
- CLI `--out` now routes clean Markdown, JSONL, compact LLM, JSON full, and
  Audit JSON through writer paths instead of rendering one full string before
  file output. JSON evidence now also has an SDK writer path and uses it from
  CLI `--out`.
- Anchored Markdown, review Markdown, plain text, and HTML review now also have
  SDK writer APIs and CLI `--out` writer routing. HTML review has an explicit
  regression assertion that it emits one bbox overlay layer per page.
- RapidOCR remains an appropriate optional local OCR worker candidate for
  DocTruth because its public project documents Apache-2.0 licensing, a Python
  API shaped as `from rapidocr import RapidOCR; engine = RapidOCR(); result =
  engine(img)`, and multiple local backends including MNN/ONNXRuntime. DocTruth
  should still keep RapidOCR behind the JSON stdin/stdout worker boundary rather
  than importing Python from Java or bundling OCR model binaries in the generic
  jar.
- The current OCR implementation already has `LocalOcrWorkerEngine`,
  `ParserPreset.OCR`, doctor readiness reporting, low-confidence audit gating,
  and a fake-MNN smoke. The concrete gap is a DocTruth-owned
  `doctruth-rapidocr-mnn-worker` adapter plus discovery/smoke coverage, not the
  Java parser API itself.
- Java/PDFBox and Rust `doctruth-runtime` now have generated bordered-table
  merged-cell parity for horizontal column spans and vertical row spans at the
  unit/protocol/smoke boundary. The implementations infer horizontal span when
  an internal vertical border does not cover the row band, infer vertical span
  when an internal horizontal border does not cover the cell's column band, and
  emit `rowRange`/`columnRange` for merged cells. This is still fixture-grade
  heuristic support, not proof of multi-page table continuation, model-assisted
  structure recognition, OCR-backed tables, or real-world labeled table
  accuracy.
- Rust `doctruth-runtime` page metadata no longer has to use hard-coded page
  dimensions or source-hash-derived placeholder page hashes. It now reads page
  MediaBox dimensions and emits stable `sha256:` hashes over page number,
  dimensions, and content bytes. This is useful sidecar metadata parity, but it
  is not rendered-PNG parity with the Java/PDFBox page image pipeline.
- Rust `doctruth-runtime` now mirrors the Java no-silent-fallback contract for
  model-assisted presets. When `table-lite`, `standard`, `table-server`, or
  `ocr` require local models that are not executed by the runtime, the sidecar
  still returns heuristic output for inspection but includes required model ids
  in `parserRun.models`, emits per-model severe
  `model_unavailable_fallback` warnings, and evaluates as
  `NOT_AUDIT_GRADE`. This is fallback honesty, not real model execution.
- `doctruth doctor --json` now separates OCR worker executable availability
  from runtime readiness. A worker can be present on `PATH` but report
  `ready=false` with a structured `statusCode` such as
  `rapidocr_unavailable`. The RapidOCR adapter itself now has `--doctor`, which
  imports and initializes `RapidOCR()` before reporting ready. On this machine,
  the adapter self-test currently reports `rapidocr_unavailable` under the
  default `python3`, while the raw Python 3.10 `rapidocr` command still has a
  NumPy ABI mismatch. This is now visible instead of being silently treated as
  OCR ready.
- Java/PDFBox now has fixture-grade multi-page table continuation support for
  adjacent generated bordered tables with repeated headers. It merges the table
  sections, removes the duplicate continuation header, and keeps continued
  `TABLE_CELL` units on their original source page. This required making
  `TableCellRegion` page-aware while keeping the public record under the
  architecture limit by using `TrustCellRange` row/column ranges. Rust sidecar
  continuation, OCR-backed tables, and labeled real-world continuation accuracy
  are still unproven.
- Rust `doctruth-runtime` now has fixture-grade multi-page table continuation
  support for adjacent generated bordered tables with repeated headers. The
  runtime merges matching adjacent tables after extraction, removes the
  continuation header, offsets continued row ranges, and stores the source page
  per table cell so generated `TABLE_CELL` units for page-2 rows still cite
  page 2. Runtime smoke and Java CLI sidecar smoke both exercise this path.
  This is heuristic generated-fixture support, not proof of model-assisted
  table structure recognition, OCR-backed table extraction, or real-world
  labeled table accuracy.
- Rust `doctruth-runtime` now has rendered PNG page image hash parity when a
  configured renderer or local `pdftoppm` is available. Runtime and Java CLI
  sidecar smokes compare `TrustPage.imageHash` against actual `pdftoppm` PNG
  bytes. The runtime still falls back to a stable content/dimension hash if no
  renderer is available, and this is hash parity rather than a Rust-owned
  persisted page artifact pipeline, interactive review UI, or OCR accuracy
  proof.
- The RapidOCR adapter now handles RapidOCR 3.8-style array-like output for
  `boxes`, `txts`, and `scores`; the previous `attr or []` normalization could
  fail with NumPy-style `truth value is ambiguous` errors. The worker smoke now
  locks that behavior with an array-like fake RapidOCR result.
- A real opt-in RapidOCR smoke now exists and passes with an isolated venv using
  `rapidocr==3.8.1` plus `rapidocr_onnxruntime==1.4.4`. It proves worker
  `--doctor`, direct OCR, and Java CLI `parse --preset ocr` over a generated
  scanned PDF. The user's default global Python/RapidOCR environment is still
  broken because Python 3.10 sees a cpython-314 NumPy extension, so the real
  smoke intentionally isolates dependencies. This does not prove an MNN-specific
  backend package or labeled real-world OCR accuracy.
- Parser benchmarks now include `ocr_text_accuracy`, computed from OCR-region
  text against expected Markdown. Benchmark corpus manifests can request
  `preset: "ocr"` per case, and the corpus smoke now gates a generated
  scanned-PDF OCR case through the CLI. This turns OCR from a string-only smoke
  into a threshold-gated generated corpus case, but still does not replace a
  labeled real-world OCR corpus.
- Local model-worker protocol now exists for configured model-assisted presets.
  `TABLE_LITE` can call a configured worker, accept full `TrustDocument` JSON,
  preserve model-produced `TrustTable`/`TABLE_CELL` units, and avoid
  `model_unavailable_fallback` when the worker succeeds. This is a worker
  protocol and fake-worker smoke, not actual ONNX/TATR/SLANeXT/RT-DETR model
  inference or real-world layout/table accuracy proof.
- `doctruth doctor --json` now exposes configured model-worker readiness under
  `models.worker`, including executable availability, runtime readiness,
  status code/message, timeout, and loaded model ids. The model-worker smoke
  verifies this before table-lite parsing. This closes the deployment diagnosis
  gap for configured workers, but not real model inference, model downloads, or
  peak RSS reporting.
- `models.worker` now also exposes worker-reported `rssMb` and `peakMemoryMb`.
  The values default to `0` when omitted, and the model-worker smoke verifies
  them through packaged CLI doctor JSON. This is protocol-level observability,
  not independent process sampling or proof of real ONNX model memory usage.
- Model-assisted parse requests are now cache-aware. A configured model worker
  receives `modelCacheDirectory` and per-model `cachePath`, `cacheStatus`,
  `actualSha256`, and `actualSizeBytes` from `ModelCacheVerifier`. This gives
  future real ONNX/TATR/SLANeXT workers a deterministic handoff, while current
  placeholder SHAs still mean generated smokes prove `MISSING` metadata rather
  than READY model loading.
- Local model manifests now close that placeholder-only gap for configured
  workers. When `doctruth.model.manifest` or `DOCTRUTH_MODEL_MANIFEST` points
  to a JSON manifest keyed by preset id, `LocalModelWorker` uses those model
  descriptors before verifying the local cache. The model-worker smoke now
  writes a SHA-matched `slanet-plus:local-smoke` artifact and verifies
  `cacheStatus=READY` through the packaged CLI path. This is still a model
  handoff contract, not real ONNX/TATR/SLANeXT/RT-DETR inference.
- `doctruth cache warm <manifest.json> --preset <preset>` now warms the local
  model cache from manifest-defined local paths or `file://` sources, writes
  artifacts under deterministic `ModelDescriptor.cacheFilename()` names, and
  verifies SHA-256 with the shared cache verifier. It now also supports HTTP(S)
  model sources through a streaming JDK `HttpClient` download path that writes a
  temp file before moving into the cache. `--offline` refuses remote sources
  before any network request. This closes the generic install/download
  contract, while real model URL selection and real model execution remain
  open.
- `doctruth doctor --json` now uses `DOCTRUTH_MODEL_MANIFEST` as a local
  model-cache preflight, not just parse-time worker metadata. It aggregates all
  manifest preset descriptors, verifies artifacts in `DOCTRUTH_MODEL_CACHE`,
  and reports `allReady` plus per-artifact identity/status/SHA/size/cache path.
  This means a developer or agent can diagnose READY/MISSING/SHA_MISMATCH
  before invoking a model-assisted parser preset. It still does not run ONNX or
  sample real worker memory under inference load.
- Model manifests now carry runtime hints separately from the SHA-verified
  artifact descriptor. The fields `task`, `backend`, `format`, `precision`,
  and `license` survive `cache warm --json`, `doctor --json`, and local
  model-worker request JSON. This gives future real ONNX/TATR/SLANeXT adapters
  routing metadata without expanding `ModelDescriptor` beyond the architecture
  limit. This is still metadata propagation, not actual model execution.
- A generic ONNXRuntime model-worker adapter now exists at
  `scripts/doctruth-onnx-model-worker`. The ONNX smoke generates a tiny
  identity model, warms the cache, runs worker `--doctor`, loads the cached
  model with ONNXRuntime, executes one inference, and returns a `TrustDocument`
  through the Java CLI model-worker path. Install and release packaging now
  include the ONNX worker. This proves local ONNX execution plumbing, but not
  production RT-DETR/TATR/SLANeXT model accuracy.
- Strict RapidOCR MNN backend readiness is now distinct from generic RapidOCR
  availability. With `DOCTRUTH_RAPIDOCR_BACKEND=mnn`, the worker imports
  `MNN` or `mnn` before reporting backend readiness and exposes `backend`,
  `backendReady`, and `backendVersion` in doctor JSON. The dedicated MNN
  backend smoke and release smoke cover this contract. Real MNN OCR recognition
  quality and labeled scanned-PDF accuracy remain open.
- The ONNX model worker now has a synthetic TATR/DETR-style table decoder
  contract. For `task=table-structure-recognition`, it finds outputs named like
  `pred_logits` and `pred_boxes`, treats boxes as normalized `cx, cy, width,
  height`, and emits `TrustTable` plus `TABLE_CELL` units. The dedicated smoke
  proves this through Java CLI parse and SHA-warmed cache. This is not yet
  curated real TATR/SLANeXT/RT-DETR weight execution or real-world parser
  accuracy.
- Low-confidence ONNX table structure detections are now explicit audit
  blockers. When the synthetic TATR/DETR-style decoder keeps a table/cell
  detection below `0.85`, it emits a severe parser warning
  `table_structure_low_confidence` and returns
  `auditGradeStatus=NOT_AUDIT_GRADE` while preserving the table and cells for
  review/replay. This closes the silent-low-confidence table gap for the local
  decoder contract, not real-world table confidence calibration.
- The ONNX model worker now also has a synthetic RT-DETR/DETR-style layout
  decoder contract. For `task=layout-detection`, it decodes outputs named like
  `pred_logits` and `pred_boxes` into bbox-bearing `TEXT_BLOCK` layout units
  sorted by reading order. The dedicated smoke proves this through Java CLI
  parse and SHA-warmed cache. This is still not curated real RT-DETR weight
  execution or real-world layout accuracy.
- Low-confidence ONNX layout detections are now explicit audit blockers. When
  the synthetic layout decoder keeps a detection below `0.85`, it emits a
  severe unit warning `layout_low_confidence` and returns
  `auditGradeStatus=NOT_AUDIT_GRADE` while preserving the region for
  review/replay. This closes the silent-low-confidence layout gap for the local
  decoder contract, not real-world confidence calibration.
- Direct ONNX worker parse responses now include resource metrics from a real
  ONNXRuntime session: total wall time, inference wall time, RSS, and peak
  memory. The dedicated resource smoke verifies these fields over a generated
  ONNX identity model. This is stronger than protocol-only doctor defaults, but
  still not a production-weight RSS/throughput benchmark.
- Parser benchmark corpus manifests now support SHA-pinned remote public PDF
  fixtures through `sourceUrl` plus `sourceSha256`. The W3C dummy PDF smoke
  downloads into `.doctruth-corpus-cache`, verifies SHA-256, and gates a
  human-authored expected `TrustDocument` label. This closes the generated-only
  corpus smoke gap for one public PDF, but not the larger multi-layout
  real-world corpus.
- The ONNX model worker is now packaged as a tiny executable shim plus
  `doctruth_onnx_worker_lib.py`. Source install, release tarball, Homebrew
  formula generation, and release smoke all include the helper module, while
  existing identity/TATR/layout/resource/low-confidence smokes still exercise
  the same worker command. This is an internal packaging split, not a new model
  accuracy claim.
- Rust sidecar doctor now reports process `rssMb` and `peakMemoryMb` without
  adding a Rust dependency. Linux reads `/proc/self/status`; macOS/other Unix
  falls back to `ps -o rss=`. This satisfies the local doctor resource contract,
  but production-weight model peak memory remains unmeasured until real models
  are loaded.
- Benchmark corpus loading now has an explicit offline mode. `ParserBenchmarkCorpus.load(path, true)`
  and `doctruth benchmark-corpus <manifest> --offline` refuse uncached remote
  `sourceUrl` fixtures before any network request, while cached remote PDFs are
  still accepted after `sourceSha256` verification. The benchmark smoke also
  runs the CLI with `-Djava.awt.headless=true` to avoid macOS/PDFBox native AWT
  aborts during generated OCR PDF rendering.
- Parser benchmark corpora now distinguish higher-is-better `minimums` from
  lower-is-better `maximums`. The first lower-is-better metric is
  `strict_warning_false_negative_rate`: it compares expected severe warning
  codes from parserRun and unit-local warning labels against actual severe
  warnings. This lets corpus labels fail when a parser silently misses an audit
  blocking condition. It is a contract gate; proving the PRD's <= 2% target
  still requires a real warning-labeled PDF corpus.
- Parser benchmark cases now carry parse latency. Directly constructed cases
  default to `0.0` for deterministic unit fixtures, while `fromPdf(...)`
  measures wall-clock parse time. Corpus output reports aggregate
  `parser_latency_p50` and `parser_latency_p95`, and `maximums` can gate
  `parser_latency_p95` at the corpus level. This proves the latency reporting
  contract, not the PRD's production latency target on a broad labeled corpus.
- Benchmark threshold routing now needs to treat aggregate metric names as a
  separate namespace from per-case metrics. `compact_llm_size_reduction_min`
  is derived from per-case `compact_llm_size_reduction` and enforced as a
  corpus aggregate `minimums` threshold; otherwise manifests fail against a
  missing per-case key with misleading `actual=0.0` output.
- The recorded real-world PDF corpus caught a concrete invalid-evidence risk:
  some table grid/cell calculations can produce off-page or zero-area boxes.
  Cell bbox normalization must clamp to page bounds and skip collapsed cells so
  downstream review/replay surfaces never receive invalid cell anchors.
- Coverage should be improved with behavior tests first. For this branch, the
  bundle coverage thresholds stayed unchanged; narrowly excluded class-level
  utility/option wrappers are covered through higher-level CLI/runtime contract
  tests rather than counted as independent behavior.
- Current recorded verification is strong for crash/regression safety on the
  checked-in real-world corpus: 383 PDFs, 379 parsed, 4 malformed-input
  failures, 0 bugs. It is not the same as broad human-labeled parser accuracy.
  Layout precision, borderless tables, OCR, model-assisted detection, and
  source-map quality still need larger labeled corpora before product accuracy
  claims are defensible.
- Status wording matters: the current branch should not be described as full
  PRD completion. It completed a large contract/runtime slice and proved a Rust
  sidecar MVP, but full PRD completion still requires a Rust-first default core,
  reusable Rust library crate, real model execution, real OCR quality, and
  labeled benchmark accuracy.
- `doctruth-runtime` was still binary-only even though the PRD calls for Rust
  core reuse behind Java and future native/JNI bindings. Splitting `src/lib.rs`
  from a thin `src/main.rs` is the correct first Rust-first step because it
  makes protocol/parse functions callable from Rust tests and future bindings
  without changing the Java public SDK yet.
- The existing Rust runtime error JSON uses `error_code`, not `code`. New tests
  should preserve that protocol unless there is an explicit versioned protocol
  migration.
- Java SDK runtime selection now has a staged Rust-first default: configured
  `doctruth.runtime.command` / `DOCTRUTH_RUNTIME_COMMAND` wins before PDFBox for
  non-OCR TrustDocument parsing. This is not yet zero-config Rust default
  because there is no packaged runtime discovery path in the Java jar.
- CLI backend semantics are now `auto|pdfbox|sidecar`. `auto` plus `--runtime`
  or `DOCTRUTH_RUNTIME_COMMAND` selects sidecar; explicit `pdfbox` remains the
  compatibility/fallback path. This better matches the PRD than requiring users
  to type `--backend sidecar` whenever they have a runtime.
- Source install and release artifacts previously could not be zero-config
  Rust-first because they omitted `doctruth-runtime`. Packaging now includes
  `bin/doctruth-runtime`, and launchers set `DOCTRUTH_RUNTIME_COMMAND` from the
  same directory before invoking Java. This makes packaged CLI parsing
  Rust-first while keeping direct jar and SDK usage explicit.
- macOS shell smokes should not assume `java` is usable; `/usr/bin/java` may be
  a stub. Use the repo's existing Homebrew/OpenJDK fallback pattern for
  installer/release smoke commands.
- Synthetic ONNX decoder smokes prove the local ONNXRuntime/model-worker path,
  but they should not be used as evidence that real RT-DETR/TATR/SLANeXT
  artifacts work. The new opt-in real model artifact smoke is the right bridge:
  when supplied a SHA-pinned manifest, it exercises cache warm, ONNXRuntime
  doctor, model-worker parse, expected model identity, and expected layout/table
  output shape through the same CLI path.
- Generated OCR corpus gating now covers both directions through the CLI and
  packaged smoke: a correct OCR label passes `ocr_text_accuracy`, and a wrong
  expected Markdown label fails with the OCR case name and metric in stderr.
  This is a stronger regression gate for label drift, but it is still not a
  broad labeled scanned-PDF OCR accuracy corpus.
- The real RapidOCR runtime can now be routed through the benchmark corpus gate
  with `scripts/smoke-doctruth-real-ocr-corpus.sh`. The opt-in run installs
  RapidOCR + ONNXRuntime, downloads PP-OCRv4 mobile ONNX models, verifies the
  worker doctor, and gates `ocr_text_accuracy` on a generated scanned-PDF
  fixture. This closes a runtime integration gap, but it still does not provide
  broad real-world scanned-PDF OCR accuracy.
- `scripts/smoke-doctruth-real-tatr-artifact.sh` now proves one public real
  TATR artifact can enter the DocTruth local model path: Xenova's quantized
  Table Transformer ONNX downloads to a local cache, gets SHA-pinned in a model
  manifest, warms through the CLI cache command, and executes through
  ONNXRuntime/model-worker from the Java CLI parse path. The current ONNX worker
  must default 4D dynamic vision input shapes to `[1, 3, 800, 800]`; replacing
  every dynamic dimension with `1` breaks real conv models. This is still
  execution proof only, not table recognition accuracy, because image
  preprocessing and real model post-processing are not implemented.
- The ONNX worker now has a real page-image input path for 4D vision models. If
  `pdftoppm` and Pillow are available, it renders the first PDF page, resizes it
  to the model input shape, converts it to a channel-first RGB float tensor, and
  reports `metrics.inputSource=rendered_page`; otherwise it reports
  `synthetic_tensor`. This materially improves the real TATR path, but TATR-
  specific normalization and post-processing into table structure are still not
  implemented.
- Public Xenova TATR uses the Table Transformer structure label set, not the
  synthetic smoke's two-label `table/cell` shape. Treating every non-table
  detection as `cell` produced flat row-0 pseudo-cells from real column/row
  detections. The ONNX worker now switches to real TATR decoding when logits
  expose the production class count, then intersects sorted `table row` and
  `table column` boxes to build provisional `TABLE_CELL` evidence. This closes
  the immediate false structure gap for the public artifact smoke, but not
  calibrated production table accuracy.
- `Kreuzberg/layout-models` provides a suitable public document-layout RT-DETR
  ONNX artifact for local smoke coverage. Its `rtdetr/model.onnx` differs from
  the synthetic DETR-style layout smokes: it needs `images` plus int64
  `orig_target_sizes`, and returns `labels`, absolute `boxes`, and `scores`
  rather than `logits`/`pred_boxes`. The worker now supports both shapes. This
  closes the real artifact execution gap for layout detection, while still
  leaving multi-column reading-order improvement and labeled layout accuracy as
  benchmark-corpus work.
- SLANeXT should not be forced into the ONNX worker path. The public/practical
  runtime path is PaddleOCR/SLANeXT-style table recognition that returns table
  structure/cells rather than DETR-style `logits`/`boxes`. The correct DocTruth
  boundary is a separate `doctruth-slanext-table-worker` JSON adapter that can
  be installed with the CLI but does not bundle PaddleOCR/Paddle/model binaries.
  A fake PaddleOCR smoke locks the adapter and Java CLI integration; real
  SLANeXT execution is now verified as an opt-in smoke in an isolated Python
  3.10 venv with PaddleOCR 3.7.0 and PaddlePaddle 3.3.1.
- PaddleOCR 3.7 `TableStructureRecognition.predict()` returns
  `TableRecResult.json.res`, not the fake worker's cell shape. Its `structure`
  is an HTML-like token stream, and its `bbox` entries may be flat 8-number
  quadrilateral arrays. The DocTruth SLANeXT adapter must normalize that shape
  into row/column cells before Java can see table evidence.
- `kind: human-labeled` is necessary but insufficient for a parser accuracy
  claim. A separate `qualityProfile: parser-accuracy` gate now forces declared
  coverage tags and minimum case counts before a manifest can load. This keeps
  small public fixtures useful for plumbing while preventing them from being
  mistaken for broad accuracy evidence.
- Real model smokes need Python isolation by model family. RT-DETR/TATR use the
  ONNXRuntime worker available in the default Python environment, while SLANeXT
  needs a PaddleOCR/Paddle environment. Running the entire suite with the
  PaddleOCR venv first broke ONNXRuntime import. `DOCTRUTH_SLANEXT_PYTHON`
  now isolates only the SLANeXT step.
- Release CI needs both system and Python dependencies for real model gates:
  `poppler-utils` for rendered PDF pages, ONNXRuntime/Pillow/Numpy for
  RT-DETR/TATR, and PaddleOCR/Paddle for SLANeXT. Normal PR CI should exercise
  the suite's skip path, while release tags run the heavy real suite.
- Keep release model-smoke Python dependencies pinned. The verified local set is
  ONNXRuntime 1.26.0 for RT-DETR/TATR and PaddleOCR 3.7.0 with PaddlePaddle
  3.3.1 for SLANeXT; PaddleOCR pulls NumPy below 2.4, so the release workflow
  pins `numpy<2.4`.
- Human-labeled benchmark corpora need their own manifest semantics; otherwise
  generated fixtures can be mistaken for accuracy evidence. `kind:
  human-labeled` now requires label-set version, reviewer, review date, and
  explicit required metrics with thresholds. CLI JSON carries this metadata so
  CI/reporting can distinguish generated regression gates from human-labeled
  accuracy runs.
- The public W3C remote-PDF smoke now exercises that `kind: human-labeled`
  metadata path through a real downloaded PDF and CLI JSON assertions. This is
  a useful release gate for corpus plumbing, but it is not broad enough to
  support real-world parser accuracy claims.
- A generated parser-accuracy seed corpus is useful as a CI gate for manifest
  coverage and metric plumbing, but it must be described as a seed. Because its
  expected labels are produced from current parser output, it cannot be used as
  evidence of real-world parser accuracy.
- Parser-accuracy benchmark reports need case-level traceability, not only
  corpus-level label metadata. `labelId` links each metric row back to the
  reviewed label set, while `tags` show which required coverage bucket the case
  satisfied. Without those fields in CLI JSON, a passing release report would
  be hard to audit after the broad real-world corpus is populated.
- Parser-accuracy reports also need an explicit review posture. A generated
  seed corpus is useful for CI contract coverage, but `reviewType:
  generated-seed` must be machine-visible so it cannot be mistaken for
  `human-reviewed` real-world accuracy evidence.
- The Rust-first correction changes where new parser-quality work should land.
  Java still owns a large compatibility surface today, but new corpus gates
  should be added to `runtime/doctruth-runtime` first. The new Rust
  `benchmark_corpus` command proves manifest loading, label metadata,
  `labelId`/`tags`, tag coverage, and basic metrics without the Java CLI.
  This is a migration of gate ownership, not proof of final parser quality.
- The model-worker migration should also happen at the Rust boundary first.
  `doctruth-runtime parse_pdf` now owns the configured worker handoff for
  model-assisted presets and treats worker bad JSON/process failure as
  `MODEL_WORKER_FAILED`. This makes Rust the control point for future
  RT-DETR/TATR/SLANeXT/OCR execution, while still leaving actual model
  execution outside the Rust binary for now.
- Rust parser-accuracy corpora must be able to exercise model-assisted presets,
  not only the default text-layer parser. Case-level `preset` now routes a
  corpus case through the same Rust `parse_pdf` model-worker handoff, so future
  broad labeled corpora can include table/layout/OCR cases under the Rust
  runtime gate.
- The PRD's intended final architecture is Rust core, not Java/PDFBox core with
  optional Rust sidecar. Java is the stable enterprise-facing SDK/CLI/API and
  compatibility shell. Any future parser-quality capability that exists only in
  Java should be treated as incomplete until the Rust runtime owns it and Java
  merely exposes or adapts it.
- MinerU's output layering is worth adopting as a product contract, but not as
  a copied schema. The useful split is final Markdown for humans/LLMs,
  flat `content_blocks.json` for reading-order ingestion, deep
  `parse_trace.json` for page/block/line/span evidence and parser QA, visual
  layout/span debug artifacts, and DocTruth's own `trust.json` as the canonical
  evidence/replay contract. Current DocTruth has `TrustDocument`, `TrustUnit`,
  source maps, tables, and evidence spans, but it does not yet expose the full
  intermediate page -> block -> line -> span trace as a first-class output.
  Future layered-output work should land at the Rust runtime boundary first.
- The first Rust-owned layered output slice now exposes `contentBlocks` and
  `parseTrace` directly in `parse_pdf` output. These are derived from the same
  Rust `body.units` and `body.pages` observations as `TrustDocument`, so clean
  content blocks and trace spans can be linked back to `unitId`,
  `sourceObjectId`, and `evidenceSpanId`. This closes the first contract gap,
  but CLI file profiles such as `--format content_blocks` /
  `--format parse_trace` and visual layout/span debug artifacts remain pending.
- CLI layered output profiles now exist for both Java/PDFBox-derived
  `TrustDocument`s and Rust sidecar-derived `TrustDocument`s:
  `doctruth parse --format content_blocks` writes
  `doctruth.content_blocks.v1`, and `--format parse_trace` writes
  `doctruth.parse_trace.v1`. The profile is currently a deterministic
  `TrustDocument` projection, so it is stable and works across backends, but it
  does not yet preserve extra raw parser observations beyond the current
  `TrustDocument` units/pages.
- The first visual trace artifact slice is now package-level rather than a new
  parser command: `doctruth review-package` writes `content_blocks.json`,
  `parse_trace.json`, `layout-debug.html`, and `span-debug.html` alongside
  `trust-document.json`, `review.html`, and page PNGs. The debug HTML carries
  `data-trace-block-id`, `data-trace-line-id`, and `data-trace-span-id`
  attributes that are verified against `parse_trace.json`. This closes the
  Phase 0A visual trace contract for review-package QA, but it is still a
  deterministic `TrustDocument` projection and not proof of broad
  multi-layout/parser accuracy.
- The Java `parse_trace` profile was aligned with Rust's `pageSize` shape
  (`width`/`height`, not bbox fields), and sidecar capabilities now advertise
  `content_blocks` and `parse_trace`. Raw Rust-sidecar layered products are
  still not preserved through `TrustDocumentJson`; Java can re-derive stable
  layered outputs from `TrustDocument`, but extra raw runtime observations remain
  a future contract gap.
- 2026-06-13 documentation/status audit result:
  - Complete: MinerU-style `content_blocks.json` / `parse_trace.json` contract,
    Rust `parse_pdf` layered output, CLI `--format content_blocks` /
    `--format parse_trace`, and review-package `layout-debug.html` /
    `span-debug.html` trace-id artifacts.
  - Complete: Docling-style v1 `TrustDocument`/`TrustUnit` contract, lossless
    JSON plus lossy Markdown/HTML/plain/compact outputs, provenance/source-map
    contracts, parser backend separation, and v1 chunk/evidence/MCP surfaces.
  - Complete: local model cache/manifest handoff, SHA verification, runtime
    hints, doctor/cache warm contracts, configured model-worker protocol, and
    Rust runtime worker handoff.
  - Complete: public RT-DETR and TATR artifact entrypoint through
    `doctruth-runtime parse_pdf` via `scripts/smoke-doctruth-runtime-real-model-artifacts.sh`.
  - Partial: Rust-core ownership. Packaged CLI can be Rust-first and Rust owns
    `parse_pdf`/`benchmark_corpus`/worker handoff, but direct Java SDK/JAR paths
    still rely on explicit/configured runtime selection and Java/PDFBox remains
    active fallback/oracle.
  - Complete for generated real-route smokes, partial for broad quality:
    SLANeXT/OCR Rust ownership now includes Rust worker routing, normalized
    TrustDocument envelopes, generated real RapidOCR + ONNXRuntime through the
    Rust runtime path, and generated real PaddleOCR/SLANeXT through the Rust
    runtime path.
  - Complete for v1 model-execution architecture: ADR 0011 accepts external
    local JSON workers as the heavy model execution boundary while Rust owns
    orchestration, manifest/cache validation, request envelopes, response
    normalization, benchmark execution, and audit propagation.
  - Partial: parser quality. Generated fixtures, remote W3C plumbing, seed
    parser-accuracy manifests, and recorded crash/regression corpus exist, but
    broad human-reviewed multi-layout/table/OCR/bbox/source-map accuracy is not
    populated.
  - Missing: broad human-reviewed parser-accuracy corpus, labeled scanned-PDF
    OCR corpus, and labeled SLANeXT/table accuracy corpus.
- Rust `benchmark_corpus` is no longer only a manifest/metadata plumbing gate.
  It now reads expected `TrustDocument` JSON labels and can threshold
  `bbox_iou`, `evidence_span_accuracy`, `table_cell_f1`, and
  `ocr_text_accuracy` in addition to `reading_order_f1`,
  `quote_anchor_accuracy`, and `bbox_coverage`. This closes a Rust-side metric
  parity gap for future broad labeled corpora, but it still depends on those
  corpora being populated.
- Human-reviewed parser-accuracy corpus manifests now have an explicit scale
  gate: `reviewType: human-reviewed` requires `labeling.minTotalCases` and the
  loader rejects reports whose case count is below that value. Generated seed
  corpora are intentionally exempt so they can stay small CI plumbing gates.
  This prevents a one-fixture human-reviewed run from being presented as broad
  parser accuracy evidence.
- Human-reviewed parser-accuracy labels are now source-byte pinned:
  `reviewType: human-reviewed` requires every case to carry `sourceSha256`, and
  both Java and Rust reject missing pins. Java now verifies local `source`
  files against `sourceSha256` as well as remote `sourceUrl` cache entries;
  Rust already verified mismatches and now also requires the pin for
  human-reviewed parser-accuracy manifests. Generated seed corpora remain
  exempt because they are plumbing checks, not accuracy evidence.
- Human-reviewed parser-accuracy manifests now also require the core metric
  set: `reading_order_f1`, `quote_anchor_accuracy`, `bbox_coverage`,
  `bbox_iou`, `evidence_span_accuracy`, `table_cell_f1`, and
  `ocr_text_accuracy`. Java and Rust both reject incomplete
  `requiredMetrics` for `reviewType: human-reviewed`. Generated seed corpora
  remain exempt, and generated contract fixtures may use conservative
  thresholds; real parser-quality claims still require broad human-reviewed
  corpus thresholds and recorded reports.
- Human-reviewed parser-accuracy manifests now also require the core coverage
  tags: `multi-layout`, `table`, `ocr`, `bbox`, and `source-map`. Java and
  Rust both reject incomplete `requiredTags` for `reviewType: human-reviewed`.
  A generated contract case may carry all tags to prove the manifest/reporting
  path, but that remains a plumbing proof; real parser-quality claims still
  require separate broad fixtures under those categories.
- `doctruth benchmark-corpus --report-out <report.json>` now writes an
  auditable parser benchmark report artifact with
  `reportFormat: doctruth.parser-benchmark.report.v1`, the resolved manifest
  path, label/review/profile metadata, aggregate metrics, and per-case
  label/tag/metric evidence. This closes the recorded-report artifact contract
  for future parser-accuracy runs, but it does not create or validate the broad
  human-reviewed corpus itself.
- Rust `doctruth-runtime` now has the same recorded-report artifact capability
  for `benchmark_corpus` through request field `report_path`. The runtime smoke
  verifies the artifact separately from stdout. This keeps future
  human-reviewed Rust corpus runs archivable without depending on shell
  redirection.
- Recorded benchmark reports now include per-case `sourceSha256` in both Java
  CLI and Rust runtime paths. This matters because a human-reviewed
  parser-accuracy report must prove not only which labels and metrics were used,
  but which exact PDF bytes those labels were attached to.
- Recorded benchmark reports now also include top-level `manifestSha256` in
  both Java CLI and Rust runtime paths. This pins the exact manifest content,
  including label metadata, thresholds, case list, and required coverage, to the
  archived report.
- Recorded benchmark reports now copy `minimums` and `maximums` into the report
  body in both Java CLI and Rust runtime paths. This makes the artifact
  self-contained about which pass/fail thresholds were applied, while
  `manifestSha256` still pins the full original manifest.
- Recorded benchmark reports now include actual `caseCount` and `casesPerTag`
  in both Java CLI and Rust runtime paths. This separates coverage actually run
  from coverage merely required by the manifest, which is necessary before
  archived broad parser-accuracy reports can be treated as evidence.
- `doctruth verify-benchmark-report <report.json>` now verifies recorded Java
  parser benchmark reports without rerunning the parser. It checks report
  format, pass status, manifest hash, copied thresholds, coverage counts, and
  source-hash pins. The benchmark smoke covers both valid report verification
  and tampered coverage failure.
- The report verifier now also checks copied coverage requirements:
  `minCasesPerTag` and `minTotalCases`. It expands manifest shorthand
  `minCasesPerTag: 1` across `requiredTags` before comparison, then verifies
  the actual report cases satisfy those thresholds.
- Rust `doctruth-runtime` now has verifier parity for recorded benchmark
  reports: it writes expanded `minCasesPerTag` and accepts
  `verify_benchmark_report` with `report_path`, validating manifest hash,
  copied thresholds, coverage counts, coverage requirements, and source pins
  without the Java CLI.
- Rust `benchmark_corpus` now enforces manifest `maximums` in addition to
  `minimums`. Before this change, lower-is-better thresholds were copied into
  reports but not applied, so Rust could emit `passed: true` even when a
  `maximums` gate was violated.
- Recorded report verifiers now re-check metric values against copied
  `minimums` and `maximums`. Java and Rust both prefer aggregate report metrics
  when present and fall back to per-case metrics when a thresholded metric is
  not emitted in the aggregate block.
- Recorded report verifiers now also check aggregate/case metric consistency.
  Java recomputes the runner's derived aggregate metrics such as
  `parser_latency_p50`, `parser_latency_p95`, and
  `compact_llm_size_reduction_min`; Rust recomputes same-name aggregate metrics
  from case metrics using the runtime's rounded-average semantics.
- Java recorded report verification now treats `casesPerTag` as an exact
  coverage map. Forged extra tag keys are rejected with `casesPerTag mismatch`,
  matching the Rust verifier's stricter behavior.
- OCR preset selection is now runtime-first when `doctruth.runtime.command` or
  `DOCTRUTH_RUNTIME_COMMAND` is configured. Java/PDFBox OCR remains the fallback
  path when no Rust runtime is available, but OCR no longer bypasses the
  configured Rust sidecar.
- Runtime status docs now describe `doctruth-runtime` as an active
  Rust-controlled runtime with parse, benchmark, verify, doctor, model-worker,
  layered-output, and real-route smoke coverage, while still calling out that
  heavy models are external-worker/opt-in and broad human-reviewed accuracy
  proof is pending.
- Broad human-reviewed corpus population is now intentionally final-stage. The
  immediate engineering target is to complete Rust-first runtime and fallback
  boundaries first; a future review workstation can accumulate approved/corrected
  labels for real accuracy measurement.
- The SDK now has a path-first TrustDocument parser entrypoint:
  `DocTruth.withProvider(provider).parsePdf(path).withParser(preset)`.
  `ParserBackendMode.AUTO` prefers a configured Rust runtime,
  `ParserBackendMode.PDFBOX` forces Java/PDFBox fallback/oracle behavior, and
  `ParserBackendMode.SIDECAR` fails unless a runtime is configured.
- Architecture correction: Java/PDFBox is not a parser core. The DocTruth
  parser core should mirror the Kreuzberg-style shape: Rust runtime as core,
  `pdf_oxide` as the Rust PDF text/page extraction backend, model workers for
  layout/table/OCR enhancements, and Java only as SDK/CLI wrapper,
  sidecar-client packaging, legacy compatibility, and regression oracle.
- Current Rust runtime now uses `pdf_oxide` for column-aware text-layer page
  extraction, text-span bbox evidence, page MediaBox geometry, and default
  rendered PNG page image hashes, and no longer depends on `pdf-extract` or a
  default `pdftoppm` renderer. It still uses `lopdf` for table/debug extraction,
  so the backend status is `PARTIAL`, not complete.
- OpenDataLoader Bench should be treated as DocTruth's parser-quality
  foundation because evidence quality is capped by parser quality. It should
  feed external parser-quality metrics such as reading-order NID, table TEDS,
  heading MHS, and speed into DocTruth benchmark reports. It should not replace
  DocTruth's evidence/replay benchmark because DocTruth still needs
  bbox/source-map/evidence-span/audit-grade/replay-integrity checks that
  OpenDataLoader Bench does not cover.
- The intended benchmark composition is now:
  `OpenDataLoader Bench = parser substrate quality` and
  `DocTruth Bench = evidence, replay, and audit quality`. A parser-quality
  failure should prevent audit-grade promotion even if DocTruth can still emit
  reviewable evidence spans.
- Review packages now use the exported page PNG manifest as the page-image hash
  source of truth. `trust-document.json`, `page-images.json`, and `review.html`
  are generated from the same rendered page list so a reviewer can anchor bbox
  evidence to the exact PNG bytes shipped in the package.
- Smoke coverage has been reconciled with the Rust-default parser path. CLI
  model-worker smokes now expect `rust-sidecar+model-worker` as the outer
  parser backend; worker-native `pdfbox+model-worker` strings remain only as
  internal worker provenance where applicable.
- The W3C dummy real-PDF smoke is now labeled as a text-layer evidence fixture,
  not a fake table fixture. Table quality remains covered by dedicated table
  and TATR/SLANeXT smokes.

## 2026-06-14 CLI Shorthand Rust-Default Gap

- `doctruth parse --json` and `--markdown` still pointed at legacy
  `ParsedDocument` output even after the rest of the CLI/SDK/MCP paths had
  moved to Rust TrustDocument by default. That meant a user could request a
  common parse output and silently bypass the Rust runtime.
- The shorthand flags now map to `TRUST_JSON` and `TRUST_MARKDOWN`.
  Legacy `ParsedDocument` output remains available only as an explicit
  Java/PDFBox oracle/compatibility run:
  `--backend pdfbox --format legacy-json|legacy-markdown`.
- Focused verification passed:
  `mvn -q -Dtest=DocTruthCliTest,TrustDocumentCliOutputProfileTest test`;
  `mvn -q -Dtest=DocTruthCliMcpTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`
  with 1046 unit tests passing, recorded PDF corpus
  `383 total / 379 success / 4 malformed trailer failures`, CSV fixture
  `57/57`, and coverage checks passing;
  `git diff --check`.

## 2026-06-14 OpenDataLoader Bench Adapter Shape

- OpenDataLoader Bench should be consumed as a parser-quality benchmark layer:
  DocTruth exports Rust-runtime predictions into a compatible artifact shape,
  imports its `evaluation.json` metrics, and records those metrics under
  `external_metrics` in DocTruth benchmark reports.
- The adapter must not replace `TrustDocument`, source maps, replay packages,
  or DocTruth's own evidence metrics. OpenDataLoader-style NID/TEDS/MHS/speed
  answers whether the parser substrate is good enough; DocTruth metrics answer
  whether the resulting evidence is citeable, source-hash-bound, replayable,
  and audit-grade.
- Future implementation should avoid running non-permissive benchmark engines
  in DocTruth CI. Use synthetic local fixtures and checked-in evaluation JSON
  for RED tests, then optionally compare external published prediction
  artifacts outside the default OSS gate.
