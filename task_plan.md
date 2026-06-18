# DocTruth v1 Parser Runtime TDD Plan

Goal: implement and verify the requirements in `docs/pdf-parser-runtime-prd.md`
with TDD, full unit coverage for the new contracts, and smoke coverage for the
developer/runtime path. The parser/runtime ownership is Rust-core by contract:
`runtime/doctruth-runtime` is the parser core, while Java remains only the
SDK/CLI/API compatibility wrapper and packaging layer around that core.

Branch: `feat/v1-trust-document-runtime-tdd`

## Scope

Implement the v1 contract in incremental, test-first slices:

1. `TrustDocument` and `TrustUnit` canonical model.
2. Evidence-bearing output contracts: JSON full/evidence, clean Markdown,
   anchored Markdown, compact LLM wire, HTML review source-map hooks,
   `content_blocks.json`, and `parse_trace.json`.
3. Rust parser/runtime core ownership, with Java PDFBox retained only as a
   legacy migration surface and differential compatibility oracle.
4. Warning and audit-gate semantics for parser uncertainty.
5. CLI/smoke path proving an agent or developer can parse, render, and inspect
   evidence output.

## Original Non-Scope For The Initial Java Contract Slice

- Downloading or running layout/table/OCR models.
- Copying Kreuzberg or Docling implementation code.
- Replacing all existing `ParsedDocument` APIs immediately.

## Current Rust-First Continuation Boundary

The initial Java contract slice is not the product end state. Treat Rust core
ownership as the acceptance target, not a future nice-to-have:

```text
Rust owns parser/runtime behavior.
Java owns SDK/CLI/API compatibility and packaging only.
PDFBox is legacy/differential oracle only, not a fallback product path.
```

All new parser-quality, corpus, OCR, layout, table, model-cache, model-execution,
warning, audit-grade, and evidence-reconciliation behavior must be implemented
and verified in `runtime/doctruth-runtime` first. Java changes are aligned only
when they expose, package, adapt, or compatibility-test Rust behavior.

MinerU-style layered parser products are now part of the PRD contract, but they
must be implemented as DocTruth-owned contracts rather than copied schemas:

```text
markdown_clean       clean final rendering for humans/LLMs
content_blocks.json  flat reading-order block stream for ingestion/cleanup/RAG
parse_trace.json     page -> block -> line -> span intermediate evidence layer
trust.json           canonical evidence/replay contract
audit/review package compliance and visual QA layer
```

The acceptance target is that `content_blocks.json` and `parse_trace.json` are
derived from the same Rust-owned parser observations as `TrustDocument`, and
that evidence spans can be traced back to parse trace spans.

OpenDataLoader PDF is now an explicit parser-algorithm reference for the Rust
core because its v2+ code is Apache-2.0 and its XY-Cut++ reading-order
implementation has concrete tests. This does not change the canonical contract:
OpenDataLoader ideas feed the Rust geometry/filter/table layer, then DocTruth
normalizes the output into `TrustDocument`, `content_blocks.json`, and
`parse_trace.json`.

```text
Kreuzberg      -> Rust runtime/model/cache/worker shape
Docling        -> unified lossless document model and lossy exports
MinerU         -> layered markdown/content-list/middle/debug products
OpenDataLoader -> XY-Cut++ geometry, structure-tree preference, safety filters
DocTruth       -> evidence, citations, warnings, audit gates, replay contracts
```

The current PDFBox replacement is not "one Rust crate that equals PDFBox." The
default Rust PDF substrate is `pdf_oxide` for text-layer extraction, page
geometry, rendering, page-image hashes, content-stream safety checks, line-table
heuristics, and bbox evidence. `lopdf` is no longer a `doctruth-runtime`
dependency or default parser-core component.

Do not mark the PRD goal complete while any of these are still Java-only or
while Java/PDFBox is still described as a normal default/fallback path:

```text
default parser core
model/cache verification
layout/table/OCR execution path
benchmark-corpus ownership
audit-grade parser decisions
evidence reconciliation semantics
real parser-quality corpus gates
```

## Phases

| Phase | Status | Deliverable | Verification |
| --- | --- | --- | --- |
| 0. Current-state audit | complete | Map existing parser/output/CLI code and dirty worktree boundary | `findings.md`, `progress.md` |
| 1. Contract red tests | complete | Tests for `TrustDocument`, `TrustUnit`, rendered outputs, warnings, parser backend, chunking/source-map, HTML passthrough, reading order, table cells | Focused failing tests before implementation |
| 2. Core model implementation | complete | Public immutable records/enums for v1 document contract | `TrustDocumentContractTest`, `TrustUnitTest` pass |
| 3. Adapter from current parser | complete | Convert existing `ParsedDocument` into `TrustDocument` baseline | `TrustDocumentAdapterTest` passes |
| 4. Renderers and compact wire | complete | Deterministic JSON/Markdown/compact render contracts | `TrustDocumentRenderedOutputTest` passes |
| 5. Audit gate and warnings | complete | Severe warning taxonomy and audit-grade blocking | `TrustDocumentAuditGateTest` passes |
| 6. CLI/smoke | complete | Parse/render smoke using local fixtures | `TrustDocumentLocalSmokeTest` passes |
| 7. CLI v1 output profiles | complete | `doctruth parse --format ... --profile ...` renders TrustDocument JSON/Markdown/JSONL/audit/compact and source-map sidecars | `TrustDocumentCliOutputProfileTest` passes |
| 8. Doctor runtime visibility | complete | `doctor` reports parser backend, model cache, memory estimate, and `doctor models` | `DocTruthCliDoctorCompletionTest` passes |
| 9. SDK parser and runtime contracts | complete | `TrustDocumentParser`, `DocTruthDocument.withParser(ParserPreset).parse()`, model-cache SHA verification, benchmark metric runner, writer-based render paths | focused parser/runtime tests pass |
| 10. Sidecar protocol adapter | complete | `SidecarParserBackend` sends JSON stdin, reads `TrustDocument` JSON stdout, and maps crash/bad JSON to structured `ParseException` | `SidecarParserBackendTest` passes |
| 11. Full verification after sidecar adapter | complete | Complete unit test suite and diff checks after sidecar adapter | `mvn test`, `git diff --check` |
| 12. CLI sidecar backend | complete | `doctruth parse --backend sidecar --runtime <path>` uses sidecar protocol for TrustDocument outputs | `TrustDocumentCliOutputProfileTest` passes |
| 13. Full verification after CLI sidecar | complete | Complete unit test suite and diff checks after CLI sidecar wiring | `mvn test`, `git diff --check` |
| 14. Rust runtime protocol RED | complete | Add cargo tests for the local `doctruth-runtime` protocol before implementation | `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` failed for missing runtime binary |
| 15. Rust runtime protocol MVP | complete | Minimal local sidecar binary with `doctor` and `parse_pdf` protocol responses | Cargo tests pass; runtime smoke passes |
| 16. Full verification after Rust runtime MVP | complete | Complete Maven + Cargo verification and diff checks | `cargo test`, `mvn test`, `git diff --check` |
| 17. Rust text-layer extraction RED | complete | Require real PDF file input to produce citeable text units and missing files to fail | `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` failed on unimplemented extraction |
| 18. Rust text-layer extraction MVP | complete | Extract text from a real text-layer PDF into a `TrustDocument` unit without severe warnings | Cargo protocol tests and runtime smoke pass |
| 19. Full verification after Rust text extraction | complete | Complete Maven + Cargo verification and diff checks | `cargo test`, smoke, `mvn test`, `git diff --check` |
| 20. Rust page-level extraction RED | complete | Require multi-page PDFs to emit page-level pages and units with stable reading order | Cargo protocol test failed at `pageCount=1` |
| 21. Rust page-level extraction MVP | complete | Use page-level text extraction to emit one page entry per page and one unit per text-bearing page | `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` |
| 22. Full verification after page-level extraction | complete | Complete Maven + Cargo verification and diff checks | `cargo test`, smoke, `mvn test`, `git diff --check` |
| 23. Parser benchmark threshold gate | complete | `ParserBenchmarkRunner.requireMinimums(...)` fails below configured acceptance thresholds with case/metric context | `ParserBenchmarkRunnerTest` red, then pass |
| 24. Full verification after benchmark gate | complete | Complete Maven + Cargo verification and diff checks after threshold gate and public API snapshot update | `cargo fmt --check`, `cargo test`, smoke, `mvn test`, `git diff --check` |
| 25. Expected-document benchmark metrics | complete | Benchmark cases can carry expected `TrustDocument`; runner reports `bbox_iou` and `table_cell_f1` for layout/table quality gates | `ParserBenchmarkRunnerTest` red, then pass |
| 26. Full verification after expected-document metrics | complete | Public API, architecture, Maven full suite, and whitespace checks after benchmark contract expansion | `PublicApiSnapshotTest`, `ArchitectureContractTest`, `mvn test`, `git diff --check` |
| 27. Rust line-level extraction RED/MVP | complete | Text-layer runtime emits stable `LINE_SPAN` units per citeable line instead of one coarse page block | Cargo protocol test red, then pass |
| 28. Full verification after Rust line-level extraction | complete | Runtime format, Cargo protocol/full tests, smoke, Java sidecar/CLI focused tests, Maven full suite, and diff checks | `cargo fmt --check`, `cargo test`, smoke, focused Maven, `mvn test`, `git diff --check` |
| 29. End-to-end CLI sidecar smoke | complete | Shaded Java CLI calls the local Rust runtime on a generated PDF and renders JSON plus Markdown/source-map outputs | `sh scripts/smoke-doctruth-cli-sidecar.sh` |
| 30. Full verification after CLI sidecar smoke | complete | Complete Cargo, runtime smoke, CLI sidecar smoke, Maven full suite, and diff checks after smoke script addition | `cargo fmt --check`, `cargo test`, smoke scripts, `mvn test`, `git diff --check` |
| 31. Real PDF benchmark fixture RED/MVP | complete | Benchmark cases can parse real PDF fixtures and gate reading order, quote anchors, and bbox coverage | `ParserBenchmarkRunnerTest` red, then pass |
| 32. Full verification after real PDF benchmark fixture | complete | Public API, architecture, Maven full suite, and whitespace checks after benchmark fixture contract expansion | `PublicApiSnapshotTest`, `ArchitectureContractTest`, `mvn test`, `git diff --check` |
| 33. Real PDF expected-bbox benchmark RED/MVP | complete | Benchmark cases can parse real PDF fixtures and compare output against expected bbox fixtures with `bbox_iou` thresholds | `ParserBenchmarkRunnerTest` red, then pass |
| 34. Full verification after expected-bbox fixture | complete | Public API, architecture, Maven full suite, Cargo runtime tests, smoke scripts, and whitespace checks after expected-bbox benchmark API expansion | `PublicApiSnapshotTest`, `ArchitectureContractTest`, `mvn test`, `cargo test`, smoke scripts, `git diff --check` |
| 35. Real PDF bordered-table benchmark RED/MVP | complete | Generated real PDFs with bordered tables parse into `TableSection`/`TrustTable` cells and pass `table_cell_f1` gates | `ParserBenchmarkRunnerTest#benchmarkCanCompareRealPdfAgainstExpectedTableCells` red, then pass |
| 36. Full verification after bordered-table fixture | complete | Related parser tests, Maven full suite, Cargo runtime tests, smoke scripts, and whitespace checks after PDF table extraction | focused Maven parser tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 37. PDF table duplicate suppression RED/MVP | complete | Text blocks inside detected bordered-table regions are suppressed so downstream Markdown/LLM consumers do not see duplicated table cell text | `ParserBenchmarkRunnerTest#realPdfTableExtractionSuppressesDuplicateTextBlocks` red, then pass |
| 38. Full verification after table duplicate suppression | complete | Parser-focused tests, Maven full suite, runtime tests, smoke scripts, and whitespace checks after table-region filtering | focused Maven parser tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 39. Table-region IoU benchmark RED/MVP | complete | `TableSection` carries optional region bbox, `TrustTable` preserves it, and benchmark cases report `table_region_iou` for real bordered PDF tables | `ParserBenchmarkRunnerTest#benchmarkCanCompareRealPdfAgainstExpectedTableRegion` red, then pass |
| 40. Full verification after table-region bbox | complete | Public API snapshot, architecture, parser/table tests, Maven full suite, runtime tests, smoke scripts, and whitespace checks after `TableSection` bbox contract change | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 41. Table-cell bbox RED/MVP | complete | `TableSection` carries per-cell regions, `TrustTableCell` preserves them, and `TABLE_CELL` units expose cell bboxes for real bordered PDF tables | `ParserBenchmarkRunnerTest#realPdfBorderedTableExtractionPreservesCellBoundingBoxes` red, then pass |
| 42. Full verification after table-cell bbox | complete | Public API snapshot, parser/table tests, Maven full suite, runtime tests, smoke scripts, and whitespace checks after `TableCellRegion` contract addition | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 43. GFM table rendering/source-map RED/MVP | complete | Clean Markdown renders structured tables as GFM pipe tables, and Markdown source maps anchor each rendered table cell to its unit/evidence ids | `TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest` red, then pass |
| 44. Full verification after GFM table rendering | complete | Renderer/source-map/CLI/parser tests, Maven full suite, runtime tests, smoke scripts, and whitespace checks after Markdown table rendering changes | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 45. Rust bordered-table runtime RED/MVP | complete | Rust sidecar parses a generated bordered-grid PDF into `TrustTable`, `TrustTableCell`, and `TABLE_CELL` units with bboxes | `cargo test ... parse_pdf_emits_table_cells_for_bordered_grid_pdf` red, then pass |
| 46. Full verification after Rust bordered-table runtime | complete | Cargo full suite, runtime smoke, CLI sidecar smoke, Maven full suite, dependency feature check, and whitespace checks after Rust table extraction | `cargo test`, smoke scripts, `mvn test`, `cargo tree`, `git diff --check` |
| 47. Rust positioned text bbox RED/MVP | complete | Rust sidecar uses content-stream text positions to emit non-page-fallback bboxes for simple `LINE_SPAN` units | `cargo test ... parse_pdf_emits_positioned_text_bboxes_when_content_stream_positions_are_available` red, then pass |
| 48. Full verification after Rust positioned text bboxes | complete | Cargo full suite, runtime smoke, CLI sidecar smoke, Maven full suite, dependency feature check, and whitespace checks after positioned text bbox extraction | `cargo test`, smoke scripts, `mvn test`, `cargo tree`, `git diff --check` |
| 49. HTML review table/cell anchors RED/MVP | complete | `html_review` emits semantic table and cell nodes with table ids, cell ids, unit/evidence links, and normalized bbox attributes | `TrustDocumentSourceMapContractTest#reviewHtmlCarriesTableAndCellAnchors` red, then pass |
| 50. Full verification after HTML table/cell anchors | complete | Renderer/source-map focused tests, Maven full suite, Cargo runtime tests, smoke scripts, and whitespace checks after HTML review table rendering | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 51. Streaming writer paths RED/MVP | complete | Clean Markdown and JSONL writer APIs write incrementally to caller-owned writers instead of one aggregate rendered string | `TrustDocumentStreamingRenderContractTest#writerPathsDoNotWriteWholeDocumentAtOnce` red, then pass |
| 52. Full verification after streaming writer paths | complete | Streaming/renderer/CLI focused tests, Maven full suite, Cargo runtime tests, smoke scripts, and whitespace checks after writer-path changes | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 53. Source-map hash binding RED/MVP | complete | Clean Markdown source-map output carries source hash and rendered content hash in SDK and CLI sidecar JSON | `TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest` red, then pass |
| 54. Full verification after source-map hash binding | complete | Source-map/CLI focused tests, public API snapshot, Maven full suite, Cargo runtime tests, smoke scripts, and whitespace checks after source-map record expansion | focused Maven tests, public API checks, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 55. Anchored Markdown bbox RED/MVP | complete | `markdown_anchored` includes normalized bbox metadata when a citeable unit has a bbox, while clean Markdown remains metadata-free | `TrustDocumentRenderedOutputTest#markdownAnchoredIncludesBboxMetadata` red, then pass |
| 56. Full verification after anchored Markdown bbox | complete | Rendered-output focused tests, Maven full suite, Cargo runtime tests, smoke scripts, and whitespace checks after anchor metadata change | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 57. Markdown review unit warnings RED/MVP | complete | `markdown_review` includes unit-scoped warnings as well as parser warnings for replay/debugging | `TrustDocumentRenderedOutputTest#markdownReviewIncludesParserAndUnitWarnings` red, then pass |
| 58. Full verification after markdown review warnings | complete | Rendered-output focused tests, Maven full suite, Cargo runtime tests, smoke scripts, and whitespace checks after review warning change | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 59. Plain text output profile RED/MVP | complete | `TrustDocument.toPlainText()`, CLI `--format plain`, and backend `plain_text` capabilities provide a clean text/table consumption view without Markdown or evidence syntax | rendered-output/CLI/capability tests red, then pass |
| 60. Full verification after plain text output | complete | Focused Java tests, public API snapshot, Maven full suite, Cargo runtime tests, runtime smoke, CLI sidecar smoke, and whitespace checks after plain output support | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 61. Source-map verification CLI RED/MVP | complete | `doctruth verify-source-map <rendered> <map.json> [--source <document>]` verifies rendered content hash and optional source hash | `TrustDocumentCliOutputProfileTest` red, then pass |
| 62. Full verification after source-map verification | complete | Focused CLI/completion tests, Maven full suite, Cargo runtime tests, runtime smoke, CLI sidecar smoke, and whitespace checks after verification command | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 63. Hashable audit JSON RED/MVP | complete | `TrustDocument` Audit JSON includes source, canonical document, and evidence hashes for replay/compliance package integrity | rendered-output/CLI tests red, then pass |
| 64. Full verification after hashable audit JSON | complete | Focused renderer/CLI tests, Maven full suite, Cargo runtime tests, runtime smoke, CLI sidecar smoke, and whitespace checks after audit hash fields | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 65. HTML review page surfaces RED/MVP | complete | `html_review` wraps units and tables in page containers with page number, page dimensions, text-layer availability, and page image hash metadata | `TrustDocumentSourceMapContractTest#reviewHtmlRendersPageSurfacesForOverlays` red, then pass |
| 66. Full verification after HTML page surfaces | complete | Focused renderer/CLI tests, Maven full suite, Cargo runtime tests, runtime smoke, CLI sidecar smoke, and whitespace checks after page-aware HTML review | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 67. Compact wire bbox metadata RED/MVP | complete | `compact_llm` appends optional `bbox=` metadata for citeable units with normalized bboxes while preserving existing compact prefixes | `TrustDocumentRenderedOutputTest#compactLlmPreservesBboxMetadataForCiteableUnits` red, then pass |
| 68. Full verification after compact bbox metadata | complete | Focused renderer/CLI tests, Maven full suite, Cargo runtime tests, runtime smoke, CLI sidecar compact smoke, and whitespace checks after compact bbox support | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 69. Compact streaming writer RED/MVP | complete | `TrustDocument.writeCompactLlm(Writer)` emits byte-identical compact output without one aggregate write, and CLI `--format compact --out` uses the writer path | `TrustDocumentStreamingRenderContractTest` red, then pass |
| 70. Full verification after compact streaming writer | complete | Focused streaming/CLI/API tests, Maven full suite, Cargo runtime tests, runtime smoke, CLI sidecar smoke, and whitespace checks after compact writer support | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 71. Compact source-map RED/MVP | complete | `TrustDocument.toCompactLlmWithSourceMap()` and CLI `--format compact --source-map` emit verifiable compact offset maps back to units/evidence spans | `TrustDocumentSourceMapContractTest`, `TrustDocumentCliOutputProfileTest` red, then pass |
| 72. Full verification after compact source-map | complete | Focused source-map/CLI/API tests, Maven full suite, Cargo runtime tests, runtime smoke, CLI sidecar compact source-map smoke, and whitespace checks | focused Maven tests, `mvn test`, `cargo fmt --check`, `cargo test`, smoke scripts, `git diff --check` |
| 73. Batch TDD execution rule | complete | PRD now instructs future goal loops to write all RED tests for one milestone before implementation, then verify focused/full/smoke gates | PRD heading/order check |
| 74. Signed TrustDocument audit package RED/MVP | complete | `TrustDocument` audit JSON can be passed through the shared `SignatureProvider` contract and written to package files | `TrustDocumentRenderedOutputTest` red, then focused green |
| 75. Full verification after signed audit package | complete | Java full suite, runtime smoke, CLI sidecar smoke, and whitespace checks after signed audit package PRD/API updates | `mvn test`, runtime smoke, CLI sidecar smoke, `git diff --check` |
| 76. Labeled benchmark corpus harness RED/MVP | complete | `ParserBenchmarkCorpus` loads manifest-relative source/expected Markdown/expected TrustDocument labels and reuses benchmark thresholds | `ParserBenchmarkCorpusTest` red, then focused green |
| 77. Benchmark corpus CLI/smoke RED/MVP | complete | `doctruth benchmark-corpus <manifest.json> [--json]` runs labeled corpus manifests and smoke verifies pass/fail thresholds | CLI tests red, then focused green and smoke pass |
| 78. Full verification after benchmark corpus CLI | complete | Full Java suite and whitespace checks after corpus CLI/docs/smoke updates | `mvn test`, `git diff --check` |
| 79. Compact corpus metric RED/MVP | complete | Benchmark results report compact size and round-trip/source-map health so `compact_llm` can be gated on corpus-level LLM efficiency and replayability | `ParserBenchmarkRunnerTest` red, then focused green |
| 80. Full verification after compact corpus metrics | complete | Focused benchmark/API tests, full Java suite, benchmark corpus smoke, and whitespace checks after compact metric docs/runner updates | focused tests, `mvn test`, smoke, `git diff --check` |
| 81. GFM Markdown escaping RED/MVP | complete | `markdown_clean` preserves fenced code blocks and links while escaping Markdown-sensitive table cell brackets/pipes/backslashes | `TrustDocumentRenderedOutputTest` red, then focused green |
| 82. Full verification after GFM Markdown escaping | complete | Renderer/source-map focused tests, full Java suite, CLI sidecar smoke, and whitespace checks after Markdown escaping update | focused tests, `mvn test`, smoke, `git diff --check` |
| 83. Audit replay verifier RED/MVP | complete | SDK and CLI verify Audit JSON against full TrustDocument JSON by checking doc/source/canonical/evidence integrity | SDK/CLI tests red, then focused green |
| 84. Full verification after audit replay verifier | complete | Public API snapshot, focused SDK/CLI/API tests, sidecar smoke, full Java suite, and whitespace checks after replay verifier wiring | focused tests, smoke, `mvn test`, `git diff --check` |
| 85. HTML review visual bbox overlay RED/MVP | complete | `html_review` emits page-scoped visual bbox overlay layers for units, tables, and cells in addition to semantic anchors | `TrustDocumentSourceMapContractTest` red, then focused green |
| 86. Full verification after HTML visual overlays | complete | Focused HTML/CLI/API tests, CLI sidecar smoke, full Java suite, and whitespace checks after overlay rendering | focused tests, smoke, `mvn test`, `git diff --check` |
| 87. Explicit strict parser preset API RED/MVP | complete | Static `TrustDocumentParser` entrypoints accept `ParserPreset` and record model-unavailable fallback as severe instead of silent heuristic success | `TrustDocumentParserApiContractTest` red, then focused green |
| 88. Full verification after strict preset API | complete | Public API snapshot, parser API, SDK preset, model policy, architecture checks, Java full suite, and whitespace checks after overload wiring | focused Maven tests, public API snapshot update, `mvn test`, `git diff --check` |
| 89. Per-model fallback warning RED/MVP | complete | Offline model-assisted presets emit one severe `model_unavailable_fallback` warning per missing required model with model identity and expected SHA | `ModelRuntimePolicyTest` red, then focused green |
| 90. Full verification after per-model fallback warnings | complete | Parser API, SDK preset, model policy, Java full suite, and whitespace checks after warning specificity change | focused tests, `mvn test`, `git diff --check` |
| 91. JSON full/audit writer APIs RED/MVP | complete | `TrustDocument.writeJsonFull(Writer)` and `writeAuditJson(Writer)` emit byte-identical output through caller-owned writers without one full-payload write | `TrustDocumentStreamingRenderContractTest` red, then focused green |
| 92. Full verification after JSON full/audit writer APIs | complete | Streaming/API focused tests, public API snapshot, Java full suite, and whitespace checks after writer API expansion | focused tests, public API snapshot update, `mvn test`, `git diff --check` |
| 93. CLI writer file output routing RED/MVP | complete | CLI `--out` routes clean Markdown, JSONL, compact LLM, JSON full, and Audit JSON through writer paths instead of one aggregate file string | `TrustDocumentCliWritersTest` red, then focused green |
| 94. Full verification after CLI writer routing | complete | CLI writer/profile/streaming/API tests, CLI sidecar smoke, Java full suite, and whitespace checks after file-output routing | focused tests, smoke, `mvn test`, `git diff --check` |
| 95. JSON evidence writer API RED/MVP | complete | `TrustDocument.writeJsonEvidence(Writer)` emits byte-identical evidence JSON without one full-payload write and CLI evidence output uses it | `TrustDocumentStreamingRenderContractTest` red, then focused green |
| 96. Full verification after JSON evidence writer API | complete | Streaming/CLI/API focused tests, public API snapshot, Java full suite, and whitespace checks after evidence writer expansion | focused tests, public API snapshot update, `mvn test`, `git diff --check` |
| 97. Remaining render writer APIs RED/MVP | complete | Anchored Markdown, review Markdown, plain text, and HTML review have byte-identical SDK writer APIs and CLI `--out` routing | `TrustDocumentStreamingRenderContractTest` red, then focused green |
| 98. Full verification after remaining render writers | complete | Streaming/CLI/API focused tests, public API snapshot, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 99. CLI stdout writer routing RED/MVP | complete | CLI TrustDocument stdout output uses writer paths instead of one aggregate rendered string | `TrustDocumentCliWritersTest` red, then focused green |
| 100. Full verification after stdout writer routing | complete | CLI writer/profile/streaming/API focused tests, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 101. Source-map sidecar writer routing RED/MVP | complete | CLI source-map sidecar files serialize through writer paths instead of one aggregate JSON string | `TrustDocumentCliWritersTest` red, then focused green |
| 102. Full verification after source-map sidecar writer routing | complete | CLI writer/profile/source-map/streaming/API focused tests, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 103. Hash input writer routing RED/MVP | complete | Canonical and evidence hash inputs use writer-backed digest paths instead of aggregate rendered JSON strings | `TrustDocumentStreamingRenderContractTest` red, then focused green |
| 104. Full verification after hash input writer routing | complete | Streaming/rendered-output/audit/parser/API focused tests, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 105. Benchmark byte-count writer routing RED/MVP | complete | Compact LLM size metrics count full JSON and compact bytes through writer-backed counters instead of aggregate strings | `ParserBenchmarkRunnerTest` red, then focused green |
| 106. Full verification after benchmark byte-count routing | complete | Benchmark/corpus/API focused tests, benchmark smoke, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 107. Source-map verifier streaming hash RED/MVP | complete | `verify-source-map` hashes rendered and source files through streaming reads instead of `readString`/`readAllBytes` | `TrustDocumentCliOutputProfileTest` red, then focused green |
| 108. Full verification after source-map verifier streaming hash | complete | CLI/source-map/API focused tests, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 109. CLI/SDK source hash streaming RED/MVP | complete | CLI parse and SDK path parse compute source hashes through streaming reads instead of `Files.readAllBytes` | parser/CLI contract tests red, then focused green |
| 110. Full verification after CLI/SDK source hash streaming | complete | Parser/CLI/sidecar/API focused tests, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 111. Source-map direct writer APIs RED/MVP | complete | SDK and CLI can write Markdown/compact source-map sidecars directly from `TrustDocument` without requiring callers to materialize `TrustRenderedDocument` | streaming/CLI writer tests red, then focused green |
| 112. Full verification after source-map direct writer APIs | complete | Streaming/CLI/source-map/API focused tests, CLI sidecar smoke, Java full suite, and whitespace checks | focused tests, snapshot update, smoke, `mvn test`, `git diff --check` |
| 113. PDFBox rendered page image hash RED/MVP | complete | PDFBox backend records rendered page dimensions and a SHA-256 PNG hash for each `TrustPage` instead of placeholder page metadata | `ParserBackendContractTest` red, then focused green |
| 114. Full verification after PDFBox page image hashes | complete | Backend/parser focused tests, CLI sidecar smoke, benchmark corpus smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 115. InputStream parser streaming copy RED/MVP | complete | SDK input-stream parser copies to a temporary PDF incrementally instead of calling `InputStream.readAllBytes()` | `TrustDocumentParserApiContractTest` red, then focused green |
| 116. Full verification after InputStream parser streaming copy | complete | Parser/backend/API focused tests, CLI sidecar smoke, benchmark corpus smoke, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 117. Rendered page image artifacts RED/MVP | complete | SDK and CLI can persist deterministic PDF page PNG artifacts plus a hash-bound manifest for review/replay tooling | `PdfPageImageRendererTest`, `DocTruthCliTest` red, then focused green |
| 118. Full verification after page image artifacts | complete | Page image smoke, CLI sidecar smoke, benchmark corpus smoke, focused API/CLI tests, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 119. Local review package RED/MVP | complete | CLI writes a static local review package containing `review.html`, `trust-document.json`, page PNG artifacts, and page image manifest | `DocTruthCliTest` red, then focused green |
| 120. Full verification after local review package | complete | Review package smoke, page image smoke, CLI sidecar smoke, benchmark corpus smoke, focused CLI/API tests, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 121. V1 OCR preset local-worker routing RED/MVP | complete | `ParserPreset.OCR` routes v1 `TrustDocumentParser`, `parse --format json`, and `review-package` through the configured local OCR worker and emits `OCR_REGION` units with `pdfbox+ocr` provenance | SDK/CLI tests red, then focused green |
| 122. Full verification after v1 OCR preset | complete | OCR preset smoke, review package smoke, CLI sidecar smoke, benchmark corpus smoke, OCR/API focused tests, Java full suite, and whitespace checks | focused tests, smoke, `mvn test`, `git diff --check` |
| 123. OCR confidence audit gate RED/MVP | complete | v1 OCR preset copies local worker confidence into `TrustUnitEvidence` and severe `ocr_low_confidence` blocks audit-grade below `0.85` | SDK red, then CLI/smoke green |
| 124. Verification after OCR confidence audit gate | complete | Focused SDK/CLI OCR tests, packaged OCR preset smoke, review package/page image/sidecar/benchmark smokes, Java full suite, and whitespace checks pass | focused tests, smoke, `mvn test`, `git diff --check` |
| 125. OCR worker doctor readiness RED/MVP | complete | `doctruth doctor` and `doctor --json` report local OCR worker executable readiness, engine, fallback engine, timeout, and disabled state | doctor test red, then green |
| 126. Verification after OCR worker doctor readiness | complete | Focused doctor/OCR/CLI tests, packaged OCR/review/page/sidecar/benchmark smokes, Java full suite, and whitespace checks pass | focused tests, smoke, `mvn test`, `git diff --check` |
| 127. Local MCP parse-document gateway RED/MVP | complete | `doctruth mcp` supports MCP initialize/tools-list/tools-call for `doctruth.parse_document`, returning compact text, JSON evidence, bbox locations, and source-map data | MCP tests red, then green |
| 128. MCP packaged smoke | complete | Shaded CLI MCP smoke passes, focused/full Java tests pass, smoke suite passes, and whitespace check passes after this slice | MCP smoke, smoke suite, `mvn test`, `git diff --check` |
| 129. MCP evidence/layout/table/citation tools RED/MVP | complete | `doctruth mcp` exposes and serves `get_layout_regions`, `get_table_cells`, `get_evidence_span`, and `verify_citation` from the v1 `TrustDocument` contract | MCP tests red, then green |
| 130. MCP broader tools packaged smoke | complete | Shaded CLI MCP smoke calls parse/layout/table/span/citation tools and verifies bbox/table/citation structuredContent | `sh scripts/smoke-doctruth-mcp.sh` |
| 131. Skill package and MCP bootstrap RED/MVP | complete | `skills/doctruth` contains a concise `SKILL.md`, OpenAI agent metadata, and a bootstrap script that writes a local stdio MCP config for `doctruth mcp` | skill package tests red, then green |
| 132. Skill package smoke | complete | Shell smoke verifies the skill package files and bootstrap-generated MCP config JSON | `sh scripts/smoke-doctruth-skill-package.sh` |
| 133. MCP model cache warmup RED/MVP | complete | `doctruth.warm_model_cache` verifies caller-supplied local model descriptors against a cache directory through MCP structuredContent | MCP test red, then green |
| 134. MCP model cache smoke | complete | Shaded CLI MCP smoke verifies `warm_model_cache` returns READY for a local SHA-matched model artifact | `sh scripts/smoke-doctruth-mcp.sh` |
| 135. Rust two-column reading-order RED/MVP | complete | `doctruth-runtime` orders positioned two-column text visually by column instead of raw content-stream interleaving | Cargo protocol test red, then green |
| 136. Runtime verification after two-column ordering | complete | Rust fmt, Cargo full tests, runtime smoke, and Java CLI sidecar smoke pass after column-aware ordering | `cargo fmt --check`, `cargo test`, smoke scripts |
| 137. Java/PDFBox borderless-table RED/MVP | complete | Conservative fallback recovers short aligned text matrices without grid lines into `TrustTable`/`TABLE_CELL` cells with bboxes | `PdfBorderlessTableExtractionTest` red, then green |
| 138. Verification after borderless-table fallback | complete | Borderless table, bordered table, two-column/layout regression, benchmark corpus smoke, Java full suite, and whitespace checks pass | focused Maven tests, benchmark smoke, `mvn test`, `git diff --check` |
| 139. Rust borderless-table RED/MVP | complete | `doctruth-runtime` recovers generated short aligned text matrices without grid lines into `TrustTable`/`TABLE_CELL` cells with bboxes | Cargo borderless protocol test red, then green |
| 140. Runtime verification after borderless table | complete | Cargo fmt/full tests, runtime smoke with explicit borderless table, Java CLI sidecar smoke, focused Java sidecar/render tests, and whitespace checks pass | `cargo fmt --check`, `cargo test`, smoke scripts, focused Maven, `git diff --check` |
| 141. CLI sidecar borderless-table smoke | complete | Shaded Java CLI consumes Rust sidecar borderless-table output and renders JSON, GFM Markdown, and plain text correctly | `sh scripts/smoke-doctruth-cli-sidecar-borderless.sh` |
| 142. Java/PDFBox horizontal merged-cell RED/MVP | complete | Generated bordered PDF tables preserve horizontal merged-cell column spans in `TableCellRegion` and `TrustTableCell.columnRange` | table-region tests red, then green |
| 143. Verification after Java merged-cell span | complete | Focused table tests, public API snapshot update, Java full suite, benchmark/OCR/sidecar smokes, and whitespace checks pass | focused Maven, snapshot, `mvn test`, smoke scripts, `git diff --check` |
| 144. RapidOCR/MNN worker adapter RED/MVP | complete | DocTruth owns `doctruth-rapidocr-mnn-worker`, parses RapidOCR text/score/box output into worker JSON, preserves OCR regions, and discovers the adapter on PATH | worker/doctor tests red, then green |
| 145. RapidOCR worker packaging smoke | complete | Source install and release tarball include executable `doctruth-rapidocr-mnn-worker`; adapter smoke proves direct worker output and Java CLI OCR preset path discovery | release smoke red, then green; RapidOCR worker smoke |
| 146. Rust horizontal merged-cell RED/MVP | complete | `doctruth-runtime` preserves generated bordered-table horizontal column spans in table JSON and `TABLE_CELL` units | Cargo protocol test red, then green |
| 147. Runtime/sidecar verification after Rust merged-cell span | complete | Runtime smoke and Java CLI sidecar smoke both parse generated horizontal merged cells as 3 cells with `Header` spanning columns 0..1 | Cargo tests, runtime smoke, CLI sidecar smoke |
| 148. Java/PDFBox vertical row-span RED/MVP | complete | Generated bordered PDF tables preserve vertical merged-cell row spans in `TableCellRegion` and `TrustTableCell.rowRange` | table-region test red, then green |
| 149. Java row-span benchmark gate | complete | Generated row-span table fixtures score `table_cell_f1=1.0` through `ParserBenchmarkRunner` | benchmark assertion |
| 150. Rust vertical row-span RED/MVP | complete | `doctruth-runtime` preserves generated bordered-table vertical row spans in table JSON and `TABLE_CELL` units | Cargo protocol test red, then green |
| 151. Runtime/sidecar verification after row-span | complete | Runtime smoke and Java CLI sidecar smoke both parse generated vertical row spans as 3 cells with `Role` spanning rows 0..1 | Cargo tests, runtime smoke, CLI sidecar smoke |
| 152. Rust page metadata RED/MVP | complete | `doctruth-runtime` reads MediaBox page dimensions and emits stable `sha256:` page metadata hashes independent of caller source hash | Cargo protocol test red, then green |
| 153. Runtime/sidecar verification after Rust page metadata | complete | Runtime smoke rejects placeholder `:page-1` source-hash page metadata and Java CLI sidecar smoke renders the new hash shape | Cargo tests, runtime smoke, CLI sidecar smoke |
| 154. Rust model-assisted fallback RED/MVP | complete | `doctruth-runtime` emits per-model severe `model_unavailable_fallback` warnings and `NOT_AUDIT_GRADE` when model-assisted presets run without local model execution | Cargo protocol test red, then green |
| 155. Runtime/sidecar verification after Rust model fallback | complete | Runtime smoke and Java CLI sidecar smoke prove `table-lite` preserves heuristic output for inspection while carrying `slanet-plus:v1` warning and non-audit-grade status | Cargo tests, runtime smoke, CLI sidecar smoke, focused Maven |
| 156. RapidOCR worker readiness RED/MVP | complete | `doctruth doctor --json` separates executable OCR worker availability from `--doctor` runtime readiness, including structured status code/message for broken RapidOCR installs | Doctor test red, then green |
| 157. RapidOCR adapter self-test smoke | complete | `doctruth-rapidocr-mnn-worker --doctor` imports/initializes RapidOCR and smoke proves ready/failure protocol plus Java CLI OCR path with the adapter contract | RapidOCR worker smoke, focused doctor test |
| 158. Java/PDFBox multi-page table continuation RED/MVP | complete | Adjacent generated bordered-table pages with repeated headers merge into one logical table, dedupe the continuation header, and keep page-2 cell evidence locations | `PdfMergedTableExtractionTest` red, then green |
| 159. Verification after Java multi-page table continuation | complete | Focused table/API/architecture tests, Java full suite, benchmark corpus smoke, and whitespace checks pass after page-aware table-cell regions and public API snapshot update | focused Maven, `mvn test`, benchmark smoke, `git diff --check` |
| 160. Rust sidecar multi-page table continuation RED/MVP | complete | `doctruth-runtime` merges adjacent generated bordered-table continuation pages with repeated headers, dedupes the continuation header, and keeps page-2 `TABLE_CELL` units on page 2 | Cargo protocol test red, then green |
| 161. Runtime/sidecar verification after Rust multi-page continuation | complete | Cargo fmt/tests, runtime smoke, Java CLI sidecar smoke, Java full suite, and whitespace checks pass after Rust continuation support | `cargo fmt --check`, `cargo test`, runtime smoke, CLI sidecar smoke, `mvn test`, `git diff --check` |
| 162. Rust rendered PNG page hash RED/MVP | complete | `doctruth-runtime` uses a configured renderer or local `pdftoppm` to hash actual rendered PNG bytes for `TrustPage.imageHash`, falling back to stable content hash only when rendering is unavailable | Cargo protocol test red, then green |
| 163. Runtime/sidecar verification after Rust rendered PNG hash | complete | Cargo fmt/tests, runtime smoke, Java CLI sidecar smoke, Java full suite, and whitespace checks pass; smokes compare `imageHash` against real `pdftoppm` PNG bytes on this machine | Cargo fmt/tests, runtime smoke, CLI sidecar smoke, `mvn test`, `git diff --check` |
| 164. RapidOCR array-output adapter RED/MVP | complete | `doctruth-rapidocr-mnn-worker` handles RapidOCR 3.8-style array-like `boxes`/`txts`/`scores` without ambiguous truth-value failures and preserves bbox/confidence output | Worker smoke red, then green |
| 165. Real RapidOCR opt-in smoke | complete | Added opt-in real RapidOCR smoke that creates/uses an isolated venv, installs RapidOCR + ONNXRuntime backend, runs worker `--doctor`, direct OCR, and Java CLI `parse --preset ocr` on a generated scanned PDF | `DOCTRUTH_RAPIDOCR_REAL_SMOKE=1 ... sh scripts/smoke-doctruth-rapidocr-real.sh` |
| 166. OCR benchmark metric and corpus preset RED/MVP | complete | Parser benchmarks now report `ocr_text_accuracy`, benchmark corpus manifests can request `preset: "ocr"`, and corpus smoke includes an OCR preset case with threshold gating | Runner/corpus tests red, then green |
| 167. Verification after OCR benchmark corpus gate | complete | Focused benchmark/corpus/API tests, benchmark/OCR/RapidOCR smokes, Java full suite, and whitespace checks pass after OCR corpus gate support | focused Maven, benchmark smoke, OCR smokes, `mvn test`, `git diff --check` |
| 168. Local model worker table-lite RED/MVP | complete | `TABLE_LITE` can use a configured local model worker instead of silent PDFBox fallback, returning model-produced `TrustTable`/`TABLE_CELL` output without `model_unavailable_fallback` | Parser API red, then green |
| 169. Verification after local model worker contract | complete | Model-worker CLI smoke, focused parser/backend/API tests, Java full suite, and whitespace checks pass | model-worker smoke, focused Maven, `mvn test`, `git diff --check` |
| 170. Model worker doctor readiness RED/MVP | complete | `doctruth doctor --json` reports configured model worker command, availability, ready state, status code/message, timeout, and loaded model ids without running inference | Doctor test red, then green |
| 171. Verification after model worker doctor readiness | complete | Model-worker smoke now verifies doctor readiness before parse; focused doctor/parser/backend/API tests, Java full suite, and whitespace checks pass | model-worker smoke, focused Maven, `mvn test`, `git diff --check` |
| 172. Model worker resource metrics RED/MVP | complete | `doctruth doctor --json` propagates worker-reported `rssMb` and `peakMemoryMb`, defaults missing metrics to `0`, and model-worker smoke verifies the fields | Doctor test red, then green |
| 173. Verification after model worker resource metrics | complete | Doctor focused tests, model-worker smoke, architecture/API checks, Java full suite, and whitespace checks pass | focused Maven, model-worker smoke, `mvn test`, `git diff --check` |
| 174. Model worker cache metadata RED/MVP | complete | Model-assisted worker requests include `modelCacheDirectory` plus per-model `cachePath`, `cacheStatus`, `actualSha256`, and `actualSizeBytes` from the local verifier | Parser API test red, then green |
| 175. Verification after model worker cache metadata | complete | Model-worker smoke verifies cache metadata handoff; parser/cache/architecture/API focused tests pass | model-worker smoke, focused Maven |
| 176. Model manifest READY cache RED/MVP | complete | `doctruth.model.manifest` / `DOCTRUTH_MODEL_MANIFEST` can override preset model descriptors so configured workers receive SHA-verified READY cache artifacts instead of placeholder descriptors | Manifest contract test red, then green |
| 177. Verification after model manifest READY cache | complete | Model-worker smoke now writes a local model artifact and manifest, then verifies `cacheStatus=READY` through the packaged CLI path | model-worker smoke, focused Maven, `mvn test`, `git diff --check` |
| 178. CLI model cache warm RED/MVP | complete | `doctruth cache warm <manifest.json> --preset <preset>` installs manifest-defined local/file model artifacts into the deterministic cache filename, verifies SHA-256, and refuses remote sources in `--offline` mode | CLI tests red, then green |
| 179. Verification after CLI model cache warm | complete | Packaged CLI smoke verifies local model cache warm and offline remote refusal; focused CLI/MCP/doctor/API tests pass | cache warm smoke, focused Maven, `mvn test`, `git diff --check` |
| 180. Remote model cache warm RED/MVP | complete | `doctruth cache warm` downloads HTTP(S) manifest sources through a streaming temp-file path, verifies SHA-256 after download, and preserves `--offline` remote refusal | CLI remote test red, then green |
| 181. Verification after remote model cache warm | complete | Packaged cache-warm smoke starts a local HTTP server and verifies remote download, local warm, and offline refusal | cache warm smoke, focused Maven, `mvn test`, `git diff --check` |
| 182. Manifest-aware model doctor RED/MVP | complete | `doctruth doctor --json` reads `DOCTRUTH_MODEL_MANIFEST`, verifies manifest model artifacts in `DOCTRUTH_MODEL_CACHE`, and reports `allReady` plus per-artifact READY/MISSING/SHA metadata | Doctor tests red, then green |
| 183. Verification after manifest-aware doctor | complete | Packaged model-worker smoke verifies doctor JSON sees the manifest cache artifact as READY; focused/full suites, LOC guard, and diff checks pass | model-worker smoke, focused Maven, `mvn test`, LOC check, `git diff --check` |
| 184. Model manifest runtime metadata RED/MVP | complete | Manifest entries can carry model runtime hints (`task`, `backend`, `format`, `precision`, `license`) through model-worker requests, `cache warm --json`, and `doctor --json` without expanding `ModelDescriptor` past architecture limits | Worker/cache/doctor tests red, then green |
| 185. Verification after manifest runtime metadata | complete | Packaged cache-warm and model-worker smokes assert runtime metadata survives jar execution; full suite, LOC guard, and diff checks pass | cache warm smoke, model-worker smoke, focused Maven, `mvn test`, LOC check, `git diff --check` |
| 186. ONNXRuntime worker smoke RED/MVP | complete | Add `scripts/doctruth-onnx-model-worker`, a JSON model-worker adapter that imports ONNXRuntime, loads a SHA-verified cached ONNX model, runs one inference, and returns a TrustDocument over the local model-worker path | ONNX smoke red at missing worker, then green |
| 187. Verification after ONNXRuntime worker smoke | complete | Package/install release scripts include the ONNX worker; release smoke verifies executable worker doctor output; full suite, LOC guard, and diff checks pass | ONNX smoke, packaging contract, release smoke, `mvn test`, LOC check, `git diff --check` |
| 188. Strict RapidOCR MNN backend doctor RED/MVP | complete | `doctruth-rapidocr-mnn-worker --doctor` now distinguishes RapidOCR availability from strict `MNN`/`mnn` backend readiness when `DOCTRUTH_RAPIDOCR_BACKEND=mnn` is set | MNN backend smoke red, then green |
| 189. Verification after strict MNN backend doctor | complete | Release smoke verifies packaged worker reports `backend=mnn` and `backendReady=true`; focused packaging test, full suite, LOC guard, and diff checks pass | MNN backend smoke, RapidOCR worker smoke, packaging test, release smoke, `mvn test`, LOC check, `git diff --check` |
| 190. ONNX TATR-like table decoder RED/MVP | complete | `doctruth-onnx-model-worker` decodes `task=table-structure-recognition` ONNX outputs named like `pred_logits`/`pred_boxes` into `TrustTable` and `TABLE_CELL` units | TATR decoder smoke red at empty tables, then green |
| 191. Verification after ONNX TATR-like decoder | complete | TATR decoder smoke and existing identity ONNX smoke pass; docs/planning updated; full suite, LOC guard, and diff checks pass | ONNX TATR smoke, ONNX identity smoke, `mvn test`, LOC check, `git diff --check` |
| 192. ONNX worker resource metrics smoke RED/MVP | complete | Direct ONNX worker responses now include `metrics.wallMs`, `metrics.inferenceWallMs`, `rssMb`, and `peakMemoryMb` from real ONNXRuntime execution | Resource smoke red at missing metrics, then green |
| 193. Verification after ONNX worker resource metrics | complete | Resource smoke and ONNX identity/TATR smokes pass; PRD/CLI/planning updated; full suite, LOC guard, and diff checks pass | ONNX resource smoke, identity smoke, TATR smoke, `mvn test`, LOC check, `git diff --check` |
| 194. Remote real-PDF benchmark corpus RED/MVP | complete | Parser corpus manifests support `sourceUrl` + `sourceSha256`, download remote PDF fixtures into `.doctruth-corpus-cache`, verify SHA-256 before parsing, and run a public W3C PDF smoke with human-authored labels | Remote corpus test red at missing `source`, then green; real PDF smoke |
| 195. Verification after remote real-PDF corpus | complete | Focused corpus tests, generated benchmark smoke, W3C real PDF smoke, full Maven suite, LOC guard, and diff checks pass | ParserBenchmarkCorpusTest, benchmark corpus smoke, real PDF corpus smoke, `mvn test`, LOC check, `git diff --check` |
| 196. ONNX RT-DETR-like layout decoder RED/MVP | complete | `doctruth-onnx-model-worker` decodes `task=layout-detection` ONNX outputs named like `pred_logits`/`pred_boxes` into bbox-bearing layout `TEXT_BLOCK` units sorted by reading order | Layout decoder smoke red at identity output, then green |
| 197. Verification after ONNX layout decoder | complete | Layout decoder smoke and existing ONNX identity/TATR/resource smokes pass; docs/planning updated; full suite, LOC guard, and diff checks pass | ONNX layout smoke, identity smoke, TATR smoke, resource smoke, `mvn test`, LOC check, `git diff --check` |
| 198. ONNX layout confidence warning RED/MVP | complete | Low-confidence `task=layout-detection` outputs below `0.85` emit severe `layout_low_confidence` unit warnings and return `NOT_AUDIT_GRADE` without dropping the region | Low-confidence layout smoke red at `AUDIT_GRADE`, then green |
| 199. Verification after ONNX layout confidence warning | complete | Low/high confidence layout smokes and existing ONNX identity/TATR/resource smokes pass; docs/planning updated; full suite, LOC guard, and diff checks pass | Low-confidence layout smoke, layout smoke, identity smoke, TATR smoke, resource smoke, `mvn test`, LOC check, `git diff --check` |
| 200. ONNX table confidence warning RED/MVP | complete | Low-confidence `task=table-structure-recognition` outputs below `0.85` emit severe `table_structure_low_confidence` parser warnings and return `NOT_AUDIT_GRADE` without dropping table/cell output | Low-confidence table smoke red at `AUDIT_GRADE`, then green |
| 201. Verification after ONNX table confidence warning | complete | Low/high confidence table smokes and existing ONNX identity/layout/resource smokes pass; docs/planning updated; full suite, LOC guard, and diff checks pass | Low-confidence table smoke, TATR smoke, identity smoke, layout smokes, resource smoke, `mvn test`, LOC check, `git diff --check` |
| 202. ONNX worker helper split RED/MVP | complete | Split the 300-line ONNX worker into a tiny executable shim plus `doctruth_onnx_worker_lib.py`, and require install/release/smoke packaging for the helper module | `CliPackagingContractTest` red at missing helper, then green |
| 203. Verification after ONNX worker helper split | complete | ONNX identity/table/layout/low-confidence/resource smokes and release tarball smoke pass after the split; full suite and whitespace checks pass | ONNX smokes, release smoke, LOC check, `mvn test`, `git diff --check` |
| 204. Rust sidecar doctor memory RED/MVP | complete | Runtime `--doctor` reports `rssMb` and `peakMemoryMb` from local process memory without adding dependencies | Rust protocol test red at missing fields, then green |
| 205. Verification after Rust sidecar doctor memory | complete | Runtime smoke verifies doctor memory fields; full Cargo/Maven/whitespace gates pass | Runtime smoke, `cargo fmt --check`, `cargo test`, `mvn test`, `git diff --check` |
| 206. Benchmark corpus offline remote RED/MVP | complete | `benchmark-corpus --offline` and `ParserBenchmarkCorpus.load(path, true)` refuse uncached remote `sourceUrl` fixtures before network access while allowing cached SHA-verified remote fixtures | Corpus/API and CLI tests red at missing overload/flag, then green |
| 207. Verification after benchmark corpus offline remote | complete | Focused corpus/API tests, benchmark corpus smoke, full Maven suite, and whitespace checks pass | Focused Maven, benchmark corpus smoke, `mvn test`, `git diff --check` |
| 208. Strict warning false-negative corpus gate RED/MVP | complete | Benchmark runner reports `strict_warning_false_negative_rate` from expected severe parser/unit warnings, and corpus manifests support `maximums` for lower-is-better thresholds | Runner/corpus/CLI tests red at missing maximum APIs, then green |
| 209. Verification after strict warning corpus gate | complete | Focused benchmark/API/CLI tests, benchmark corpus smoke, full Maven suite, and whitespace checks pass | Focused Maven, benchmark corpus smoke, `mvn test`, `git diff --check` |
| 210. Parser latency corpus gate RED/MVP | complete | `ParserBenchmarkCase.fromPdf(...)` records parse latency, runner reports `parser_latency_ms`, corpus output reports `parser_latency_p50/p95`, and `maximums.parser_latency_p95` gates aggregate latency | Runner/CLI tests red at missing latency APIs, then green |
| 211. Verification after parser latency corpus gate | complete | Focused benchmark/API/CLI tests, benchmark corpus smoke, full Maven suite, and whitespace checks pass | Focused Maven, benchmark corpus smoke, `mvn test`, `git diff --check` |
| 212. Section boundary corpus gate RED/MVP | complete | `ParserBenchmarkRunner` reports `section_boundary_f1` from recovered heading-like boundary lines, and corpus manifests can gate it through `minimums` | Runner/corpus tests red at `section_boundary_f1=0.0`, then green |
| 213. Verification after section boundary corpus gate | complete | Focused benchmark/API/CLI tests, benchmark corpus smoke, full Maven suite, and whitespace checks pass | Focused Maven, benchmark corpus smoke, `mvn test`, `git diff --check` |
| 214. Evidence span accuracy corpus gate RED/MVP | complete | `ParserBenchmarkRunner` reports `evidence_span_accuracy` by checking expected text-line coverage through actual evidence-bearing units, and corpus manifests can gate it through `minimums` | Runner/corpus tests red at `evidence_span_accuracy=0.0`, then green; smoke caught and fixed overly strict span-id matching |
| 215. Verification after evidence span accuracy gate | complete | Focused benchmark/API/CLI tests, benchmark corpus smoke, full Maven suite, and whitespace checks pass | Focused Maven, benchmark corpus smoke, `mvn test`, `git diff --check` |
| 216. Benchmark resource metrics RED/MVP | complete | `ParserBenchmarkCase` carries `ParserBenchmarkResources`; runner reports `rss_peak_mb` and `model_cache_size_mb`, and CLI JSON exposes them per case | Runner/CLI tests red at missing constructor/metrics, then green; architecture forced resource wrapper instead of 7-component case record |
| 217. Verification after benchmark resource metrics | complete | Focused benchmark/API/CLI tests, benchmark corpus smoke, full Maven suite, and whitespace checks pass | Focused Maven, benchmark corpus smoke, `mvn test`, `git diff --check` |
| 218. Compact corpus aggregate minimum gate RED/MVP | complete | Corpus output reports `compact_llm_size_reduction_min`, and manifest `minimums.compact_llm_size_reduction_min` gates corpus-level compact LLM reduction instead of per-case fallback | Runner/corpus/CLI tests red at missing aggregate metric and wrong per-case failure, then green |
| 219. Degenerate table cell bbox RED/MVP | complete | Real-PDF table extraction skips degenerate normalized cell bboxes instead of throwing `IllegalArgumentException` | Focused PDF table test red at `bounding box must have positive width and height`, then green |
| 220. Verification after compact aggregate and degenerate bbox gates | complete | Focused benchmark/table tests, benchmark corpus smoke, full Maven suite, recorded verify, and whitespace checks passed | Focused Maven, benchmark corpus smoke, `mvn test`, `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`, `git diff --check` |

## Decisions

- Keep current Java `ParsedDocument` compatibility; add `TrustDocument` as the
  v1 product contract.
- Use `TrustUnit` for the smallest citeable unit.
- Implement Java-side contracts first; Rust runtime becomes a backend behind
  the same contract later.
- Preserve unrelated existing dirty changes unless they directly block this work.

## Errors Encountered

| Error | Attempt | Resolution |
| --- | --- | --- |
| Focused v1 tests failed at testCompile because contract types did not exist | Red phase for `TrustDocumentContractTest,TrustUnitTest` | Added minimal v1 public records/enums; focused tests now pass |
| Adapter test failed at testCompile because `TrustDocument.fromParsed(...)` did not exist | Red phase for `TrustDocumentAdapterTest` | Added adapter factory on `TrustDocument`; adapter test now passes |
| Rendered output failed because Jackson cannot serialize Optional without extra module | `TrustDocumentRenderedOutputTest` first implementation | Replaced broad record serialization with explicit JSON node rendering |
| New v1 output tests failed at testCompile because source-map/chunk/HTML APIs did not exist | Red phase for chunking, HTML passthrough, source-map, reading-order, table contracts | Added public records and render helpers, then reran focused tests |
| Public API snapshot failed after adding v1 public API | Focused full contract run | Updated `public-api-snapshot.txt` and reran focused/full suites |
| Benchmark threshold test failed at testCompile because `requireMinimums(...)` did not exist | Red phase for parser benchmark threshold gates | Added threshold enforcement and reran focused/full suites |
| Compact corpus aggregate minimum tests failed because aggregate metrics did not include `compact_llm_size_reduction_min` and manifests treated it as a per-case metric | Red phase for compact corpus aggregate gate | Added aggregate min metric plus aggregate `minimums` routing before per-case threshold checks |
| Recorded real-world PDF verification failed with `bounding box must have positive width and height` from `PdfPageTableExtractor.cellRegions` | `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded` after compact gate | Added a focused RED PDF fixture with an off-page degenerate grid cell and skipped zero-area normalized cell regions |
| Public API snapshot failed after `requireMinimums(...)` became public | `PublicApiSnapshotTest,ArchitectureContractTest` | Regenerated the public API snapshot from the test, reviewed v1 parser/runtime API diff, and reran successfully |
| Expected-document benchmark test failed at testCompile because `ParserBenchmarkCase` did not accept an expected document | Red phase for bbox/table metrics | Added optional expected `TrustDocument` and implemented `bbox_iou` / `table_cell_f1` |
| Rust line-level extraction test failed because runtime still emitted one `TEXT_BLOCK` per page | Red phase for citeable line units | Split extracted page text into normalized lines and emitted stable `LINE_SPAN` units with page+line source ids |
| CLI sidecar smoke initially failed because bare `java` resolved to the macOS stub with no runtime | First run of `scripts/smoke-doctruth-cli-sidecar.sh` | Script now resolves `$JAVA_HOME/bin/java`, Homebrew OpenJDK, then fallback `java` |
| Real PDF benchmark fixture test failed at testCompile because `ParserBenchmarkCase.fromPdf(...)` did not exist | Red phase for parser-quality fixture gate | Added `fromPdf(...)` and benchmark `bbox_coverage` metric |
| Real PDF expected-bbox benchmark test failed at testCompile because `ParserBenchmarkCase.fromPdf(..., expectedDocument)` did not exist | Red phase for bbox IoU fixture gate | Added `fromPdf(...)` overload carrying expected `TrustDocument` |
| Real PDF bordered-table benchmark failed because Java/PDFBox parser emitted no structured tables from a PDF grid | Red phase for table-cell quality gate | Added PDF graphics vertical-line extraction and a conservative bordered-grid table detector that emits `TableSection` |
| Real PDF bordered-table output duplicated cell text as both `TEXT_BLOCK` and `TABLE_CELL` units | Red phase for downstream Markdown/LLM cleanliness | Kept internal table-region bounds and filtered overlapping text blocks before adding table sections |
| Real PDF bordered-table benchmark could not score `table_region_iou` because table region bboxes were not preserved in `TableSection`/`TrustTable` | Red phase for table-region quality gate | Added optional `TableSection.boundingBox`, propagated it to `TrustTable`, and implemented `table_region_iou` |
| Real PDF bordered-table cells had no cell-level bboxes in `TrustTableCell` or `TABLE_CELL` units | Red phase for evidence-grade table-cell anchors | Added `TableCellRegion`, carried detected grid cell bboxes through `TableSection`, and propagated them into `TrustTableCell` plus table-cell units |
| Markdown table output was not valid GFM and source-map Markdown rendered each table cell as a separate paragraph | Red phase for LLM/RAG-friendly Markdown table output | Added GFM pipe-table rendering and source-map entries for each rendered table cell |
| Rust runtime emitted no `TrustTable`/`TABLE_CELL` output for a bordered-grid PDF | Red phase for Rust sidecar table parity | Added `pdf_oxide` content-stream parsing, simple bordered-grid detection, table/cell bbox JSON output, and table-aware runtime/CLI smoke coverage |
| Rust runtime `LINE_SPAN` units still used page-level bbox fallback even when `Td/Tj` text positions were available | Red phase for precise text bbox progress | Reused content-stream parsing to estimate positioned text bboxes and suppress fallback warnings for simple positioned text |
| Rust runtime emitted no `TrustTable`/`TABLE_CELL` output for borderless aligned text matrices | Red phase for Rust borderless table parity | Added a conservative borderless table fallback over content-stream `TextPoint`s and explicit runtime smoke coverage |
| `compact_llm` emitted only document/unit records and dropped table ids plus parser/unit warnings | Red phase for compact evidence wire coverage | Added deterministic `t|` table records and `w|` parser/unit warning records |
| Rust protocol tests could read another test's generated PDF under parallel cargo execution | Full cargo verification after compact wire change | Made generated PDF fixture paths unique with an atomic process-local sequence suffix |
| `html_review` exposed unit/evidence/page anchors but no bbox-compatible attributes | Red phase for HTML review bbox anchors | Added `data-bbox` and `data-bbox-space="normalized-0-1000"` when unit bboxes are present |
| `html_review` had table-cell unit sections but no semantic table/cell review nodes | Red phase for HTML review table/cell anchors | Added semantic table and cell HTML renderer with table id, cell id, unit/evidence ids, and normalized bbox attributes |
| `writeMarkdownClean(...)` and `writeJsonLines(...)` wrote one aggregate rendered string to the caller-owned writer | Red phase for streaming writer paths | Added incremental renderer writer paths and bounded write chunking while preserving byte-for-byte output parity |
| `TrustRenderedDocument` source maps did not bind clean Markdown to source hash or rendered content hash | Red phase for source-map hash binding | Added `sourceHash` and `contentHash` to `TrustRenderedDocument`, computed clean Markdown SHA-256, and updated CLI source-map JSON |
| `markdown_anchored` carried span/page anchors but omitted available bbox metadata | Red phase for anchored Markdown bbox metadata | Added optional `bbox="x0,y0,x1,y1"` attribute inside evidence anchors |
| `markdown_review` rendered parser warnings but omitted unit-scoped warnings | Red phase for markdown review warning coverage | Added a Unit Warnings section with unit id, severity, warning code, and message |
| Plain text was mentioned in the PRD but not exposed as an SDK method, CLI format, or backend capability | Red phase for clean consumption output parity | Added `TrustDocument.toPlainText()`, CLI `--format plain`, `plain_text` capabilities, docs, PRD contract text, and CLI sidecar smoke coverage |
| Source-map sidecars carried source/content hashes but there was no CLI verifier | Red phase for replay/source binding | Added `verify-source-map`, hash mismatch failures, help/completion/docs, and CLI sidecar smoke coverage |
| Audit JSON was compliance-oriented but did not include canonical/evidence hashes | Red phase for hashable audit package integrity | Added `canonicalHash` and deterministic `evidenceHash` to `TrustDocument` Audit JSON and sidecar smoke assertions |
| `TrustDocument` audit JSON was hashable but had no SDK signing/wrapping path | Red phase for signed audit package integrity | Reused the existing `SignatureProvider` contract and added `toAuditJson(SignatureProvider)` plus file output |
| Labeled benchmark cases existed only as in-code/generated fixtures, not an executable corpus manifest | Red phase for corpus harness | Added `ParserBenchmarkCorpus.load(...)` with relative paths, expected labels, threshold reuse, and case-specific diagnostics |
| `TrustDocumentJson.fromJsonFull(...)` rejected `toJsonFull()` output when page `imageHash` was blank | Corpus harness green phase | Allowed blank page image hash during JSON full import while keeping required trust fields strict |
| Benchmark corpus manifests were SDK-only and not available as a CLI/CI smoke gate | Red phase for benchmark corpus CLI | Added `benchmark-corpus` command, JSON output, threshold failure exit behavior, help/completion entries, docs, and smoke script |
| HTML review had unit/table/cell anchors but no page surface wrapper for overlay tools | Red phase for page-aware HTML review | Added page containers with page number, dimensions, text-layer availability, image hash, and page-scoped units/tables |
| HTML page-surface test initially used the wrong `TrustUnitLocation` constructor argument order | First RED attempt for HTML page surfaces | Corrected the test to construct `TrustUnitLocation(page, bbox, readingOrder)` before verifying the intended missing-page-surface failure |
| CLI sidecar smoke expected synthetic `1000x1000` page geometry and `sha256:image` | First smoke run after HTML page surfaces | Updated smoke assertions to the real generated PDF MediaBox `612x792` and source-derived `sha256:*:page-1` image hash pattern |
| `compact_llm` carried evidence ids and text but dropped available bbox metadata | Red phase for compact evidence wire coverage | Appended optional `bbox=x0,y0,x1,y1` to compact unit records when a normalized bbox exists |
| Maven focused tests and sidecar smoke were launched concurrently despite the no-parallel-Maven note | Focused verification for compact bbox metadata | Both passed, then full Maven/Cargo/runtime verification was run sequentially |
| Compact output still had no writer API and CLI file output rendered the full compact string before writing | Red phase for compact streaming writer | Added `TrustDocument.writeCompactLlm(Writer)` and routed `doctruth parse --format compact --out` through the writer path |
| Public API snapshot failed after adding `writeCompactLlm(Writer)` | Focused verification after compact writer implementation | Regenerated the public API snapshot with `-Ddoctruth.updatePublicApiSnapshot=true` and reran focused tests |
| Compact output was evidence-bearing but not source-map resolvable | Red phase for compact source-map coverage | Added `toCompactLlmWithSourceMap()`, CLI compact `--source-map`, verification coverage, and sidecar smoke assertions |
| Public API snapshot needed to include `toCompactLlmWithSourceMap()` | Focused verification after compact source-map implementation | Regenerated the public API snapshot and reran focused API/architecture tests |
| Parallel Maven test invocations produced broad `cannot find symbol` compile errors | Focused verification for HTML review bbox anchors | Reran Maven commands sequentially; sequential focused and full verification passed |
| Static `TrustDocumentParser` only exposed the lite parser path, so callers could not request strict/model-assisted preset semantics from the parser-only API | Red phase for strict preset API | Added `parse(..., ParserPreset)` and `parseBatch(..., ParserPreset)` overloads; strict presets carry severe `model_unavailable_fallback` warnings and evaluate `NOT_AUDIT_GRADE` |
| Model fallback warnings were generic, so audit/replay tools could not tell which required model was unavailable | Red phase for per-model fallback warnings | `ModelRuntimePolicy.warnings()` now emits one severe warning per required model with model identity and expected SHA |
| Full Maven suite once failed in `GeminiProviderHttpTest$HttpErrors.unauthorisedNonRetryable` with `PROVIDER_RESPONSE_INVALID` instead of `PROVIDER_HTTP_401` | First full verification after per-model fallback warnings | The focused Gemini test passed on immediate rerun, then the full Maven suite passed; recorded as an existing provider HTTP test flake unrelated to parser/model changes |
| JSON full and audit JSON only exposed aggregate string renderers, leaving large-document callers without writer APIs for the two most important replay formats | Red phase for JSON/audit writer APIs | Added SDK writer APIs and renderer chunking around Jackson writer output; parser ingestion and some CLI/file export paths still materialize aggregate data |
| CLI `--out` still rendered JSON full and Audit JSON through full strings even after SDK writer APIs existed | Red phase for CLI writer routing | Added `TrustDocumentCliWriters` and routed clean Markdown, JSONL, compact LLM, JSON full, and Audit JSON file output through writer paths |
| JSON evidence still had only a string renderer after JSON full/audit writer APIs landed | Red phase for JSON evidence writer API | Added `TrustDocument.writeJsonEvidence(Writer)`, updated the public API snapshot, and routed CLI evidence file output through the SDK writer |
| Anchored Markdown, review Markdown, plain text, and HTML review still had only string renderers | Red phase for remaining render writer APIs | Added SDK writer APIs, CLI `--out` routing, and an HTML one-overlay-layer-per-page regression check |
| CLI TrustDocument stdout output still rendered through one aggregate string even after file-output writer paths existed | Red phase for stdout writer routing | Added a bounded `PrintStream` writer bridge and routed TrustDocument stdout output through the same writer dispatch |
| CLI source-map sidecars serialized with `writeValueAsString(...)` before file write | Red phase for source-map sidecar writer routing | Added `TrustDocumentCliWriters.writeSourceMap(...)` with bounded writer chunking and routed `writeSourceMapIfRequested(...)` through file writers |
| Canonical and evidence hashes were computed from aggregate JSON strings | Red phase for hash input writer routing | Added writer-visible canonical/evidence hash inputs and compute hashes through `DigestOutputStream` |
| Benchmark compact-size metrics counted `toJsonFull().getBytes(...)` and `toCompactLlm().getBytes(...)` | Red phase for benchmark byte-count writer routing | Added writer-backed byte counters and routed compact-size reduction through them |
| `verify-source-map` hashed rendered and source files with `Files.readString(...).getBytes(...)` and `Files.readAllBytes(...)` | Red phase for verifier streaming hash | Added package-visible streaming hash helpers and routed verifier checks through buffered file reads |
| CLI parse and SDK path parse source hashing used `Files.readAllBytes(...)` | Red phase for source hash streaming | Added streaming hash helpers for `TrustDocumentParser` and `ParseCommand` and routed path hashing through buffered file reads |
| Source-map sidecar writing required callers to materialize `TrustRenderedDocument` first | Red phase for source-map direct writer APIs | Added `TrustDocument.writeMarkdownSourceMap(...)`, `writeCompactLlmSourceMap(...)`, CLI direct writer methods, and routed `parse --source-map` through them |
| PDFBox `TrustPage` metadata still used `1000x1000` with blank image hash | Red phase for rendered page image hashes | Added PDFRenderer-backed 72 DPI page rendering, PNG SHA-256 page image hashes, and backend/public path parser routing through the enriched page metadata |
| `TrustDocumentParser.parse(InputStream, ...)` called `InputStream.readAllBytes()` before parsing | Red phase for input-stream parser streaming copy | Changed stream parsing to copy into a temporary PDF file incrementally, then route through the same PDFBox backend path used by file parsing |
| Rendered page images existed only as `TrustPage.imageHash` metadata, not persisted review/replay artifacts | Red phase for rendered page image artifacts | Added `PdfPageImageRenderer.writePngs(...)`, `doctruth render-pages <document> -o <dir>`, `page-images.json`, and a CLI smoke script that verifies manifest hashes against PNG bytes |
| HTML review and page images existed as separate outputs with no one-command local review package | Red phase for local review package | Added `doctruth review-package <document> -o <dir>` with `review.html`, `trust-document.json`, `pages/page-%04d.png`, `page-images.json`, and smoke verification |
| OCR SPI existed but v1 `TrustDocumentParser` and TrustDocument CLI outputs still reported `pdfbox`/model fallback instead of local OCR provenance | Red phase for v1 OCR preset routing | Routed `ParserPreset.OCR` through `PdfDocumentParser.parse(..., OcrEngines.defaultLocal())`, marked units as `OCR_REGION`, added `pdfbox+ocr`/`rapidocr-mnn:local` provenance, and wired `parse --format json --preset ocr` plus `review-package --preset ocr` |
| OCR worker confidence was logged by `PdfDocumentParser` but lost before `TrustUnitEvidence`, so weak OCR became `AUDIT_GRADE` | Red phase for OCR confidence audit gate | Wrapped the local OCR engine in `TrustDocumentParser`, collected page confidence, copied it into OCR units, and added severe `ocr_low_confidence` below `0.85` |
| Users could configure OCR but `doctruth doctor` did not report whether a local OCR worker was visible/executable | Red phase for OCR worker doctor readiness | Added `OcrDoctor` and wired text/JSON doctor output to expose command, availability, disabled state, engine, fallback engine, and timeout |
| Phase 6 required agent document parsing through MCP, but the CLI had no `mcp` command or document evidence tool | Red phase for local MCP parse-document gateway | Added a local stdio MCP gateway with `initialize`, `tools/list`, and `tools/call` for `doctruth.parse_document`; response includes compact text, JSON evidence with bbox locations, and source-map entries |
| Local `rapidocr` command existed but could not start in the current Python environment | Manual local check | Kept raw `rapidocr` CLI out of auto-discovery; current error is NumPy C-extension mismatch between Python 3.10 and a cpython-314 NumPy artifact. Worker protocol remains the verified path |
| Real RapidOCR 3.8 output failed with `truth value of an array with more than one element is ambiguous` | Direct worker request in isolated RapidOCR venv | Added array-like fake worker RED coverage and changed the adapter to normalize iterable/`tolist()` outputs without boolean checks |
| `rg` consistency check executed a backticked pattern as a shell command | Search for stale `rapidocr_unavailable` wording | Reran the search with single-quoted pattern; no stale current-status wording remained |
| `ocr_text_accuracy` defaulted to `0.0` because the metric did not exist | RED phase for OCR benchmark metric | Added normalized OCR text accuracy metric with threshold coverage |
| Benchmark corpus ignored `preset: "ocr"` and parsed scanned fixtures as `lite` | RED phase for OCR corpus preset routing | Added per-case preset parsing and routed corpus PDF cases through `TrustDocumentParser.parse(path, preset)` |
| Public API snapshot failed after adding preset-aware `ParserBenchmarkCase.fromPdf(...)` overload | Focused benchmark/corpus verification | Regenerated the public API snapshot and reran focused API/architecture tests |
| Parallel focused Maven invocations raced on surefire temporary files again | Initial focused verification for OCR benchmark metric | Reran benchmark/corpus tests sequentially and kept subsequent verification sequential |
| `section_boundary_f1` returned `0.0` for recovered generated headings | Red phase for section-boundary benchmark gate | Added heading-like boundary extraction and F1 scoring over actual vs expected Markdown boundary keys |
| `evidence_span_accuracy` returned `0.0` for generated corpus smoke even after exact span-id matching passed unit tests | Packaged benchmark corpus smoke | Changed metric semantics from internal span-id equality to expected text-line coverage by actual evidence-bearing units |
| Adding benchmark resource fields directly to `ParserBenchmarkCase` made it a 7-component public record | Architecture contract after resource metric implementation | Introduced `ParserBenchmarkResources` and kept `ParserBenchmarkCase` at 5 record components with compatibility accessors |
| `TABLE_LITE` ignored `doctruth.model.command` and still used PDFBox fallback | RED phase for local model worker | Added `LocalModelWorker` and `TrustDocumentParser` integration before fallback policy evaluation |
| Fake model worker stdout was empty because heredoc consumed stdin as Python source | First GREEN attempt for local model worker | Rewrote fake workers as executable Python scripts that read JSON from stdin |
| `doctor --json` did not expose the configured model worker even though parsing could use it | RED phase for model worker doctor readiness | Added `ModelWorkerDoctor` with `--doctor` readiness probing and wired it into `models.worker` JSON/text output |
| Model-worker smoke failed because Java normalized a `//` temp path differently from shell `$WORKER` | First smoke run after doctor readiness | Compared resolved paths in the smoke assertion instead of raw strings |
| Refactoring `ModelDoctor` out of `DoctorCommand` initially removed still-needed `Files`/`Path` imports and left old `ready`/`statusCode` field references | Post-green structure cleanup | Restored imports, switched summary to accessor methods, and reran focused plus full Maven successfully |
| `rg` consistency check executed a backticked `ModelDoctor` pattern as a shell command | Final consistency search | Reran the search with single-quoted patterns |
| Model-worker `--doctor` resource fields were ignored, so `rssMb` and `peakMemoryMb` were missing/zero even when the worker reported them | RED phase for model worker resource metrics | Added resource parsing to `ModelWorkerDoctor`, wired JSON output, and extended smoke assertions |
| Model-worker parse requests named required models but did not tell the worker where verified local cache artifacts live or whether each artifact was ready/missing/mismatched | RED phase for model worker cache metadata | Added `modelCacheDirectory` and per-model cache verification fields to `LocalModelWorker` requests; extended parser API and smoke assertions |

## Verification Log

| Command | Result |
| --- | --- |
| `mvn -q -Dtest=TrustDocumentContractTest,TrustUnitTest,TrustDocumentAdapterTest,TrustDocumentRenderedOutputTest,TrustDocumentAuditGateTest,TrustDocumentLocalSmokeTest test` | pass |
| `mvn -q -Dtest=ArchitectureContractTest,PublicApiSnapshotTest test` | pass after updating public API snapshot |
| `mvn -q -Dtest=TrustDocumentChunkingContractTest,TrustDocumentSourceMapContractTest,HtmlPassthroughContractTest,ReadingOrderContractTest,TableExtractionContractTest test` | red at first compile, then pass after implementation |
| `mvn -q -Dtest=ParserBackendContractTest,ModelRuntimePolicyTest,TrustDocumentContractTest,TrustUnitTest,TrustDocumentAdapterTest,TrustDocumentRenderedOutputTest,TrustDocumentAuditGateTest,TrustDocumentLocalSmokeTest,TrustDocumentChunkingContractTest,TrustDocumentSourceMapContractTest,HtmlPassthroughContractTest,ReadingOrderContractTest,TableExtractionContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test` | red at first because `parse` did not support `--format`; pass after implementation |
| `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test` | red at first because doctor did not report parser/model/memory and did not support `doctor models`; pass after implementation |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,DocTruthCliTest,CliSupportTest,DocTruthCliDoctorCompletionTest,ParserBackendContractTest,ModelRuntimePolicyTest,TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentChunkingContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest test` | red at first because `TrustDocumentParser` and `TrustDocument.canonicalHash()` did not exist; pass after implementation |
| `mvn -q -Dtest=TrustDocumentSdkParserContractTest,ModelCacheVerifierTest,ParserBenchmarkRunnerTest,TrustDocumentStreamingRenderContractTest test` | red at first compile, then pass after implementation |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test` | red at first because JSON full/audit writer APIs did not exist; pass after implementation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for JSON full/audit writer APIs |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 871 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentCliWritersTest test` | red at first because `TrustDocumentCliWriters` did not exist; pass after implementation |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test` | pass |
| `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentStreamingRenderContractTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 873 tests, 0 failures, 0 errors |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test` | red at first because `writeJsonEvidence(Writer)` did not exist; pass after implementation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for `writeJsonEvidence(Writer)` |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn test` | pass: 873 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test` | red at first because remaining render writer APIs did not exist; pass after implementation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for anchored/review/plain/HTML writer APIs |
| `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentStreamingRenderContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 874 tests, 0 failures, 0 errors |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ModelCacheVerifierTest,ParserBenchmarkRunnerTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,ParserBackendContractTest,ModelRuntimePolicyTest,TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentChunkingContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn test` | pass: 811 tests, 0 failures, 0 errors |
| `mvn -q -Dtest=SidecarParserBackendTest test` | red at first because `SidecarParserBackend` did not exist; pass after implementation |
| `mvn -q -Dtest=SidecarParserBackendTest,ParserBackendContractTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ModelCacheVerifierTest,ParserBenchmarkRunnerTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn test` | pass: 814 tests, 0 failures, 0 errors |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test` | red at first because `--backend`/`--runtime`/`--preset` were unsupported; pass after implementation |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,SidecarParserBackendTest,ParserBackendContractTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ModelCacheVerifierTest,ParserBenchmarkRunnerTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn test` | pass: 815 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest test` | red at first compile for missing threshold API, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test` | pass after updating public API snapshot |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 6 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `mvn test` | pass: 817 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest test` | red at first compile for missing expected document constructor, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test` | pass after updating public API snapshot |
| `mvn test` | pass: 818 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` | red at first: 5 passed, 2 failed because runtime emitted page-level `TEXT_BLOCK`; then pass: 7 tests |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass after rustfmt |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentCliOutputProfileTest,TrustDocumentParserApiContractTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 818 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 818 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest test` | red at first compile for missing `ParserBenchmarkCase.fromPdf(...)`, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 819 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest test` | red at first compile for missing `ParserBenchmarkCase.fromPdf(..., expectedDocument)`, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 820 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkCanCompareRealPdfAgainstExpectedTableCells test` | red at first: `table_cell_f1` was 0.0, then pass after bordered-table extraction |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableExtractionContractTest,PdfVisualLayoutParserTest test` | pass |
| `mvn test` | pass: 821 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkCanCompareRealPdfAgainstExpectedTableRegion test` | red at first: `table_region_iou` was 0.0, then pass after table bbox propagation and metric implementation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableSectionTest,TableExtractionContractTest,TrustDocumentAdapterTest,TrustDocumentRenderedOutputTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 825 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest#realPdfTableExtractionSuppressesDuplicateTextBlocks test` | red at first: duplicate `TEXT_BLOCK` units contained table cell text, then pass after filtering |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableExtractionContractTest,PdfVisualLayoutParserTest,PdfDocumentParserTest test` | pass |
| `mvn test` | pass: 822 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest#realPdfBorderedTableExtractionPreservesCellBoundingBoxes test` | red at first: `TrustTableCell.boundingBox()` values were empty, then pass after cell-region propagation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableCellRegionTest,TableSectionTest,TableExtractionContractTest,TrustDocumentAdapterTest,TrustDocumentRenderedOutputTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 833 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml parse_pdf_emits_table_cells_for_bordered_grid_pdf -- --nocapture` | red at first: `tables.len()` was 0, then pass after Rust bordered-grid table extraction |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 8 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass; now also validates bordered-table JSON/cell bboxes |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also validates sidecar table JSON and clean Markdown GFM table output |
| `mvn test` | pass: 834 tests, 0 failures, 0 errors |
| `cargo tree --manifest-path runtime/doctruth-runtime/Cargo.toml -e normal \| rg "chrono\|jiff\|rayon\|time v" \|\| true` | pass: no unnecessary PDF backend default-feature dependencies reported |
| `git diff --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml parse_pdf_emits_positioned_text_bboxes_when_content_stream_positions_are_available -- --nocapture` | red at first: text bbox was still page fallback with x0=0.0, then pass after content-stream text-position bbox extraction |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 834 tests, 0 failures, 0 errors |
| `cargo tree --manifest-path runtime/doctruth-runtime/Cargo.toml -e normal \| rg "chrono\|jiff\|rayon\|time v" \|\| true` | pass: no unnecessary PDF backend default-feature dependencies reported |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest test` | red at first: clean Markdown lacked GFM table separators and source-map Markdown rendered each cell as its own paragraph; then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentChunkingContractTest,TrustDocumentCliOutputProfileTest,TableExtractionContractTest,ParserBenchmarkRunnerTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 834 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 7 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest test` | red at first: `compact_llm` lacked table and warning records; then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test` | pass |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | red at first due parallel temp PDF fixture collision, then pass: 9 tests |
| `mvn test` | pass: 835 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest test` | red at first: HTML review lacked bbox attributes; then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test` | pass when run sequentially |
| `mvn test` | pass: 835 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest test` | red at first: review Markdown omitted unit-scoped warnings; then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test` | pass |
| `mvn test` | pass: 839 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest test` | red at first: anchored Markdown omitted bbox metadata; then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test` | pass |
| `mvn test` | pass: 838 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest test` | red at first compile for missing `TrustRenderedDocument.sourceHash()` and `contentHash()`; then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 837 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test` | red at first: Markdown writer attempted one 5279-character write; then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test` | pass |
| `mvn test` | pass: 837 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest test` | red at first: HTML review had no semantic table/cell nodes; then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test` | pass |
| `mvn test` | pass: 836 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest test` | red at first compile for missing `TrustDocument.toPlainText()`, then pass |
| `mvn -q -Dtest=ParserBackendContractTest,SidecarParserBackendTest test` | red at first because PDFBox and sidecar capabilities omitted `plain_text`, then pass |
| `mvn -q -Dtest=ParserBackendContractTest,SidecarParserBackendTest,TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 842 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also validates sidecar plain table output |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test` | red at first because `verify-source-map` was not registered, then pass |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 844 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also verifies the generated Markdown source-map sidecar |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest test` | red at first because Audit JSON omitted `canonicalHash` and `evidenceHash`, then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 845 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also validates sidecar audit JSON hash fields |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest test` | red at first for test constructor misuse, then red correctly because HTML review lacked page containers, then pass |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 846 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 9 tests |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also validates sidecar HTML page metadata |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest test` | red at first because `compact_llm` omitted bbox metadata, then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also validates sidecar compact output contains `bbox=` |
| `mvn test` | pass: 847 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml && sh scripts/smoke-doctruth-runtime.sh && git diff --check` | pass: 9 cargo tests and runtime smoke |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test` | red at first compile because `writeCompactLlm(Writer)` did not exist, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for `writeCompactLlm(Writer)` |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 847 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml && sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh && git diff --check` | pass: 9 cargo tests, runtime smoke, CLI sidecar smoke |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest test` | red at first compile because `toCompactLlmWithSourceMap()` did not exist, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for `toCompactLlmWithSourceMap()` |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest,TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also verifies compact source-map sidecar and `verify-source-map` |
| `mvn test` | pass: 849 tests, 0 failures, 0 errors |
| `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml && sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh && git diff --check` | pass: 9 cargo tests, runtime smoke, CLI sidecar smoke |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest test` | red at first compile for missing signer overloads, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest,TrustDocumentRenderedOutputTest test` | pass |
| `mvn test` | pass: 852 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-runtime.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkCorpusTest test` | red at first compile for missing `ParserBenchmarkCorpus`, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for `ParserBenchmarkCorpus` |
| `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest,ParserBenchmarkCorpusTest,ParserBenchmarkRunnerTest test` | pass |
| `mvn test` | pass: 855 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest test` | red at first because `benchmark-corpus` was unknown, then pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | failed at first due to missing Python `reportlab`, then pass after raw-PDF fixture generation |
| `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,ParserBenchmarkCorpusTest,ParserBenchmarkRunnerTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 859 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsCompactLlmCorpusMetrics test` | red at first because compact metrics were absent and defaulted to 0.0, then pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `mvn test` | pass: 860 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=PdfBorderlessTableExtractionTest test` | red at first because no table was emitted; pass after fallback |
| `mvn -q -Dtest=PdfBorderlessTableExtractionTest,ParserBenchmarkRunnerTest,TableExtractionContractTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest test` | pass after tightening fallback to avoid sidebar/two-column false positives |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `mvn test` | pass: 902 tests, 0 failures, 0 errors |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test borderless_table_contract` | red at first because runtime emitted 0 tables; pass after Rust fallback |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` | pass: 10 tests |
| `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass |
| `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` | pass: 11 integration tests total across borderless/protocol contracts |
| `sh scripts/smoke-doctruth-runtime.sh` | pass with explicit bordered and borderless table checks |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar-borderless.sh` | pass |
| `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentCliOutputProfileTest,TrustDocumentRenderedOutputTest test` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest#markdownCleanPreservesCodeLinksAndEscapedTableCells test` | red at first because table cells did not escape brackets, then pass |
| `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest test` | pass |
| `mvn test` | pass: 861 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustAuditVerifierTest,TrustDocumentCliOutputProfileTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest test` | red at first compile for missing verifier/fromJsonFull, then pass |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for `TrustAuditVerifier` and `TrustDocument.fromJsonFull` |
| `mvn -q -Dtest=TrustAuditVerifierTest,TrustDocumentCliOutputProfileTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also validates `verify-audit` against sidecar full JSON and audit JSON |
| `mvn test` | pass: 867 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest#reviewHtmlRendersVisualBboxOverlayLayer test` | red at first because HTML review had semantic bbox anchors but no visual overlay layer, then pass |
| `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest,SidecarParserBackendTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass; now also validates HTML bbox overlay layer and unit overlay nodes |
| `mvn test` | pass: 868 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test` | red at first because anchored Markdown, review Markdown, plain text, and HTML review writer APIs did not exist; pass after implementation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for remaining render writer APIs |
| `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentStreamingRenderContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 874 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentCliWritersTest test` | red at first because `writeToPrintStream(...)` did not exist; pass after implementation |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test` | pass |
| `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentStreamingRenderContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 875 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentCliWritersTest test` | red at first because `writeSourceMap(...)` did not exist; pass after implementation |
| `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest test` | pass |
| `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 876 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test` | red at first because canonical/evidence hash input writer methods did not exist; pass after implementation |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentRenderedOutputTest,TrustAuditVerifierTest,TrustDocumentParserApiContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 877 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest test` | red at first because writer-backed byte counter methods did not exist; pass after implementation |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 878 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test` | red at first because source-map streaming hash helpers did not exist; pass after implementation |
| `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 879 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest test` | pass |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest,SidecarParserBackendTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 881 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliWritersTest test` | red at first because source-map direct writer APIs did not exist; pass after implementation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for source-map writer APIs |
| `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentSourceMapContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 882 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBackendContractTest test` | red at first because PDFBox `TrustPage` width was still 1000.0 and image hash was blank; pass after implementation |
| `mvn -q -Dtest=ParserBackendContractTest,TrustDocumentParserApiContractTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 883 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest test` | red at first because `parse(InputStream, ...)` still called `readAllBytes()`; pass after implementation |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ParserBackendContractTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `mvn test` | pass: 884 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=PdfPageImageRendererTest test` | red at first because `PdfPageImageRenderer` did not exist; pass after implementation |
| `mvn -q -Dtest=DocTruthCliTest#renderPagesWritesPngArtifactsAndManifest test` | red at first because `render-pages` was unknown; pass after implementation |
| `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test` | pass, snapshot updated for `PdfPageImageRenderer` |
| `mvn -q -Dtest=PdfPageImageRendererTest,DocTruthCliTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-page-images.sh` | pass |
| `mvn test` | pass: 886 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest#ocrPresetRoutesLowTextPdfThroughConfiguredLocalWorker test` | red at first because OCR preset still reported `pdfbox`; pass after v1 OCR routing |
| `mvn -q -Dtest=DocTruthCliTest#reviewPackageCanUseOcrPresetWithConfiguredLocalWorker test` | red at first because `review-package` did not accept `--preset`; pass after implementation |
| `mvn -q -Dtest=DocTruthCliTest#parseTrustJsonCanUseOcrPresetWithConfiguredLocalWorker test` | red at first because TrustDocument parse output still reported `pdfbox`; pass after routing TrustDocument formats through v1 parser |
| `sh scripts/smoke-doctruth-ocr-preset.sh` | pass |
| `/Users/jameslee/Library/Python/3.10/bin/rapidocr --help` | failed: local NumPy C-extension mismatch, so raw rapidocr CLI is not verified |
| `mvn -q -Dtest=TrustDocumentParserApiContractTest,DocTruthCliTest,LocalOcrWorkerEngineTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn test` | pass: 890 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-review-package.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `mvn -q -Dtest=DocTruthCliTest#reviewPackageWritesHtmlDocumentAndPageImages test` | red at first because `review-package` was unknown; pass after implementation |
| `sh scripts/smoke-doctruth-review-package.sh` | pass |
| `mvn -q -Dtest=DocTruthCliTest,ArchitectureContractTest,PublicApiSnapshotTest test` | pass |
| `mvn test` | pass: 887 tests, 0 failures, 0 errors |
| `sh scripts/smoke-doctruth-page-images.sh` | pass |
| `sh scripts/smoke-doctruth-cli-sidecar.sh` | pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest test` | pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `mvn test` | pass: 932 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsStrictWarningFalseNegativeRate,ParserBenchmarkRunnerTest#benchmarkStrictWarningMetricMatchesParserAndUnitWarnings,ParserBenchmarkCorpusTest#manifestEnforcesMaximumThresholds,ParserBenchmarkCorpusCliTest#benchmarkCorpusMaximumThresholdFailureReturnsRuntimeError test` | red at first because maximum-threshold APIs and warning metric did not exist; pass after implementation |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `mvn test` | pass: 936 tests, 0 failures, 0 errors |
| `git diff --check` | pass |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsParserLatencyForEachCase,ParserBenchmarkRunnerTest#benchmarkAggregatesParserLatencyPercentiles,ParserBenchmarkCorpusCliTest#benchmarkCorpusLatencyMaximumFailureUsesAggregateMetrics,ParserBenchmarkCorpusCliTest#benchmarkCorpusJsonPrintsMachineReadableMetrics,ParserBenchmarkCorpusCliTest#benchmarkCorpusPrintsReadableSummaryAndPassesThresholds test` | red at first because latency case metadata and aggregate metrics did not exist; pass after implementation |
| `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test` | pass |
| `sh scripts/smoke-doctruth-benchmark-corpus.sh` | pass |
| `mvn test` | pass: 939 tests, 0 failures, 0 errors |
| `git diff --check` | pass |

## Remaining PRD Coverage

- Real PDF extraction inside the Rust `doctruth-runtime`. The binary now exists
  and extracts text-layer PDFs into citeable `LINE_SPAN` units. For simple
  content streams with `Tf`/`Td`/`Tj`, it now emits non-page-fallback text
  bboxes. It also recovers simple generated bordered-grid tables into
  `TrustTable`, `TrustTableCell`, and `TABLE_CELL` units with bboxes. It now
  also recovers generated short aligned borderless text matrices into
  table/cell output and handles a generated positioned two-column text fixture
  by ordering units visually by column. It now also preserves generated
  horizontal column spans and vertical row spans for bordered merged cells. It
  now reads MediaBox page dimensions and, when a configured renderer or local
  `pdftoppm` is available, hashes actual rendered PNG bytes for per-page
  `sha256:` metadata hashes; otherwise it falls back to stable content/dimension
  hashes. For model-assisted presets that the runtime cannot execute
  locally, it now emits per-model severe fallback warnings and downgrades audit
  grade instead of silently succeeding. It now also merges adjacent generated
  bordered-table continuation pages with repeated headers while keeping
  continued table-cell unit evidence on the original page. It still does not provide
  font-metric-perfect text bboxes, semantic layout-region classification,
  persisted Rust page image artifacts, OCR, model-assisted table recognition, or
  real-world labeled table accuracy.
- Real model-assisted layout/table/OCR runtime execution.
- Basic GFM pipe-table rendering now exists for structured tables in clean
  Markdown and Markdown source-map output. `compact_llm` now preserves table ids
  and parser/unit warnings in a deterministic compact wire shape, and it now
  appends optional bbox metadata for citeable units that carry normalized
  bboxes. Compact LLM output now also has source-map sidecars that resolve
  rendered compact unit text offsets back to unit ids and evidence spans, and
  benchmark metrics now report compact size reduction, round-trip health, and
  compact source-map coverage so corpora can gate the LLM/RAG wire path.
  `html_review` now exposes bbox-compatible attributes for units with normalized
  bboxes and semantic table/cell nodes with table ids, cell ids, unit/evidence
  ids, and cell bboxes, plus page containers with page number, dimensions,
  text-layer availability, and page image hashes. Java/PDFBox backend pages now
  use 72 DPI PDFRenderer page dimensions and SHA-256 hashes of rendered PNG page
  images. SDK and CLI now also persist deterministic `page-%04d.png` artifacts
  with a `page-images.json` manifest through `PdfPageImageRenderer` and
  `doctruth render-pages`. CLI now also writes a static local review package
  with `review.html`, `trust-document.json`, page PNGs, and page-image manifest
  through `doctruth review-package`. HTML review now also emits a page-scoped visual bbox overlay layer for
  units, tables, and cells. Clean Markdown now preserves fenced code blocks and links and escapes
  Markdown-sensitive table cell brackets, pipes, and backslashes. A full GFM
  renderer implementation with a dedicated Markdown/HTML parser stack,
  finalized compact-wire spec, persisted Rust page image artifacts, interactive
  browser review UI, and
  cross-format parity remains open.
- Streaming parser/renderer implementation for multi-GB files. All current
  SDK `TrustDocument` render formats now have byte-stable writer APIs that
  avoid one full-payload write into caller-owned writers. CLI `--out` routes
  all current TrustDocument output formats through writer paths, and
  TrustDocument stdout output now uses the same writer dispatch. Source-map
  sidecar file serialization now uses a writer path, and SDK/CLI source-map
  sidecar writers can write directly from `TrustDocument` without requiring
  callers to materialize `TrustRenderedDocument`. The compatibility
  source-map APIs still return `TrustRenderedDocument`, and source-map JSON
  still includes full rendered text by contract. Parser ingestion still
  materializes `TrustDocument`. Canonical and evidence hashing now compute
  deterministic hash inputs through writer-backed digest paths. Benchmark
  compact-size metrics now count full JSON and compact LLM bytes through
  writer-backed byte counters. Source-map verifier file hashing now uses
  streaming file reads. CLI parse and SDK path parse source hashing now use
  streaming file reads. SDK input-stream parsing now copies input incrementally
  to a temporary file instead of calling `InputStream.readAllBytes()`, while the
  byte-array upload API still receives materialized bytes by definition.
- Clean Markdown source-map sidecars now include source and rendered content
  hashes, and `verify-source-map` can validate rendered Markdown plus optional
  source document hashes. Signed `TrustDocument` audit package output now uses
  the shared `SignatureProvider` contract at the SDK boundary. Local audit
  replay verification now compares Audit JSON against full TrustDocument JSON
  through SDK and CLI paths. External timestamping remains open.
- Audit JSON now includes source, canonical document, and evidence hashes, so
  parser audit output is explicitly hashable and sidecar-smoke-tested. SDK-level
  signing/wrapping and local replay verification now exist for `TrustDocument`
  audit JSON. External timestamping, notarization, key management, and
  legal-hold/WORM semantics remain open.
- Anchored Markdown now includes bbox metadata for units that have normalized
  bboxes. More complete Markdown parity for lists and warning blocks over a
  labeled corpus remains open.
- Review Markdown now exposes parser and unit warnings. It still does not
  replace the visual HTML review surface or a signed audit package.
- Plain text output now exists as SDK `toPlainText()`, CLI `--format plain`,
  backend `plain_text` capability, PRD/docs contract, and CLI sidecar smoke
  coverage. It is a clean consumption view only and intentionally omits audit
  evidence syntax; replay workflows still need JSON/source-map/evidence outputs.
- MCP local stdio coverage now includes document parsing, layout regions, table
  cells, evidence span lookup, and quote-vs-span citation verification.
  `skills/doctruth` now packages the agent-facing workflow and local MCP
  bootstrap script. MCP model cache warmup/preflight now verifies caller-
  supplied local model descriptors against a cache directory without implicit
  downloads. Remote/distributed MCP deployment remains outside this slice.
- Full parser quality benchmark corpus with labeled PDF fixtures. Threshold
  enforcement, expected-document metrics, manifest-based corpus loading, and a
  CLI/smoke gate now exist. The corpus runner now also supports `maximums` for
  lower-is-better metrics and reports `strict_warning_false_negative_rate`
  against expected severe parser/unit warnings. It now records parse latency
  for PDF-backed cases, reports corpus-level `parser_latency_p50/p95`, and can
  gate `maximums.parser_latency_p95`. It now also reports and gates
  `section_boundary_f1` from recovered heading-like section boundary lines and
  `evidence_span_accuracy` from expected text-line coverage by actual
  evidence-bearing units. It now reports per-case `rss_peak_mb` and
  `model_cache_size_mb` through benchmark resource observations. Corpus output
  also reports `compact_llm_size_reduction_min`, and manifest `minimums` can
  gate the compact LLM corpus-level reduction target. The real human-labeled
  corpus and parser-quality targets still need to be added.
- End-to-end Java CLI to Rust sidecar smoke now exists and passes, but it only
  covers text-layer line spans on a generated PDF. It does not prove layout,
  OCR, table extraction, precise bbox quality, or labeled corpus accuracy.
- Real PDF benchmark fixture support now exists for Java/PDFBox parser quality:
  benchmark cases can parse a PDF path directly and threshold
  `reading_order_f1`, `quote_anchor_accuracy`, and `bbox_coverage`. This is a
  fixture gate, not a full labeled benchmark corpus.
- Real PDF expected-bbox fixture support now exists: generated PDF benchmark
  cases can carry expected `TrustDocument` bbox labels and threshold `bbox_iou`.
  This is still a generated fixture, not a human-labeled real-world corpus.
- Real PDF bordered-table fixture support now exists for the Java/PDFBox
  baseline: generated PDFs with explicit grid lines can be parsed into
  `TableSection` and scored through `table_cell_f1`. This is a conservative
  bordered-table path, not borderless table recognition or model-assisted
  table structure extraction.
- Real PDF table-region fixture support now exists for the Java/PDFBox
  baseline: generated PDFs with explicit grid lines can preserve the table
  region bbox into `TrustTable.boundingBox` and gate it through
  `table_region_iou`.
- Real PDF table-cell bbox support now exists for the Java/PDFBox baseline:
  generated PDFs with explicit grid lines can preserve per-cell bounding boxes
  into `TrustTableCell.boundingBox` and `TABLE_CELL` unit locations. Java/PDFBox
  also now has a conservative borderless fallback for short, non-bold, aligned
  text matrices without grid lines, with regression coverage proving it does
  not swallow sidebar language rows or two-column resume layout blocks. Java/PDFBox
  now also preserves horizontal merged-cell column spans and vertical merged-cell
  row spans on generated bordered table fixtures and gates both behaviors
  through `table_cell_f1`. Rust runtime now also preserves generated horizontal
  merged-cell column spans and vertical row spans through protocol tests and
  sidecar smoke. Java/PDFBox and Rust sidecar now both merge adjacent generated
  bordered-table continuation pages with repeated headers, dedupe the
  second-page header, and keep continued cell units on their original source
  page. This is still not bold-header borderless tables, model-assisted table
  structure extraction, OCR-backed tables, or labeled real-world table
  accuracy.
- Rust runtime bordered-table smoke support now exists for generated PDFs with
  explicit grid lines, runtime smoke explicitly checks a generated borderless
  aligned text table, and runtime plus Java CLI sidecar smoke now check
  generated horizontal merged cells and vertical row spans. A dedicated Java CLI
  sidecar borderless smoke also verifies JSON, clean Markdown, and plain-text
  rendering from the Rust sidecar output. This proves the Java CLI sidecar can
  consume runtime `TrustTable`/`TABLE_CELL` JSON and render GFM tables, but it
  is still a generated-fixture gate rather than a labeled real-world table
  benchmark.
- OCR routing is not excluded from the runtime plan. Java currently has a local
  OCR worker protocol, `ParserPreset.OCR`, doctor readiness reporting, low-
  confidence audit gating, a fake MNN-compatible OCR preset smoke, and a
  DocTruth-owned `doctruth-rapidocr-mnn-worker` adapter packaged into source
  installs and release tarballs. Doctor JSON now separates worker executable
  availability from `--doctor` runtime readiness. The worker now handles
  RapidOCR 3.8-style array outputs, and an opt-in real smoke proves isolated
  RapidOCR + ONNXRuntime backend initialization, direct OCR, and Java CLI
  `parse --preset ocr` over a generated scanned PDF. Parser benchmarks now
  expose `ocr_text_accuracy`, and corpus manifests can request `preset: "ocr"`
  so generated scanned-PDF OCR cases can be threshold-gated through
  `benchmark-corpus`. The generic Java jar still intentionally avoids bundling
  OCR model binaries. MNN-specific backend installation and labeled real-world
  scanned-PDF OCR accuracy remain open.

These are intentionally not claimed as complete in this Java contract slice.

## Final Verification For Current TDD Slice

- `sh scripts/smoke-doctruth-benchmark-corpus.sh` passed.
- `mvn test` passed: 967 tests, 0 failures, 0 errors.
- `mvn verify -DskipITs` passed: 980 tests, 0 failures, 0 errors, coverage
  checks met.
- `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded` passed:
  Surefire 980 tests, 0 failures, 0 errors; Failsafe 16 tests, 0 failures,
  0 errors, 2 skipped.
- Recorded real-world PDF fixture result: total=383, success=379, failure=4,
  bugs=0, passRate=0.9896. The four failures are malformed PDFs with missing
  root object trailer errors, not parser bugs.
- Recorded PDF fixture timing: total parse time 17840 ms, mean 46580 us.
- `git diff --check` passed.

Current status: this is not full PRD completion. The completed scope is the
contract/runtime TDD slice: local parser runtime contract, CLI/SDK surfaces,
Rust sidecar MVP, model/OCR handoff contracts, generated benchmark gates, and
recorded-corpus regression safety.

Full PRD status remains open until these are done:

- Rust runtime is the parser core for the current v1 runtime slice, not only a
  sidecar MVP.
- Rust runtime uses a `pdf_oxide`-backed PDF backend for text, page geometry,
  rendering, bbox evidence, content-stream safety checks, and line-table/debug
  extraction. Current status: `doctruth-runtime` reports `pdf_oxide` as the
  default backend and no longer depends on `lopdf`.
- Java/PDFBox is wrapper/legacy/oracle only, not a primary parser core or
  hidden default.
- Real RT-DETR/TATR/SLANeXT release workflow has been run remotely and produced
  artifact/log evidence, not only local workflow contract tests.
- Final stage: OCR quality and multi-layout/table/bbox/source-map quality are
  proven against broad human-reviewed corpora collected through a review
  workstation/workflow.

## Full PRD Continuation Phases

| Phase | Status | Deliverable | Verification |
| --- | --- | --- | --- |
| 201. Rust library core boundary RED/MVP | complete | `doctruth-runtime` is no longer binary-only: core protocol functions are callable from the Rust library crate, while `src/main.rs` is a thin process wrapper | `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` |
| 202. Rust library core sidecar verification | complete | Existing runtime and Java CLI sidecar smokes still pass after the lib/bin split | `sh scripts/smoke-doctruth-runtime.sh`, `sh scripts/smoke-doctruth-cli-sidecar.sh` |
| 203. Rust default parser selection RED/MVP | complete | Java SDK/CLI can prefer Rust runtime by default when configured/packaged, with PDFBox as explicit fallback | focused Java CLI/SDK tests |
| 204. Rust default parser verification | complete | Full Java focused tests plus runtime/sidecar smokes prove the default path does not regress | focused Maven, Cargo, smoke |
| 205. Zero-config packaged Rust runtime RED/MVP | complete | Source install and release tarballs include `bin/doctruth-runtime`, and launchers auto-export `DOCTRUTH_RUNTIME_COMMAND` when the runtime is present | `CliPackagingContractTest`, install/release smoke |
| 206. Zero-config packaged Rust runtime verification | complete | Installed and release-packaged launchers parse a PDF through Rust sidecar without user runtime env setup | install smoke, `scripts/smoke-cli-release.sh` |
| 207. Real model artifact acceptance harness RED/MVP | complete | Opt-in smoke validates SHA-pinned user-supplied ONNX artifacts through cache warm, ONNXRuntime doctor, model-worker parse, and expected task/model assertions | `scripts/smoke-doctruth-real-model-artifact.sh`, synthetic artifact execution |
| 208. Real model artifact harness verification | complete | Existing ONNX synthetic decoder smokes still pass, skip path is safe without env, and the harness can execute a supplied artifact manifest | ONNX smokes, harness skip, harness synthetic run |
| 209. Curated production model artifacts | pending | CI or fixture storage supplies actual RT-DETR/TATR/SLANeXT-compatible artifacts and runs the real artifact smoke against them | opt-in/recorded real artifact smoke with model ids |
| 210. OCR quality corpus | pending | Labeled scanned-PDF corpus gates `ocr_text_accuracy` and low-confidence blocking | benchmark-corpus recorded OCR run |
| 211. Human-labeled parser accuracy corpus | pending | Multi-layout/table/bbox/source-map labels gate PRD parser quality metrics | benchmark-corpus recorded accuracy run |
| 212. Generated OCR wrong-label corpus gate | complete | Generated OCR corpus smoke and CLI contract fail when OCR expected Markdown labels do not match OCR output | `ParserBenchmarkCorpusCliTest`, `scripts/smoke-doctruth-benchmark-corpus.sh` |
| 213. Real OCR runtime corpus smoke | complete | Opt-in RapidOCR + ONNXRuntime smoke feeds generated scanned-PDF OCR output through `benchmark-corpus` and gates `ocr_text_accuracy` | `DOCTRUTH_REAL_OCR_CORPUS_SMOKE=1 sh scripts/smoke-doctruth-real-ocr-corpus.sh` |
| 214. Public TATR artifact execution smoke | complete | Opt-in Xenova Table Transformer quantized ONNX smoke downloads/caches a real artifact and executes it through Java CLI + ONNX worker | `DOCTRUTH_REAL_TATR_SMOKE=1 sh scripts/smoke-doctruth-real-tatr-artifact.sh` |
| 215. Rendered-page ONNX vision input | complete | ONNX worker feeds 4D vision models with a rendered PDF page tensor when `pdftoppm`/Pillow are available and reports `metrics.inputSource=rendered_page` | real TATR smoke, ONNX resource/TATR/layout smokes |
| 216. Real TATR row/column post-processing RED/MVP | complete | Public Xenova TATR artifact is decoded with its real table/row/column label set and row-column intersections become multi-row/multi-column `TABLE_CELL` evidence | real TATR smoke red, then green |
| 217. Real TATR post-processing verification | complete | Synthetic TATR, low-confidence table, model worker, resource, layout, packaging, and real TATR smokes/tests still pass after the decoder split | focused smokes, `CliPackagingContractTest`, `git diff --check` |
| 218. Real RT-DETR artifact adapter RED/MVP | complete | Public Kreuzberg document-layout RT-DETR ONNX artifact runs through rendered page input, `orig_target_sizes`, `labels`/`boxes`/`scores` decoding, and Java CLI model-worker harness | `DOCTRUTH_REAL_RTDETR_SMOKE=1 sh scripts/smoke-doctruth-real-rtdetr-artifact.sh` |
| 219. Real RT-DETR adapter verification | complete | Synthetic layout/TATR smokes and packaging contract still pass after adding real RT-DETR input/output support | layout/TATR smokes, `CliPackagingContractTest`, `git diff --check` |
| 220. SLANeXT/PaddleOCR table worker adapter RED/MVP | complete | `doctruth-slanext-table-worker` exposes a local PaddleOCR/SLANeXT JSON model-worker adapter and preserves model-produced table cells through `TrustDocument` | fake PaddleOCR worker smoke red, then green |
| 221. SLANeXT worker packaging verification | complete | Source install, release tarball, Homebrew formula, and release smoke include/check the SLANeXT worker adapter | `CliPackagingContractTest`, SLANeXT worker smoke |
| 222. Real SLANeXT runtime smoke | complete | Run the opt-in real PaddleOCR/SLANeXT runtime smoke in an isolated Python 3.10 environment with PaddleOCR/Paddle installed | `PATH=/tmp/doctruth-slanext-venv/bin:$PATH DOCTRUTH_REAL_SLANEXT_SMOKE=1 sh scripts/smoke-doctruth-real-slanext-artifact.sh` |
| 223. Human-labeled corpus metadata RED/MVP | complete | `kind: human-labeled` benchmark manifests require label metadata and explicit thresholds for declared required metrics | corpus unit/CLI tests red, then green |
| 224. Human-labeled corpus smoke verification | complete | Packaged benchmark corpus smoke accepts human-labeled manifests, rejects missing required metric thresholds, and emits JSON metadata for CI | benchmark corpus smoke, public API snapshot |
| 225. Public human-labeled remote PDF smoke | complete | W3C remote-PDF corpus smoke uses `kind: human-labeled`, label-set metadata, required metrics, and verifies those fields in CLI JSON | `sh scripts/smoke-doctruth-real-pdf-corpus.sh` |
| 226. Parser-accuracy coverage contract RED/MVP | complete | `qualityProfile: parser-accuracy` human-labeled manifests require declared `requiredTags` and `minCasesPerTag`, and fail when tagged case coverage is too small | corpus unit/CLI tests red, then green |
| 227. Parser-accuracy coverage smoke verification | complete | Benchmark corpus smoke covers parser-accuracy JSON metadata and coverage failure diagnostics | `scripts/smoke-doctruth-benchmark-corpus.sh`, public API snapshot |
| 228. Real model suite smoke RED/MVP | complete | `scripts/smoke-doctruth-real-model-suite.sh` provides one opt-in entrypoint for RT-DETR, TATR, and SLANeXT real runtime smokes and supports `DOCTRUTH_SLANEXT_PYTHON` for isolated PaddleOCR environments | packaging test red, then green; suite skip and real run |
| 229. Real model suite packaging verification | complete | Source install, release tarball, and release smoke include/check the real model suite script | `CliPackagingContractTest`, release smoke contract |
| 230. Release workflow real-model gate RED/MVP | complete | Release workflow installs Python/poppler/model dependencies and runs `DOCTRUTH_REAL_MODEL_SUITE=1 scripts/smoke-doctruth-real-model-suite.sh`; CI also checks the safe skip path | `WorkflowContractTest` red, then green |
| 231. Parser-accuracy seed corpus smoke RED/MVP | complete | Generated seed corpus covers multi-layout, table, OCR, bbox, and source-map tags through a `qualityProfile: parser-accuracy` manifest | packaging contract red, then green; seed smoke |
| 232. Parser-accuracy seed corpus CI verification | complete | CI workflow runs the generated seed corpus smoke so parser-accuracy manifest plumbing stays executable on every PR | `WorkflowContractTest`, seed smoke |
| 233. Parser-accuracy case label contract RED/MVP | complete | Parser-accuracy human-labeled cases require `labelId` and non-empty `tags`; benchmark result JSON carries both fields per case | corpus unit/CLI tests red, then green |
| 234. Parser-accuracy case label verification | complete | Seed corpus smoke asserts label ids and coverage tags survive through `benchmark-corpus --json`; public API snapshot updated with label/expectation value objects | focused corpus tests, seed smoke, public API snapshot |
| 235. Parser-accuracy review type RED/MVP | complete | Parser-accuracy manifests require `labeling.reviewType` and report `generated-seed` vs `human-reviewed` in CLI JSON | corpus unit/CLI tests red, then green |
| 236. Parser-accuracy review type verification | complete | Seed corpus smoke asserts `reviewType: generated-seed`; benchmark corpus smoke asserts `reviewType: human-reviewed`; public API snapshot includes `reviewType()` | focused corpus tests, smokes, public API snapshot |
| 237. Final-stage real-world labeled corpus population | final-stage | Add broad real-world PDFs with human-reviewed labels for multi-layout/table/OCR/bbox/source-map quality after the runtime/boundary work is complete | recorded benchmark-corpus run |
| 238. Rust benchmark-corpus protocol RED/MVP | complete | `doctruth-runtime` accepts `benchmark_corpus` manifests with parser-accuracy label metadata, expected Markdown/TrustDocument paths, tag coverage, and metric minimums | Rust contract test red on `UNKNOWN_COMMAND`, then green |
| 239. Rust benchmark-corpus smoke verification | complete | Local smoke exercises the Rust runtime corpus protocol end to end without Java CLI | `cargo fmt --check`, `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh` |
| 240. Rust corpus source hash RED/MVP | complete | Rust `benchmark_corpus` rejects mismatched case `sourceSha256` before parsing so labels remain hash-bound to source PDFs | Rust contract test red on unexpected success, then green |
| 241. Rust model-worker handoff RED/MVP | complete | `doctruth-runtime parse_pdf` routes model-assisted presets to `DOCTRUTH_RUNTIME_MODEL_COMMAND`/`DOCTRUTH_MODEL_COMMAND` before heuristic fallback and maps bad worker JSON to `MODEL_WORKER_FAILED` | Rust model-worker contract test red, then green |
| 242. Rust model-worker smoke verification | complete | Local smoke proves Rust runtime can call a configured model worker and return worker-produced `TrustDocument` without Java CLI | `sh scripts/smoke-doctruth-runtime-model-worker.sh` |
| 243. Rust benchmark-corpus preset routing RED/MVP | complete | Rust corpus cases can declare `preset`, and model-assisted corpus cases run through the Rust model-worker handoff before metric thresholds are evaluated | Rust corpus test red at `reading_order_f1 0`, then green |
| 244. Rust benchmark-corpus preset smoke verification | complete | Runtime corpus smoke runs a `table-lite` corpus case through fake model worker and asserts preset metadata in report JSON | `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh` |
| 245. Rust real-model execution migration | complete | Rust now controls model-assisted execution through worker handoff, RT-DETR/TATR have a Rust-runtime real-artifact entrypoint, RapidOCR and SLANeXT have generated real Rust-route smokes, SLANeXT/OCR have Rust-runtime worker-protocol smokes, and ADR 0011 accepts external local workers as the v1 model execution boundary | Phases 251, 254, 255, 256, 257, 258, and 259 complete |
| 246. Final-stage Rust broad human-reviewed corpus | final-stage | Run Rust `benchmark_corpus` against broad human-reviewed multi-layout/table/OCR/bbox/source-map corpus after review-workstation labels exist | recorded Rust benchmark-corpus report |
| 247. Layered parser output contract RED/MVP | complete | Add `ContentBlock` and `ParseTrace` contracts for MinerU-style layered outputs without copying MinerU schema | Rust protocol tests red on missing `contentBlocks`/`parseTrace`, then green |
| 248. Rust layered output ownership | complete | Rust runtime emits or can derive `content_blocks.json` and `parse_trace.json` from page/block/line/span observations | Cargo tests, runtime smoke, benchmark corpus smoke, model-worker smoke |
| 249. CLI layered output profiles | complete | `doctruth parse` can write `content_blocks.json`, `parse_trace.json`, and clean Markdown with source-map links from the same canonical parse | `TrustDocumentCliOutputProfileTest`, `scripts/smoke-doctruth-cli-sidecar.sh` |
| 250. Visual trace artifact contract | complete | `review-package` writes `content_blocks.json`, `parse_trace.json`, `layout-debug.html`, and `span-debug.html`; layout/span debug artifacts use the same trace ids as `parse_trace.json` for parser QA | `DocTruthCliTest`, `scripts/smoke-doctruth-review-package.sh` |
| 251. Rust real-model handoff smoke | complete | Rust runtime has a safe-by-default `parse_pdf` smoke that routes model-assisted parsing through `DOCTRUTH_RUNTIME_MODEL_COMMAND`, validates required model identities, and can be pointed at a real runtime model worker through `DOCTRUTH_RUNTIME_REAL_MODEL_COMMAND` | `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`, `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, `sh scripts/smoke-doctruth-runtime-real-model-suite.sh` |
| 252. Parser-accuracy readable evidence output | complete | Human-labeled/parser-accuracy corpus readable output exposes label/review/coverage evidence instead of hiding it in JSON-only reports | `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`, `sh scripts/smoke-doctruth-real-pdf-corpus.sh`, `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh` |
| 253. Full verification closure for current TDD slice | complete | Current TDD slice closes with Java recorded verify, Rust runtime verify, runtime/CLI/corpus smokes, coverage gate, and whitespace check passing; production model artifacts and broad human-reviewed corpus remain separate pending PRD phases | `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`, `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`, `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, `sh scripts/smoke-doctruth-runtime.sh`, `sh scripts/smoke-doctruth-runtime-model-worker.sh`, `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`, `sh scripts/smoke-doctruth-runtime-real-model-suite.sh`, `sh scripts/smoke-doctruth-cli-sidecar.sh`, `sh scripts/smoke-doctruth-review-package.sh`, `sh scripts/smoke-doctruth-model-worker.sh`, `sh scripts/smoke-doctruth-benchmark-corpus.sh`, `sh scripts/smoke-doctruth-real-pdf-corpus.sh`, `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`, `git diff --check` |
| 254. Rust runtime real RT-DETR/TATR artifact entrypoint | complete | Real public RT-DETR and TATR ONNX artifact smokes can now be launched through `doctruth-runtime parse_pdf`, with Rust normalizing worker envelopes to `parserRun.backend=rust-sidecar+model-worker` while preserving `workerBackend`; the script is packaged and skip-safe by default | `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`, `DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1 sh scripts/smoke-doctruth-runtime-real-model-artifacts.sh`, `mvn -q -Dtest=CliPackagingContractTest test` |
| 255. Rust runtime SLANeXT/OCR worker protocol | complete | `doctruth-runtime parse_pdf` can now route `table-server` to the SLANeXT/PaddleOCR worker and `ocr` to the RapidOCR worker, with both returning TrustDocument envelopes normalized through the Rust runtime; packaging includes both runtime worker smokes | `sh scripts/smoke-doctruth-runtime-slanext-worker.sh`, `sh scripts/smoke-doctruth-runtime-ocr-worker.sh`, `sh scripts/smoke-doctruth-rapidocr-worker.sh`, `sh scripts/smoke-doctruth-ocr-preset.sh`, `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, `mvn -q -Dtest=CliPackagingContractTest,DocTruthCliDoctorCompletionTest test` |
| 256. Rust runtime real SLANeXT/OCR artifact runs | complete | Run opt-in real PaddleOCR/SLANeXT and RapidOCR/MNN workers through `doctruth-runtime parse_pdf`, not only through Java CLI/direct worker paths or fake Rust worker modules | OCR complete via phase 258; SLANeXT complete via phase 259 |
| 257. Rust-native/in-process model execution decision | complete | Production RT-DETR/TATR/SLANeXT/OCR model execution remains external-worker based for v1, with Rust owning orchestration, manifest/cache validation, request envelopes, response normalization, benchmark execution, and audit propagation | `docs/adr/0011-model-execution-worker-boundary.md`, Rust runtime model-worker tests and real-route smokes |
| 258. Rust runtime real RapidOCR generated corpus | complete | Generated scanned-PDF OCR fixture runs through real RapidOCR + ONNXRuntime via `doctruth-runtime parse_pdf` and the packaged RapidOCR worker, with runtime-normalized `OCR_REGION` output and bbox evidence | `DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE=1 sh scripts/smoke-doctruth-runtime-real-ocr-corpus.sh` |
| 259. Rust runtime real SLANeXT generated table | complete | Generated table fixture runs through installed PaddleOCR/SLANeXT via `doctruth-runtime parse_pdf`, not Java CLI, and records table-cell output; the smoke can create an isolated `paddleocr+paddlepaddle` venv | `DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE=1 DOCTRUTH_SLANEXT_VENV=... sh scripts/smoke-doctruth-runtime-real-slanext-artifact.sh` |
| 260. Rust benchmark expected-label metrics RED/MVP | complete | Rust `benchmark_corpus` now reads expected `TrustDocument` JSON labels and scores core parser-accuracy metrics beyond plumbing: `bbox_iou`, `evidence_span_accuracy`, `table_cell_f1`, and `ocr_text_accuracy` | Red Rust contract test failed on missing `bbox_iou`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`, `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh` |
| 261. Human-reviewed corpus scale gate RED/MVP | complete | Parser-accuracy manifests with `reviewType: human-reviewed` must declare and satisfy `labeling.minTotalCases`; generated seed corpora remain small plumbing gates | Java and Rust red tests, `ParserBenchmarkCorpusTest`, `ParserBenchmarkCorpusCliTest`, Rust benchmark corpus contract, benchmark corpus smokes |
| 262. Human-reviewed source hash pinning RED/MVP | complete | Human-reviewed parser-accuracy cases must include `sourceSha256`, and Java/Rust reject missing pins or SHA mismatches before accepting labels | Java RED test for missing pin, Rust RED test for missing pin, local SHA mismatch test, benchmark corpus smoke |
| 263. Human-reviewed core metric coverage RED/MVP | complete | Human-reviewed parser-accuracy manifests must declare the core parser-quality metric set so bbox/table/OCR/evidence quality cannot be silently omitted | Java and Rust RED tests for incomplete metrics, parser corpus focused tests, benchmark corpus smoke |
| 264. Human-reviewed core tag coverage RED/MVP | complete | Human-reviewed parser-accuracy manifests must declare the core coverage tags `multi-layout`, `table`, `ocr`, `bbox`, and `source-map` so broad corpus claims cannot shrink to one easy bucket | Java and Rust RED tests for incomplete tags, CLI readable/JSON fixture, benchmark corpus smoke |
| 265. Recorded parser-accuracy report artifact RED/MVP | complete | `doctruth benchmark-corpus --report-out <report.json>` writes an auditable report artifact with report format, resolved manifest path, label/review metadata, metrics, and per-case label/tag evidence | RED CLI test for missing option, `ParserBenchmarkCorpusCliTest`, benchmark corpus smoke, full Maven suite |
| 266. Rust recorded benchmark report artifact RED/MVP | complete | `doctruth-runtime` `benchmark_corpus` accepts `report_path` and writes the same v1 recorded report artifact shape as the Java CLI report-out path | RED Rust contract test for missing file, full runtime cargo test, runtime benchmark smoke |
| 267. Recorded source hash evidence RED/MVP | complete | Java and Rust recorded benchmark reports include per-case `sourceSha256`, so archived parser-accuracy reports prove the exact source bytes tied to human-reviewed labels | Java/Rust RED report assertions, public API snapshot, benchmark smokes, full Maven and runtime tests |
| 268. Recorded manifest hash evidence RED/MVP | complete | Java and Rust recorded benchmark reports include `manifestSha256`, so archived parser-accuracy reports prove the exact labels, thresholds, and case list used for the run | Java/Rust RED report assertions, benchmark smokes, focused Java/Rust tests |
| 269. Recorded threshold criteria RED/MVP | complete | Java and Rust recorded benchmark reports include copied `minimums` and `maximums`, so archived reports are self-contained about the pass/fail criteria used for the run | Java/Rust RED report assertions, benchmark smokes, focused Java/Rust tests |
| 270. Recorded coverage counts RED/MVP | complete | Java and Rust recorded benchmark reports include actual `caseCount` and `casesPerTag`, so archived parser-accuracy reports prove what coverage ran instead of only declaring manifest requirements | Java/Rust RED report assertions, benchmark smokes, focused Java/Rust tests |
| 271. Recorded report verifier RED/MVP | complete | `doctruth verify-benchmark-report <report.json>` verifies report format, pass status, manifest hash, copied thresholds, actual coverage counts, and source pins without rerunning the parser | Java RED verifier tests, help/completion coverage, benchmark smoke valid/tampered report paths |
| 272. Recorded coverage threshold verifier RED/MVP | complete | `verify-benchmark-report` verifies copied `minCasesPerTag`/`minTotalCases` against manifest semantics and confirms actual report coverage satisfies those thresholds | Java RED threshold-tamper test, benchmark smoke tampered threshold path, focused Java contract tests |
| 273. Rust recorded report verifier parity RED/MVP | complete | `doctruth-runtime` writes `minCasesPerTag` into recorded reports and accepts `verify_benchmark_report` to validate runtime-produced report format, manifest hash, coverage counts, coverage thresholds, and source pins without Java CLI | Rust RED verifier tests, full runtime cargo test, runtime benchmark smoke valid/tampered report paths |
| 274. Rust benchmark maximum threshold gate RED/MVP | complete | Rust `benchmark_corpus` now enforces manifest `maximums` for lower-is-better metrics instead of only copying them into reports | Rust RED maximum failure test, full runtime cargo test, runtime/Java benchmark smokes |
| 275. Recorded metric threshold verifier RED/MVP | complete | Java and Rust report verifiers re-check recorded metric values against copied `minimums`/`maximums`, falling back to per-case metrics when aggregate metrics are absent | Java/Rust RED metric-tamper tests, Java/Rust benchmark smokes, focused Java/Rust suites |
| 276. Recorded aggregate metric consistency verifier RED/MVP | complete | Java and Rust report verifiers recompute aggregate metrics from case-level metrics and reject reports whose aggregate/case metric evidence diverges | Java/Rust RED aggregate-tamper tests, Java/Rust benchmark smokes, focused Java/Rust suites |
| 277. Recorded coverage map exactness RED/MVP | complete | Java `verify-benchmark-report` now rejects extra forged `casesPerTag` entries instead of only checking tags present in actual cases, matching Rust verifier exact-map semantics | Java RED extra-tag test, benchmark smoke extra-tag tamper path, focused Java contract tests |
| 278. OCR runtime-first parser selection RED/MVP | complete | Java SDK OCR preset now prefers a configured Rust runtime before Java/PDFBox legacy/oracle mode, so OCR no longer bypasses the Rust-core path when sidecar is available | Java RED OCR runtime-first test, parser API tests, runtime smoke, CLI sidecar smoke OCR preset path |
| 279. Runtime status docs reconciliation | complete | Runtime README and parser capability matrix now describe current Rust runtime capabilities honestly while preserving limits around unconditional default status, external-worker heavy models, and broad accuracy proof | docs-only worker patch, `git diff --check` |
| 280. Path-first SDK backend selection RED/MVP | complete | SDK now has a path-first `parsePdf(...).withParser(...).backend(AUTO|PDFBOX|SIDECAR)` TrustDocument parser path, so Rust auto mode and explicit Java/PDFBox legacy/oracle mode are both developer-visible contracts | Java RED SDK tests for auto runtime, explicit PDFBox legacy/oracle mode, and sidecar missing-runtime failure |
| 281. Rust PDF backend decision correction | complete | PRD and planning files now define Rust runtime + Kreuzberg-style `pdf_oxide` as the parser-core direction, with Java/PDFBox limited to wrapper/legacy/oracle and old `pdf-extract` removed from the runtime dependency path | docs/planning update, `cargo info pdf_oxide`, `git diff --check` |
| 282. Rust `pdf_oxide` backend RED/MVP | complete | `doctruth-runtime` depends on `pdf_oxide`, uses it for column-aware text-layer page extraction, text-span bbox-backed line units, page geometry, default rendered PNG page hashes, content-stream safety checks, and line-table extraction, emits `parserRun.pdfBackend`, and no longer depends on `lopdf` | `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test library_contract --test protocol_contract`, `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`, `sh scripts/smoke-doctruth-runtime.sh` |
| 283. Rust `pdf_oxide` render migration | complete | Page geometry and default page render hashes now come from `pdf_oxide`; `pdftoppm` is no longer a default runtime dependency and only remains possible through explicit configured renderer override | Rust protocol test, runtime smoke, dependency tree |
| 284. Rust table/debug backend completion | complete | Bordered/merged/continued table extraction now reads content streams through `pdf_oxide`, `parserRun.pdfBackend.current` reports `pdf_oxide`, `status` reports `DEFAULT`, and `lopdf` is removed from `doctruth-runtime` dependencies | Rust RED tests, dependency tree, runtime protocol contract |
| 285. OpenDataLoader XY-Cut++ Rust port RED/MVP | complete | Ported an attributed OpenDataLoader-style XY-Cut++ reading-order sorter into `runtime/doctruth-runtime`, covering cross-layout elements, adaptive horizontal/vertical cuts, narrow-outlier gap retry, two-column layouts, row-section preference, and sidebars while preserving `TrustDocument` as canonical output | Rust RED unit tests adapted from OpenDataLoader-style scenarios, protocol contract |
| 286. OpenDataLoader parser-safety filters RED/MVP | partial | Rust runtime filters whitespace-only, off-page, tiny, duplicate, near-white/background-like, and invisible render-mode text; severe parser-safety warnings block audit-grade output. Hidden OCG and rendered-page background comparison remain substrate/accuracy gaps and must not be claimed complete | Rust safety-filter tests, runtime protocol contract, benchmark corpus contract, current `pdf_oxide` API search |
| 287. OpenDataLoader tagged-structure preference RED/MVP | complete | Rust runtime now uses `pdf_oxide` canonical page reading order so trustworthy Tagged-PDF structure trees beat geometric ordering, emits `parserRun.readingOrder` and `parseTrace.readingOrder`, and falls back to XY-Cut with a structured warning when `/MarkInfo /Suspects true` marks the tree unreliable | Rust tagged-PDF fixture tests, parse trace assertions |
| 288. OpenDataLoader table heuristic migration RED/MVP | complete | Compatible bordered/line-table, merged-cell, row-span, borderless text-spatial, and adjacent-page continuation checks now run through the Rust `pdf_oxide` backend and normalize into `TrustDocument` table cells | Rust table fixtures, benchmark corpus table metrics |
| 289. Reference-composition guardrails | complete | Added PRD/test guardrails proving Kreuzberg, Docling, MinerU, and OpenDataLoader roles are layered references and do not create competing canonical outputs; `TrustDocument` remains the only truth contract and Java/PDFBox is not canonical | `ArchitectureContractTest`, PRD guardrail checks |
| 290. OpenDataLoader Bench adapter plan | complete | Treat OpenDataLoader Bench as the parser-quality foundation and DocTruth Bench as the evidence/replay layer; map DocTruth Rust runtime output to prediction/evaluation artifacts without replacing TrustDocument | PRD/planning docs updated; implementation RED tests remain Phase 291+ |
| 291. OpenDataLoader Bench adapter contract RED/MVP | complete | Add an adapter that exports DocTruth Rust runtime output into OpenDataLoader Bench-style prediction artifacts and imports `evaluation.json` into DocTruth benchmark reports under `external_metrics` | Java/Rust RED tests export `markdown/<document_id>.md` + `summary.json`, import synthetic `evaluation.json`, and do not execute GPL/AGPL engines |
| 292. OpenDataLoader Bench external metrics gate RED/MVP | complete | Add report/verifier fields for NID, TEDS, MHS, and speed, then block audit-grade promotion when parser-quality thresholds fail | Java/Rust benchmark report tests import synthetic `evaluation.json`, gate `opendataloader_*` thresholds, and reject tampered external metrics |
| 293. CLI shorthand Rust-default contract RED/MVP | complete | Make `--json` and `--markdown` aliases for Rust TrustDocument output; require `--backend pdfbox --format legacy-json|legacy-markdown` for old ParsedDocument output | CLI RED tests, focused Maven, full Java recorded verify, Rust runtime tests, diff check |
| 294. Rust-default smoke reconciliation | complete | Review package, benchmark, OCR seed, real-PDF, ONNX, TATR, and SLANeXT smokes now follow the Rust-sidecar default parser path; review packages align `trust-document.json` page hashes with exported PNG manifests | review/model/benchmark/real-PDF/seed/ONNX/TATR/SLANeXT smokes, targeted Java/Rust tests |
| 295. Raw Rust layered observation preservation RED/MVP | complete | Java sidecar parsing preserves Rust-emitted `contentBlocks` and `parseTrace` payloads through `TrustDocument`, and SDK/CLI writers prefer those raw runtime layers before falling back to deterministic TrustDocument projections | RED sidecar contract test, focused Java layered-output tests, Rust protocol contract |
| 296. Rust runtime capability/model doctor RED/MVP | complete | `doctruth-runtime --doctor` now reports local parser/model capabilities, native text/document-structure slots, layout/table/OCR model slots, model manifest path, cache directory, per-preset cache readiness, SHA mismatch/missing artifacts, worker configured/available/ready separation, and runtime memory without running inference | RED Rust library doctor tests, runtime protocol/model-worker tests |
| 297. Missing model graceful fallback coverage | complete | Rust `parse_pdf` tests now explicitly prove layout (`standard`), table (`table-server`), and OCR (`ocr`) presets fall back locally when required models are missing, mark output `NOT_AUDIT_GRADE`, and emit severe `model_unavailable_fallback` warnings with the missing model identity | Rust protocol contract coverage |
| 298. OpenDataLoader Bench vendored fixture import | complete | Vendored OpenDataLoader Bench under `third_party/opendataloader-bench/` with source metadata, license/notice preservation, PDFs, ground-truth Markdown, prediction/evaluation artifacts, evaluator code, charts, and AGENTS guidance that this is the first external parser-quality gate | repo import, `SOURCE.md`, AGENTS/PRD/NOTICE updates |
| 299. Recorded replay-validity report contract RED/MVP | complete | Java CLI and Rust runtime recorded benchmark reports now include `coverageRequired`, `coverageSatisfied`, `validityInputs`, and per-case `replay` evidence for sourceRef/quote/evidence-span replayability | Java/Rust RED report assertions and tamper-verifier tests |
| 300. Recorded replay-validity verifier parity | complete | Java `verify-benchmark-report` and Rust `verify_benchmark_report` recompute coverage satisfaction, verify replay validity inputs, and reject forged case-level replay evidence without rerunning the parser | `ParserBenchmarkCorpusCliTest`, Rust `benchmark_corpus_contract` |
| 301. Parser fixture taxonomy RED/MVP | complete | Java CLI and Rust runtime benchmark reports support `requiredFixtureTypes`, case `fixtureTypes`, fixture coverage counts, required fixture coverage, and satisfied fixture coverage for simple-column, two-column, sidebar, table, borderless-table, scanned-OCR, invoice, and mixed-layout fixtures | Java/Rust RED report assertions and fixture coverage tamper-verifier tests |
| 302. OpenDataLoader-inspired behavior taxonomy RED/MVP | complete | Java CLI and Rust runtime benchmark reports support `requiredBehaviors`, case `behaviors`, behavior coverage counts, required behavior coverage, and satisfied behavior coverage for XY-Cut edge, safety-filter, structure-tree preference, and table-cluster heuristic cases | Java/Rust RED report assertions and behavior coverage tamper-verifier tests |
| 303. OpenDataLoader evaluation import RED/MVP | complete | Parser benchmark manifests can declare `externalEvaluations.opendataloader`, import checked-in OpenDataLoader-style `evaluation.json`, flatten NID/TEDS/MHS/speed into `opendataloader_*` metrics, and persist source hashes under `externalMetrics` | Java/Rust RED report assertions, threshold gates, and tamper-verifier tests |
| 304. Rust duplicate text safety filter RED/MVP | complete | Rust runtime filters near-overlaid duplicate positioned text before reading-order grouping, emits a severe `duplicate_text_filtered` warning, and marks output `NOT_AUDIT_GRADE`; this is a partial safety-filter slice and does not complete Phase 286 | Rust protocol contract, benchmark corpus contract |
| 305. Rust geometric and near-white parser-safety filters RED/MVP | complete | Rust runtime filters whitespace-only, off-page, tiny, near-white/background-like, duplicate, and invisible render-mode text-layer spans with severe warnings and audit-grade blocking; robust rendered-page background comparison remains a later accuracy expansion | Rust protocol contract, benchmark corpus contract |
| 306. Rust text-spatial table detector slice | complete | Borderless/text-spatial table extraction uses `pdf_oxide` `detect_tables_from_spans`; bordered-grid, merged-cell, row-span, and adjacent-page continuation extraction now use `pdf_oxide` content-stream primitives rather than `lopdf` | Rust protocol contract |
| 307. Rust recorded actual TrustDocument replay binding RED/MVP | complete | Rust recorded benchmark reports embed each case's actual `TrustDocument` plus `actualTrustDocumentSha256`, and `verify_benchmark_report` recomputes the hash so parser-quality/replay claims are bound to real parser output instead of metrics-only evidence | Rust RED report assertion and tamper-verifier test |
| 308. Rust recorded metric replay from actual TrustDocument RED/MVP | complete | Rust `verify_benchmark_report` resolves each case's manifest label by `labelId`, reloads expected Markdown and expected `TrustDocument`, recomputes parser-quality metrics from embedded `actualTrustDocument`, and rejects metric claims that no longer match the recorded parser output | Rust RED tamper test that changes embedded parser output and updates its hash |
| 309. Rust fixture/layout pass-fail report RED/MVP | complete | Rust recorded benchmark reports include `fixtureResults` with each fixture/layout bucket's case count, cases, aggregate metrics, and pass/fail status; verifier recomputes it so layout pass/fail evidence cannot be forged independently | Rust RED report assertion and tamper-verifier test |
| 310. OpenDataLoader Bench real runner baseline | complete | Added a DocTruth runner for the vendored OpenDataLoader Bench corpus that writes prediction markdown, summary, errors, and evaluator outputs for real PDFs; first full local run covered 200 PDFs, parsed 199, failed one scanned/no-text-layer PDF, and produced the honest baseline `overall=0.509`, `nid=0.759`, `teds=0.0`, `mhs=0.003` | RED smoke for one real PDF, `sh scripts/smoke-doctruth-opendataloader-bench-runner.sh`, `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime` |
| 311. OpenDataLoader export-layer score lift | complete | Improved OpenDataLoader prediction Markdown export with heading promotion, TrustDocument table HTML rendering, and a conservative line-span table fallback; full 200-PDF run improved `overall=0.549`, `nid=0.767`, `teds=0.065`, and `mhs=0.122` while keeping 199/200 parsed | RED export-format smoke, `python3 scripts/smoke-doctruth-opendataloader-export-format.py`, `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-optimized` |
| 312. OpenDataLoader runner timeout | complete | Added per-document `--timeout-seconds` handling so pathological PDFs are recorded as failed instead of blocking iteration; 30s timeout cut optimized full-run time from `390.96s` to `239.54s` with effectively unchanged aggregate score | RED timeout smoke, `python3 scripts/smoke-doctruth-opendataloader-timeout.py`, `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-optimized-timeout --timeout-seconds 30` |
| 313. Parser-quality replication research | complete | Wrote the OpenDataLoader/Docling quality replication plan, separating current low-score baseline from the complete reference-pipeline work needed for real parity | `docs/plans/2026-06-17-parser-quality-replication-plan.md`, `findings.md`, `progress.md` |
| 314. Reference oracle diff harness | complete | Generate per-document DocTruth vs OpenDataLoader vs Docling vs ground-truth comparison records with top-loss metric and failure bucket | `scripts/compare-doctruth-parser-references.py`, smoke, full OpenDataLoader Bench pass2 comparison |
| 315. Metric-specific parser triage | complete | Bucket low-score cases by NID/TEDS/MHS/speed/replay failure type so implementation slices target measurable losses instead of screenshots | `scripts/triage-doctruth-parser-reference-report.py`, smoke, pass2 triage report |
| 316. Table-cluster Rust parity slice | partial | Export-layer table fixes preserve TrustDocument row/column ranges and guarded bbox fallback; Rust text-spatial/borderless tables now normalize `method="cluster"`, preserve empty sparse cells, and add a positioned-line cluster fallback that fixes real OpenDataLoader case `01030000000128` from `table_count=0` to a 6-column cluster table. Full cluster-table structure parity remains pending until pass3/full bench rerun | table range/spatial smoke, Rust protocol cluster method test, real OpenDataLoader sparse table regression, full pass2 TEDS `0.18840125729021784` vs old `0.06498004117639267` |
| 317. Heading/list/section Rust parity slice | partial | Rust content-block semantics now classifies heading/table/list/text, prevents numbered list items from being promoted as headings, emits section ids/parent ids/paths/title paths/root flags, and exposes `parseTrace.sectionTree` from parser observations; real MHS parity on OpenDataLoader Bench remains pending | Rust protocol heading/list/section tests, heading smoke, pass2 MHS `0.19566644996808139` vs old `0.12239636974611434` |
| 318. Reading-order/text-normalization parity slice | partial | Page-number noise filtering and false table suppression protect text cases, but NID remains below the old timeout run and far below references | column/page-number smoke, pass2 NID `0.7391382135188431` vs old `0.7663393307030263` |
| 319. Rust content block semantics RED/MVP | complete | Rust-owned `contentBlocks`, `parseTrace.readingBlocks`, and `parseTrace.sectionTree` now carry heading/table/list/text types, heading levels, normalized text, bbox, source unit ids, evidence span ids, and section hierarchy metadata so exporter does not invent all section semantics | `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract` |
| 320. Rust text-span observation layer RED/MVP | complete | `parseTrace.pages[].textSpans[]` now exposes flat page text spans with bbox, content, source object id, evidence span id, reading order, and unit back-links through `parseTraceSpanIds` for XY-Cut++ and table-cluster algorithms | Rust RED/green protocol contract |
| 321. Rust table confidence/export parity RED/MVP | partial | Rust tables carry method, quality row/column/fill counts, row/column ranges, and OpenDataLoader-style `cluster` method for text-spatial tables; full TEDS parity still needs stronger structure recognition | Rust table contracts, table smoke, OpenDataLoader subset/full bench pending |
| 322. Parser-quality pass3 verification | pending | Run final smoke suite, Rust tests, diff check, and full OpenDataLoader Bench pass3/pass5 after completing the next algorithm batch, not after exporter-only changes | unified verification only after implementation batch |
| 323. OpenDataLoader hybrid rustification plan | complete | New plan accepts OpenDataLoader hybrid as the proven benchmark oracle/reference, then moves deterministic parser work into Rust and Python/Torch-heavy model work into an MNN-first lazy model runtime instead of requiring an all-Rust parser first | `docs/plans/2026-06-18-opendataloader-rustification-tdd-plan.md` |
| 324. OpenDataLoader hybrid benchmark oracle TDD | complete | Added explicit `benchmark-oracle --engine opendataloader-hybrid` CLI adapter, fake/live oracle JSON runner contract, TrustDocument normalization, parserRun external provenance/elapsed time, Markdown-only non-audit-grade warning, production parse no-fallback guard, vendored-PDF fake smoke, and real one-document OpenDataLoader hybrid live smoke through the DocTruth CLI adapter | `BenchmarkOracleCommandTest`, `scripts/smoke-doctruth-benchmark-oracle.sh`, `scripts/doctruth_opendataloader_hybrid_oracle.py`, `target/benchmark-oracle-live/01030000000119.trust.json` |
| 325. OpenDataLoader structured adapter TDD | complete | `benchmark-oracle --engine opendataloader-hybrid` now prefers oracle `blocks` over Markdown, maps heading/list/table blocks into `TrustDocument` units/tables, emits INFO structured source-mapping provenance, keeps Markdown-only fallback as severe non-audit-grade, and can render structured `content_blocks`/`parse_trace` output | RED/green `BenchmarkOracleCommandTest`, focused API/architecture suite |
| 326. Rust deterministic parity from OpenDataLoader behavior | partial | Added real OpenDataLoader Bench regressions for `01030000000195`, `01030000000001`, `01030000000021`, `01030000000036`, `01030000000038`, `01030000000029`, `01030000000046`, and `01030000000047`: Rust heading classification no longer promotes bullet symbols, soft hyphens, bullet-line word fragments, lowercase-connector fragments, sentence-punctuation fragments, or prose citation tails as headings; canonical `contentBlocks` now merge same-line section marker headings such as `B.1 Large Language Models`, numeric marker headings such as `7 Variants of sj Observer Models`, dotted numeric headings such as `5. The dynamics`, numbered section lines such as `2. General Profile of MSMEs` / `6.2. Expectations for Re-Hiring Employees`, and centered chapter-number/title pairs such as `# 2` / `# The Lost Homeland`; section metadata is derived from merged semantic blocks; the benchmark exporter trusts Rust block types and renders each merged block once. Rust now rejects full-page single-cell `line-table` detections so page prose cannot leak as a table cell. Added benchmark-renderer special case for TOC tables: `01030000000044` now renders TOC as Markdown heading/plain lines. Moved ANFREL political-party registration table recovery into Rust core: `01030000000046` and `01030000000047` now emit canonical 7-column `TrustTable`s with grouped headers, rowspans/colspans, preserved empty cells, normalized header bboxes, continuation rows, totals, and page-number filtering before Markdown export. Spot metrics: `01030000000195` improved from pass7-style `overall≈0.538/MHS≈0.083` to `overall=0.998/MHS=0.999`; `01030000000001` improved from subset `overall=0.495/MHS=0.000` to `overall=0.984/MHS=0.977`; `01030000000021` improved from subset `overall=0.498/NID=0.996/MHS=0.000` to `overall=0.998/NID=0.997/MHS=0.999`; `01030000000036` improved to `overall=0.682/MHS=0.771`; `01030000000038` improved to `overall=0.776/MHS=0.794`; `01030000000044` improved from `overall=0.332/MHS=0.000` to `overall=1.000/MHS=1.000`; `01030000000029` improved from `overall=0.432/NID=0.679/MHS=0.185` to `overall=0.632/NID=0.966/MHS=0.297`; `01030000000046` improved from Rust-core `overall=0.751/NID=0.764/TEDS=0.738` to `overall=0.944/NID=0.889/TEDS=0.999`; `01030000000047` improved from `overall=0.443/NID=0.557/TEDS=0.329` to `overall=0.977/NID=0.955/TEDS=1.000`. Fixed subset evaluation so `--limit` runs only score generated document IDs; current 50-doc subset reports `overall=0.8035/NID=0.8809/MHS=0.5121/TEDS=0.9183` with `missing_predictions=0`. Remaining Phase 3 work includes broader reading-order/text normalization, heading hierarchy, non-ANFREL table families, OCR/no-text cases, broader subset/full OpenDataLoader Bench rerun, and then MNN runtime phases | Rust RED/green real fixtures, TOC renderer smoke, party-table Rust contracts/smoke, centered chapter Rust contract, `doctruth-runtime-heading-fragment-195`, `doctruth-runtime-heading-merge-195`, `doctruth-runtime-numeric-heading-001`, `doctruth-runtime-centered-chapter-021`, `doctruth-runtime-centered-chapter-50`, `doctruth-runtime-party-core-50b`, and `git diff --check` |
| 327. MNN-first model runtime resource profile | pending | Replace always-on Docling/Torch residency for local edge mode with Rust-orchestrated MNN model manifests, ONNX-to-MNN conversion as build tooling only, page-level routing, lazy load/unload, no automatic runtime fallback chain, and resource gates | Phase 4/5 RED tests and resource benchmark |
| 328. MNN runtime final benchmark acceptance gate | pending | Run the MNN production runtime through OpenDataLoader Bench because model conversion/quantization can degrade quality; accept only near-hybrid NID/TEDS/MHS/overall with materially lower RSS/cold-start/latency and no Python/Torch/Docling production process | Final MNN acceptance gate in `docs/plans/2026-06-18-opendataloader-rustification-tdd-plan.md` |
| 329. Rust TOC split-page-number table slice | complete | Real OpenDataLoader case `01030000000016` now emits a canonical Rust `TrustTable` for `Table of Contents` pages where titles are in the left column and page numbers are split into a right bbox column; rows without an explicit right page number can reuse the previous TOC page reference when the PDF text layer omits duplicate page numbers. This moves the fix into `body.tables`/`TABLE_CELL` units instead of relying on Markdown-only export repair | RED/GREEN `parse_pdf_emits_table_of_contents_rows_for_split_page_numbers`; spot `doctruth-runtime-toc-core-016` improved to `overall=0.989/NID=0.998/MHS=0.980`; 50-doc subset `doctruth-runtime-toc-core-50` reports `overall=0.8128/NID=0.8826/TEDS=0.9183/MHS=0.5507`, `parsed_count=50`, `failed_count=0`, `missing_predictions=0`; `cargo fmt --check`, `cargo test --test protocol_contract`, `git diff --check` |
| 330. Rust split-title heading and body-fragment demotion slice | complete | Real OpenDataLoader case `01030000000033` now merges upper-page same-line title fragments such as `Functional` + `Abstraction` into one heading block while demoting title-case body fragments such as `Nothing would` when they sit on the right side of an ongoing sentence line. This reduces false section roots and improves heading hierarchy without changing the canonical Rust observation layer | RED/GREEN `parse_pdf_merges_split_title_line_and_rejects_body_fragments_as_headings`; spot `doctruth-runtime-title-fragment-033` improved from `overall=0.537/NID=0.929/MHS=0.145` to `overall=0.610/NID=0.930/MHS=0.290`; 50-doc subset `doctruth-runtime-title-fragment-50` reports `overall=0.8170/NID=0.8829/TEDS=0.9183/MHS=0.5687`, `parsed_count=50`, `failed_count=0`, `missing_predictions=0`; `cargo test --test protocol_contract`, `git diff --check` |
| 331. Rust inline math heading demotion slice | complete | Real OpenDataLoader case `01030000000031` and related math-heavy pages no longer promote inline formula fragments such as `P`, `P þP`, `W and`, `A , we can compute the`, `S ¼`, or sentence continuations as headings, while preserving true section-marker headings such as `B Related Works and Background` and numbered headings. This materially improves MHS without adding a formula parser yet | RED/GREEN `parse_pdf_does_not_promote_inline_math_fragments_to_headings`; regression guard `parse_pdf_merges_opendataloader_split_heading_lines`; spot `doctruth-runtime-inline-math-031` improved to `overall=0.837/NID=0.932/MHS=0.743`; 50-doc subset `doctruth-runtime-inline-math-50` reports `overall=0.8435/NID=0.8832/TEDS=0.9183/MHS=0.6878`, `parsed_count=50`, `failed_count=0`, `missing_predictions=0`; `cargo test --test protocol_contract`, `cargo fmt --check`, `git diff --check` |
| 332. Rust multiline heading merge and same-column guard slice | complete | Real OpenDataLoader cases `01030000000019` and `01030000000039` now merge wrapped multiline headings such as `Author’s Note to the 2021 Edition` and `9.5. Adapting to the New Normal: Changing Business Models`, reject parenthetical/body fragments as headings, and avoid regressing synthetic section hierarchy by blocking vertical heading merge across same-column body text or from single-token/chapter-number starts | RED/GREEN `parse_pdf_merges_multiline_headings_and_rejects_parenthetical_body_fragments`; regression guards `parse_pdf_emits_section_hierarchy_for_heading_blocks` and `parse_pdf_promotes_centered_chapter_number_and_title_headings`; spot `01030000000019` reports `overall=0.994/NID=0.998/MHS=0.990`; spot `01030000000039` reports `overall=0.726/NID=0.688/MHS=0.765`; 50-doc subset `doctruth-runtime-multiline-heading-50` reports `overall=0.8534/NID=0.8833/TEDS=0.9183/MHS=0.7331`, `parsed_count=50`, `failed_count=0`, `missing_predictions=0`; `cargo fmt --check`, `cargo test --test protocol_contract`, `git diff --check` |
| 333. Rust footnote and hyphen-continuation heading demotion slice | complete | Real OpenDataLoader case `01030000000013` and related footnote-heavy pages no longer promote two-digit footnote markers, lowercase hyphenated continuation lines, or same-line citation-tail journal fragments as section headings while preserving true chapter heading `4 Al-Sadu Symbols and Social Significance` and multiline/year headings such as `Author’s Note to the 2021 Edition` | RED/GREEN `parse_pdf_does_not_promote_footnote_and_hyphen_continuations_to_headings`; regression guard `parse_pdf_merges_multiline_headings_and_rejects_parenthetical_body_fragments`; spot `01030000000013` improved from `overall=0.495/NID=0.766/MHS=0.224` to `overall=0.639/NID=0.767/MHS=0.510`; 50-doc subset `doctruth-runtime-footnote-heading-50b` reports `overall=0.8632/NID=0.8834/TEDS=0.9183/MHS=0.7771`, `parsed_count=50`, `failed_count=0`, `missing_predictions=0`; `cargo test --test protocol_contract` -> `47 passed`; `cargo fmt --check`, `git diff --check` |
| 334. Rust figure-caption spatial-table suppression slice | complete | Real OpenDataLoader case `01030000000027` no longer emits a page of figure captions as a `pdf_oxide text-spatial` TrustTable; line spans are preserved instead so benchmark Markdown no longer degrades into an HTML table for chart/caption pages, while normal borderless spatial table detection still passes | RED/GREEN `parse_pdf_does_not_emit_figure_caption_page_as_spatial_table`; regression guard `parse_pdf_uses_pdf_oxide_text_spatial_table_detection_for_borderless_table`; spot `01030000000027` improved from `overall=0.535/NID=0.535` to `overall=0.624/NID=0.624`; 50-doc subset `doctruth-runtime-figure-caption-table-50` reports `overall=0.8650/NID=0.8852/TEDS=0.9183/MHS=0.7771`, `parsed_count=50`, `failed_count=0`, `missing_predictions=0`; `cargo test --test protocol_contract` -> `48 passed`; `cargo fmt --check`, `git diff --check` |
| 335. Rust full-page line-table suppression slice | complete | Real OpenDataLoader case `01030000000041` no longer appends page prose, corrupt glyphs, chart caption text, and footer labels as one full-page spanned `pdf_oxide line-table` `TABLE_CELL`; normal line spans remain the canonical text evidence | RED/GREEN `parse_pdf_does_not_emit_full_page_spanned_line_table_cell`; regression guard `parse_pdf_does_not_emit_full_page_single_cell_line_table`; spot `01030000000041` improved from subset `overall=0.587/NID=0.587` to `overall=0.803/NID=0.803`; 50-doc subset `doctruth-runtime-fullpage-line-table-50` reports `overall_mean=0.8762/NID=0.8964/TEDS=0.9183/MHS=0.7771`, `parsed_count=50`, `failed_count=0`; `cargo test --test protocol_contract` -> `49 passed`; `cargo fmt --check`, `git diff --check` |
| 336. Rust survey-chart two-column region ordering slice | complete | Survey/report chart pages with Figure captions and date/survey phase labels now repair row-interleaved two-column body regions into left-column then right-column reading order without applying the rule to ordinary Figure/photo pages | RED/GREEN `parse_pdf_orders_opendataloader_two_column_body_by_column_regions`; regression guard `parse_pdf_orders_two_column_positioned_text_by_visual_columns`; spot `01030000000037` improved from `overall=0.588/NID=0.648` to `overall=0.788/NID=0.960`; 50-doc subset `doctruth-runtime-survey-chart-50` reports `overall_mean=0.8889/NID=0.9126/TEDS=0.9183/MHS=0.7977`, no overall regressions >0.02, `parsed_count=50`, `failed_count=0`; `cargo test --test protocol_contract` -> `50 passed`; `cargo fmt --check`, `git diff --check` |
| 337. Rust vertical numbered heading merge slice | complete | Real OpenDataLoader case `01030000000003` now merges vertically split section heading fragments `11`, `Dual-Presentation`, `sj`, and `Data` into one semantic heading `11 Dual-Presentation SJ Data`, while demoting citation-like fragments such as `Arnold, 2011` and preserving existing numeric-heading regressions | RED/GREEN `parse_pdf_merges_vertical_numbered_heading_fragments`; regression guards for dotted numeric headings, inline math demotion, and footnote/hyphen heading demotion; spot `01030000000003` improved from `overall=0.593/MHS=0.471` to `overall=0.689/MHS=0.662`; 50-doc subset `doctruth-runtime-vertical-numbered-50` reports `overall_mean=0.8908/NID=0.9127/TEDS=0.9183/MHS=0.8064`, no overall regressions >0.02, `parsed_count=50`; `cargo test --test protocol_contract` -> `51 passed`; `cargo fmt --check`, `git diff --check` |
| 338. Formula spatial-table suppression and same-line numeric heading slice | complete | Real OpenDataLoader case `01030000000028` no longer has the benchmark adapter synthesize a fake HTML table from formula/prose line spans, and Rust core merges same-line section marker heading `4.` + `Entropy` into `4. Entropy` while preserving page-header number demotion for `01030000000048` | RED/GREEN `parse_pdf_merges_same_line_number_marker_heading`; regression guard `parse_pdf_does_not_promote_page_header_number_as_heading`; adapter formula-spatial smoke and `py_compile`; spot `01030000000028` improved from `overall=0.607/NID=0.838/MHS=0.376` to `overall=0.879/NID=0.977/MHS=0.780`; 50-doc subset `doctruth-runtime-formula-heading2-50` reports `overall_mean=0.8963/NID=0.9154/TEDS=0.9183/MHS=0.8248`, no overall regressions >0.02; `cargo test --test protocol_contract` -> `53 passed`; `cargo fmt --check`, `git diff --check` |
| 339. Figure caption semantic-block merge slice | complete | Real OpenDataLoader case `01030000000027` now merges fragmented caption units such as `Figure` + `7.` + caption lines into one content block per figure caption, improving `contentBlocks`/LLM consumption while keeping raw `LINE_SPAN` evidence unchanged; benchmark metrics remain unchanged because the remaining gap is missing chart/axis text that requires OCR/image-layer recovery | RED/GREEN `parse_pdf_merges_figure_caption_fragments`; regression guard `parse_pdf_does_not_emit_figure_caption_page_as_spatial_table`; spot `01030000000027` remains `overall=0.624/NID=0.624`; 50-doc subset `doctruth-runtime-figure-caption-merge-50` matches Phase 338 means with no overall regressions or improvements >0.02; `cargo test --test protocol_contract` -> `54 passed`; `cargo fmt --check`, `git diff --check` |
| 340. Runtime profile gate RED/MVP | complete | Rust runtime now exposes profile contracts in `--doctor`, records `parserRun.profile`, keeps backward-compatible default protocol behavior as `edge-model`, refuses `benchmark-oracle` as a production `parse_pdf` profile, and prevents explicitly requested `edge-fast` parses from starting a configured model worker | RED/GREEN `doctor_reports_runtime_profiles_and_resource_gate_contract`, `parse_pdf_rejects_benchmark_oracle_as_production_runtime_profile`, `parse_pdf_edge_fast_profile_does_not_start_configured_worker`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `5 passed`; `cargo fmt --check`; `git diff --check` |
| 341. Benchmark resource/profile report RED/MVP | complete | Rust `benchmark_corpus` reports `resourceProfile` with runtime profile, process RSS/peak memory sampling, elapsed time, mean case elapsed time, and no Python/Torch/Docling production residency marker; each case records `runtimeProfile`, `elapsedMs`, and process RSS memory sampling so future MNN cold-start/warm-run gates have a stable report home | RED/GREEN `benchmark_corpus_runs_labeled_manifest_and_reports_metrics` and `benchmark_corpus_writes_recorded_report_artifact`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `26 passed`; `cargo fmt --check`; `git diff --check` |
| 342. MNN-only edge-model manifest gate RED/MVP | complete | `edge-model` no longer starts a configured model worker just because one exists; model-assisted parse/benchmark paths require manifest/cache artifacts that are `READY` and explicitly `backend=mnn` + `format=mnn`. ONNX/onnxruntime manifests are marked unsupported and fall back to deterministic Rust output with severe non-audit-grade warnings instead of silently running as production | RED/GREEN `parse_pdf_edge_model_rejects_onnx_manifest_and_does_not_start_worker`; upgraded worker and benchmark worker tests to provide READY MNN manifests and assert MNN metadata reaches the worker; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `6 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `26 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo fmt --check`; `git diff --check` |
| 343. Lazy MNN worker protocol/resource aggregation RED/MVP | complete | Model-assisted `edge-model` worker requests now declare `modelRuntime.runtime=mnn`, `loadPolicy=lazy`, and `unloadPolicy=idle-after-request`; worker envelope metrics are normalized into `parserRun.modelRuntime`, and benchmark `resourceProfile.modelRuntime` aggregates cold-start time, inference time, peak memory, and loaded model ids when measurable | RED/GREEN model-worker assertions for request policy and returned metrics; benchmark worker case asserts report-level `resourceProfile.modelRuntime`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `6 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `26 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo fmt --check`; `git diff --check` |
| 344. Auto preset simple-page deterministic routing RED/MVP | complete | `preset=auto` now has an explicit routing contract: simple text-layer PDFs under `edge-model` remain Rust deterministic even when a READY MNN worker/manifest is configured. `parserRun.modelRouting` records mode, decision, whether a model runtime started, routed pages, and model identities for deterministic and worker-backed paths | RED/GREEN `parse_pdf_auto_preset_simple_text_does_not_start_mnn_worker`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `7 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `26 passed`; `cargo fmt --check`; `git diff --check` |
| 345. Auto preset table-heavy MNN routing RED/MVP | complete | `preset=auto` now detects table-heavy text-layer pages and routes them to the `table-lite` MNN table model when a READY `slanet-plus:v1` manifest/cache is available. Worker requests include auto routing metadata, and the normalized TrustDocument records `parserRun.modelRouting` with `route=table-model`, `startedModelRuntime=true`, routed page 1, and model identity | RED/GREEN `parse_pdf_auto_preset_table_heavy_routes_to_table_mnn_worker`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `8 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `26 passed`; `cargo fmt --check`; `git diff --check` |
| 346. Auto preset scanned/OCR MNN routing RED/MVP | complete | `preset=auto` now detects PDFs whose pages have no extractable text-layer lines and routes them to the `ocr` MNN model path when a READY `ocr-router:v1` manifest/cache is available. The worker request and normalized TrustDocument record `parserRun.modelRouting` with `route=ocr-model`, `startedModelRuntime=true`, routed page 1, and OCR model identity. Without a READY MNN OCR artifact, this remains fail-closed rather than falling back to Torch/Docling/Tesseract/PDFBox | RED failure: `PDF_EXTRACTION_FAILED` before OCR routing; GREEN `parse_pdf_auto_preset_scanned_pdf_routes_to_ocr_mnn_worker`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `9 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `26 passed` |
| 347. Packaged RapidOCR/MNN worker discovery RED/MVP | complete | OCR auto routing can now discover a packaged `doctruth-rapidocr-mnn-worker` on `PATH` when no explicit `DOCTRUTH_RUNTIME_MODEL_COMMAND`/`DOCTRUTH_MODEL_COMMAND` is configured. Discovery is route-scoped to `ocr-model`; table/layout model routes still require explicit worker configuration and READY MNN artifacts, preventing hidden fallback chains | RED failure: OCR auto route still returned `PDF_EXTRACTION_FAILED` with only PATH worker present; GREEN `parse_pdf_auto_ocr_route_discovers_packaged_rapidocr_mnn_worker`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `10 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `26 passed` |
| 348. MNN promotion gate report RED/MVP | complete | Rust `benchmark_corpus` now emits `mnnPromotion` when a manifest declares `promotionGates.mnn`, combining OpenDataLoader quality metrics with resource-profile evidence. The gate accepts only when NID/TEDS/MHS/derived overall meet thresholds, model runtime metrics exist, Python/Torch/Docling residency is false, lazy startup is true, and model peak RSS is below the declared heavy-oracle RSS. Low-quality MNN runs remain benchmark `passed` for parser-corpus validity but `mnnPromotion.accepted=false` | RED missing `mnnPromotion`; GREEN `benchmark_corpus_reports_mnn_promotion_gate_for_model_profile`; negative GREEN `benchmark_corpus_rejects_mnn_promotion_when_quality_gate_fails`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> `28 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> `56 passed`; `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract` -> `10 passed` |
| 349. MNN promotion OpenDataLoader bench lane smoke | complete | `run-doctruth-mnn-promotion-bench.sh` provides a fail-closed OpenDataLoader Bench lane that requires MNN manifest/cache, sends `runtime_profile=edge-model`, records manifest/cache/model runtime evidence in summary JSON, and proves routed MNN execution with a Rust smoke worker binary rather than a Python fake worker | RED smoke failed on missing runner; GREEN `sh scripts/smoke-doctruth-mnn-promotion-bench.sh`; `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py`; `cargo fmt --check`; `cargo test --test protocol_contract`; `cargo test --test model_worker_contract`; `cargo test --test benchmark_corpus_contract`; `git diff --check` |
| 350. Rust-owned OpenDataLoader prediction artifacts RED/MVP | complete | Rust `benchmark_corpus` prediction export now writes OpenDataLoader-style markdown, `summary.json`, and `errors.json` with TrustDocument/runtime profile, parsed/failed counts, no Python/Torch/Docling production residency, and per-document model routing/runtime evidence. Added a smoke that uses Rust `benchmark_corpus` plus a Rust MNN worker binary to produce prediction artifacts without the Python prediction adapter | RED `benchmark_corpus_exports_opendataloader_prediction_artifacts` failed on missing `runtime_contract`; GREEN focused and full `benchmark_corpus_contract`; `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`; `sh scripts/smoke-doctruth-mnn-promotion-bench.sh`; `cargo fmt --check`; `git diff --check` |
| 351. Direct Rust OpenDataLoader prediction command RED/MVP | complete | `doctruth-runtime` now supports `opendataloader_prediction`, scanning `bench_dir/pdfs`, honoring `doc_id`/`limit`, parsing with the requested preset/profile, and writing prediction markdown/summary/errors directly without generating a corpus manifest or calling the Python adapter | RED failed on `UNKNOWN_COMMAND`; GREEN `opendataloader_prediction_command_writes_artifacts_from_bench_pdf_dir`; `scripts/smoke-doctruth-rust-opendataloader-prediction.sh` now calls the direct Rust command; `benchmark_corpus_contract` -> 29 passed; smoke + fmt + diff checks pass |
| 352. Direct prediction evaluator import and promotion report RED/MVP | complete | `opendataloader_prediction` can now import an OpenDataLoader evaluator JSON, expose external metrics, synthesize `resourceProfile`, and evaluate `promotionGates.mnn` through the same MNN promotion decision path used by `benchmark_corpus` | RED `opendataloader_prediction_command_imports_evaluator_metrics_for_promotion_report` failed on missing external metrics; GREEN `benchmark_corpus_contract` -> 30 passed; direct smoke asserts no promotion is evaluated without evaluator/gate; model/protocol regressions and fmt/diff checks pass |
| 353. Rust promotion report from existing prediction/evaluator artifacts RED/MVP | complete | `doctruth-runtime` now supports `opendataloader_promotion_report`, reading an existing Rust prediction `summary.json` plus an OpenDataLoader evaluator JSON and producing a promotion report without reparsing PDFs or calling the Python prediction adapter. This keeps Python limited to upstream evaluator/oracle scoring for this slice | RED failed on `UNKNOWN_COMMAND`; GREEN `opendataloader_promotion_report_uses_existing_prediction_summary_without_reparse`; smoke proves Rust prediction -> evaluator JSON import -> Rust promotion report with MNN runtime/resource gate; fixed peak-memory aggregation for float JSON metrics; `benchmark_corpus_contract` -> 31 passed; `model_worker_contract` -> 10 passed; `protocol_contract` -> 56 passed; smoke + fmt checks pass |
| 354. Rust OpenDataLoader evaluator MVP | complete | `doctruth-runtime` now supports `opendataloader_evaluate_prediction`, reading ground-truth Markdown plus prediction Markdown and writing OpenDataLoader-style `evaluation.json` with NID/TEDS/MHS fields, missing-prediction counts, summary passthrough, optional `doc_id`, and no Python process. This is an MVP evaluator path for simple parity and promotion plumbing; full rapidfuzz/APTED/lxml parity remains future work | RED failed on `UNKNOWN_COMMAND`; GREEN `opendataloader_evaluate_prediction_writes_rust_evaluation_without_python`; direct smoke now proves Rust prediction -> Rust evaluator -> Rust promotion report; `benchmark_corpus_contract` -> 32 passed; smoke + fmt + diff checks pass |
| 355. Rust evaluator upstream normalization parity slice | complete | Rust evaluator now matches two important upstream normalization behaviors: MHS treats all Markdown heading levels as equivalent, and table scoring normalizes `th` to `td` while dropping `thead`/`tbody`. String similarity now uses LCS/Indel-style ratio, closer to rapidfuzz `fuzz.ratio`, instead of Levenshtein/max length | RED `opendataloader_evaluator_matches_upstream_heading_and_table_normalization`; GREEN focused test, `benchmark_corpus_contract` -> 33 passed; `model_worker_contract` -> 10 passed; `protocol_contract` -> 56 passed; smoke + fmt + diff checks pass |
| 356. Rust evaluator MHS tree/content parity slice | complete | Rust MHS now builds a document/heading/content tree and scores ordered tree edits so text changes under an otherwise identical heading structure reduce MHS while preserving MHS-S. This moves the Rust evaluator closer to upstream APTED semantics without yet claiming full APTED parity | RED `opendataloader_evaluator_mhs_scores_content_separately_from_structure`; GREEN focused MHS/content and normalization tests; `benchmark_corpus_contract` -> 34 passed; `model_worker_contract` -> 10 passed; `protocol_contract` -> 56 passed; smoke + fmt + diff checks pass |
| 357. Rust evaluator TEDS tree/content parity slice | complete | Rust TEDS now parses simple HTML table trees (`body/table/tr/td`) with `rowspan`/`colspan`, scores ordered tree edits, separates content-sensitive TEDS from structure-only TEDS-S, and keeps `th`/`thead`/`tbody` normalization. This closes the previous string-similarity gap for same-structure content changes | RED `opendataloader_evaluator_teds_scores_content_separately_from_structure`; GREEN focused TEDS and normalization tests; `benchmark_corpus_contract` -> 35 passed; `model_worker_contract` -> 10 passed; `protocol_contract` -> 56 passed; smoke + fmt + diff checks pass |
| 358. Rust evaluator Markdown table conversion slice | complete | Rust TEDS now converts simple Markdown pipe tables into HTML table trees before scoring, matching the upstream evaluator's Markdown-table-to-HTML preprocessing for common pipe-table cases | RED `opendataloader_evaluator_converts_markdown_pipe_tables_for_teds`; GREEN focused Markdown-table/TEDS tests; `benchmark_corpus_contract` -> 36 passed; `model_worker_contract` -> 10 passed; `protocol_contract` -> 56 passed; smoke + fmt + diff checks pass |
| 359. Default OpenDataLoader runner Rustification | complete | `scripts/run-doctruth-opendataloader-bench.sh` and `scripts/run-doctruth-mnn-promotion-bench.sh` no longer call the Python prediction adapter. The default path now uses `doctruth-runtime opendataloader_prediction`, writes `prediction-report.json`, and runs the Rust evaluator unless `--evaluator official` is explicitly requested as oracle-only. At this slice boundary, `--timeout-seconds` was intentionally not accepted until Phase 360 implemented it in Rust | RED grep found both default runners invoking `doctruth_opendataloader_prediction.py`; GREEN `sh scripts/smoke-doctruth-opendataloader-bench-runner.sh`, `sh scripts/smoke-doctruth-mnn-promotion-bench.sh`, `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract` -> 36 passed; `cargo fmt --check`; runner grep no Python adapter |
| 360. Rust OpenDataLoader per-document timeout RED/MVP | complete | `opendataloader_prediction` accepts `timeout_seconds`/`timeoutSeconds` and uses a child `doctruth-runtime parse_pdf` process per document only when timeout is requested. Timed-out documents are killed, written as empty markdown, and reported with `errorCode=PARSE_TIMEOUT` in `summary.json` and `errors.json`; the default no-timeout path remains in-process for speed | RED slow MNN worker test failed because timeout was ignored; GREEN `opendataloader_prediction_command_records_per_document_timeout`; `benchmark_corpus_contract` -> 37 passed; runner `--timeout-seconds` flag wired; fast runner smoke still omits timeout to keep smoke quick |
| 361. Rust/OpenDataLoader evaluator parity smoke | complete | Added `scripts/smoke-doctruth-opendataloader-evaluator-parity.sh`, which builds a temporary mini OpenDataLoader Bench fixture set, runs the official upstream evaluator and Rust `opendataloader_evaluate_prediction`, and compares aggregate plus per-document NID/TEDS/MHS/MHS-S/TEDS-S metrics within tolerance. The smoke is skip-safe when upstream Python evaluator dependencies are unavailable, and uses the vendored bench `.venv` when present | `sh scripts/smoke-doctruth-opendataloader-evaluator-parity.sh`; official evaluator and Rust evaluator agree on exact text, heading-level normalization, and table wrapper/header normalization fixtures |
| 362. Python oracle fail-closed boundary | complete | Legacy Python/OpenDataLoader hybrid baseline scripts now require `DOCTRUTH_ALLOW_PYTHON_ORACLE=1` before launching the heavy oracle path. Added a boundary smoke proving default OpenDataLoader and MNN runners do not call the Python prediction adapter and that the legacy oracle runner refuses to start without explicit opt-in | RED `sh scripts/smoke-doctruth-python-boundary.sh` failed because the legacy runner did not require opt-in; GREEN smoke after fail-closed guard |
