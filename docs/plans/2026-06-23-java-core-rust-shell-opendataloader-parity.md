# Java Core Rust Shell OpenDataLoader Parity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reach OpenDataLoader benchmark parity by keeping the proven Java/PDFBox/OpenDataLoader-style parser quality path as the document parsing core, while replacing Python/Docling/Torch runtime shells with Rust-owned orchestration, model workers, benchmark execution, and TrustDocument normalization.

**Architecture:** Java owns the canonical document parser backend for PDF text extraction, layout geometry, table heuristics, headings, reading order, veraPDF/PDFBox compatibility, and TrustDocument emission. Rust owns the long-running local runtime shell: process lifecycle, corpus runner, resource accounting, MNN model worker, JSONL protocol, OpenDataLoader Bench prediction generation, and Python-free default execution. Python/OpenDataLoader original runners remain oracle-only fixtures, never production fallback.

**Tech Stack:** Java 25/Maven, Apache PDFBox 3, existing DocTruth TrustDocument model, OpenDataLoader PDF reference under `third_party/`, Rust/Cargo, serde/serde_json, stdio JSONL, MNN model worker boundary, OpenDataLoader Bench corpus/evaluator.

---

## Why This Replaces The Previous Execution Direction

The previous plan had the right practical insight but the wrong enforcement:

```text
OpenDataLoader hybrid quality baseline first
-> DocTruth TrustDocument adapter
-> Rust deterministic local parser parity
-> MNN-first lazy model runtime
-> OpenDataLoader/Docling/Python/Torch as benchmark oracle only
```

What went wrong:

- Repo policy and several docs over-rotated to "Rust parser core replaces Java/PDFBox."
- Implementation then chased Rust parser heuristics directly instead of first preserving the Java/OpenDataLoader quality path.
- The Rust parity matrix mostly records partial processor behavior, not full OpenDataLoader algorithm parity.
- Current full200 score proves the gap: `overall=0.745414`, with the largest misses in reading order, heading hierarchy, and table structure.

Corrected direction:

```text
Java/OpenDataLoader-compatible parser core = quality source of truth
Rust runtime shell = Python/Torch/Docling replacement and edge runtime
TrustDocument = canonical DocTruth schema
OpenDataLoader original = benchmark oracle only
```

This is not a brand-new product strategy. It is a corrective execution plan for the already intended practical path: preserve parser accuracy first, then Rustify the expensive outer runtime.

## Non-Negotiable Boundaries

- Do not replace the Java parser core until benchmark parity is achieved and a separate Rust core ADR is approved.
- Do not add Python as a production fallback.
- Do not run one Java process per PDF in benchmark mode; the Java backend must stay warm across the corpus.
- Do not claim OpenDataLoader parity from fixture-only tests.
- Do not make external schemas canonical. Normalize everything through TrustDocument.
- Do not hide quality loss behind resource wins. Benchmark quality and runtime metrics must be reported together.

## Current Evidence Baseline

Use this as the current regression target:

```text
branch: feat/opendataloader-parity-coverage
run: third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-current-20260623-180244/
parsed: 199/200
elapsed: 221.6s
mean: 1.11s/doc
overall: 0.745414
nid: 0.860092
teds: 0.496416
mhs: 0.483837
```

Largest gap buckets from the current triage:

```text
reading_order_or_text_normalization: 89
heading_hierarchy_mismatch: 76
heading_missing: 7
table_structure_mismatch: 16
table_missing: 8
text_noise_or_duplicates: 2
text_missing_or_truncated: 2
```

## Phase 1: Fix Product/Architecture Contracts

### Task 1.1: Rewrite parser ownership docs

Files:

- `AGENTS.md`
- `docs/pdf-parser-runtime-prd.md`
- `docs/plans/2026-06-18-opendataloader-rustification-tdd-plan.md`
- `docs/plans/python-to-rust-parser-parity.md`
- `docs/parser/opendataloader-parity-matrix.md`

Change:

- State that Java/PDFBox/OpenDataLoader-compatible parsing is the current default quality core.
- State that Rust owns runtime shell, worker lifecycle, model runtime, corpus runner, resource accounting, and optional future parser modules.
- State that Python/OpenDataLoader original is oracle-only.
- State that "Rust parser core" is a future ADR, not current default.

Tests:

- Update `src/test/java/ai/doctruth/ArchitectureContractTest.java` to assert these exact policy lines exist:
  - `Java/OpenDataLoader-compatible parser core is the current quality source of truth`
  - `Rust owns the runtime shell and Python replacement boundary`
  - `Python/OpenDataLoader original runners are oracle-only`

Verification:

```bash
mvn -q -Dtest=ArchitectureContractTest test
git diff --check
```

Commit:

```bash
git add AGENTS.md docs/pdf-parser-runtime-prd.md docs/plans/2026-06-18-opendataloader-rustification-tdd-plan.md docs/plans/python-to-rust-parser-parity.md docs/parser/opendataloader-parity-matrix.md src/test/java/ai/doctruth/ArchitectureContractTest.java
git commit -m "docs: correct opendataloader parser ownership boundary"
```

## Phase 2: Promote Java OpenDataLoader Backend From Oracle To First-Class Local Backend

### Task 2.1: Add backend contract tests before implementation

Files:

- `src/test/java/ai/doctruth/opendataloader/OpenDataLoaderJavaBackendContractTest.java`
- `src/test/java/ai/doctruth/opendataloader/OpenDataLoaderBackendProtocolTest.java`

Test cases:

- A sample PDF produces a backend response with:
  - `backend = "opendataloader-java-core"`
  - `schemaVersion`
  - `markdown`
  - `blocks[]`
  - `tables[]`
  - `headings[]`
  - `sourceMap[]`
  - `warnings[]`
  - `metrics`
- Structured blocks include `id`, `kind`, `pageIndex`, `bbox`, `readingOrder`, `text`.
- Tables include cell-level row/column coordinates when available.
- Response can be converted to `TrustDocument` without losing source refs.

Verification should fail before implementation:

```bash
mvn -q -Dtest=OpenDataLoaderJavaBackendContractTest,OpenDataLoaderBackendProtocolTest test
```

### Task 2.2: Implement Java backend DTOs and parser facade

Files:

- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderBackendRequest.java`
- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderBackendResponse.java`
- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderBlock.java`
- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderTable.java`
- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderTableCell.java`
- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderSourceRef.java`
- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderJavaBackend.java`
- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderTrustDocumentAdapter.java`

Implementation:

- Reuse existing `PdfDocumentParser`, `PdfPageBlockExtractor`, `PdfPageTableExtractor`, `PdfBorderlessTableExtractor`, `PdfSemanticSectionCoalescer`, and `TrustDocumentParser`.
- Do not duplicate parser algorithms in Rust for this phase.
- Expose the parser output as OpenDataLoader-shaped structured blocks, then normalize into TrustDocument.
- Keep warning codes explicit for unsupported exact parity features.

Verification:

```bash
mvn -q -Dtest=OpenDataLoaderJavaBackendContractTest,OpenDataLoaderBackendProtocolTest test
mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest,PdfBorderlessTableExtractionTest test
```

Commit:

```bash
git add src/main/java/ai/doctruth/opendataloader src/test/java/ai/doctruth/opendataloader
git commit -m "feat: add opendataloader java parser backend"
```

## Phase 3: Add Warm Java Backend Process For Rust Runtime

### Task 3.1: Add Java JSONL backend CLI

Files:

- `src/main/java/ai/doctruth/opendataloader/OpenDataLoaderBackendCli.java`
- `src/test/java/ai/doctruth/opendataloader/OpenDataLoaderBackendCliTest.java`
- `src/main/java/ai/doctruth/cli/DocTruthCli.java`
- `src/main/java/ai/doctruth/cli/Usage.java`

Behavior:

- Add hidden/developer command:

```bash
doctruth opendataloader-backend --stdio-jsonl
```

- Read one JSON request per line from stdin.
- Write one JSON response per line to stdout.
- Keep JVM process alive across documents.
- Return structured error JSON instead of crashing on one bad PDF.
- Include per-document parse timings and peak Java process metadata when available.

Tests:

- CLI parses two requests through one process.
- Malformed request returns structured error and process stays alive.
- Unsupported options are rejected fail-closed.

Verification:

```bash
mvn -q -Dtest=OpenDataLoaderBackendCliTest test
```

### Task 3.2: Add Rust warm-process client

Files:

- `runtime/doctruth-runtime/src/opendataloader_java_backend.rs`
- `runtime/doctruth-runtime/src/lib.rs`
- `runtime/doctruth-runtime/src/main.rs`
- `runtime/doctruth-runtime/tests/opendataloader_java_backend_contract.rs`

Behavior:

- Spawn the Java backend once for a benchmark run.
- Send JSONL requests and parse JSONL responses.
- Track startup time separately from per-document parse time.
- Kill the child process at the end of the run.
- Fail closed if the Java backend exits or emits invalid JSON.

Tests:

- A fake JSONL worker proves Rust sends multiple documents to one process.
- A fake worker with bad JSON returns a structured error.
- A fake worker with one failed PDF continues to parse the next request.

Verification:

```bash
cd runtime/doctruth-runtime && cargo test --test opendataloader_java_backend_contract
```

Commit:

```bash
git add src/main/java/ai/doctruth/opendataloader src/test/java/ai/doctruth/opendataloader runtime/doctruth-runtime/src runtime/doctruth-runtime/tests
git commit -m "feat: add warm java opendataloader backend bridge"
```

## Phase 4: Route OpenDataLoader Bench Through The Java Quality Core

### Task 4.1: Add backend mode to Rust benchmark prediction generator

Files:

- `runtime/doctruth-runtime/src/main.rs`
- `runtime/doctruth-runtime/src/lib.rs`
- `runtime/doctruth-runtime/tests/benchmark_corpus_contract.rs`
- `scripts/run-doctruth-opendataloader-bench.sh`

Behavior:

- Add explicit backend mode:

```bash
doctruth-runtime opendataloader-prediction \
  --backend opendataloader-java-core \
  --manifest third_party/opendataloader-bench/... \
  --out third_party/opendataloader-bench/prediction/doctruth-java-core-...
```

- Default benchmark backend should be `opendataloader-java-core`.
- Existing Rust heuristic backend remains available as `rust-edge-fast`, but not called parity.
- Prediction output must include:
  - `backend`
  - `javaBackendCommand`
  - `rustRuntimeVersion`
  - `parserPolicy`
  - `startupMs`
  - `perDocumentMs`
  - `rssSamples`
  - source hashes

Tests:

- The runner writes OpenDataLoader-compatible Markdown artifacts.
- The runner records backend metadata.
- The runner does not invoke Python unless `--oracle-python` is explicitly passed.

Verification:

```bash
cd runtime/doctruth-runtime && cargo test --test benchmark_corpus_contract
```

### Task 4.2: Add no-Python default guard

Files:

- `runtime/doctruth-runtime/tests/opendataloader_python_boundary_contract.rs`
- `scripts/check-no-python-defaults.sh`

Behavior:

- In production/default benchmark mode, these strings must not appear in execution path config:
  - `python`
  - `docling`
  - `torch`
  - `opendataloader-hybrid`
- They may appear only under oracle test fixtures and docs that explicitly say oracle-only.

Verification:

```bash
cd runtime/doctruth-runtime && cargo test --test opendataloader_python_boundary_contract
bash scripts/check-no-python-defaults.sh
```

Commit:

```bash
git add runtime/doctruth-runtime scripts
git commit -m "feat: route opendataloader bench through java quality backend"
```

## Phase 5: Port Remaining Python Outer Runtime Responsibilities To Rust

### Task 5.1: Replace Python prediction packaging

Files:

- `runtime/doctruth-runtime/src/opendataloader_prediction.rs`
- `runtime/doctruth-runtime/src/opendataloader_report.rs`
- `runtime/doctruth-runtime/tests/opendataloader_prediction_contract.rs`

Behavior:

- Rust writes the exact prediction folder shape expected by OpenDataLoader Bench:
  - `markdown/`
  - `summary.json`
  - `cases/*.json`
  - `failures/*.json`
  - `resources.json`
  - `reference-comparison.json`
  - `reference-comparison.md`
- Python evaluator is allowed only as an external oracle command, not packaging logic.

Verification:

```bash
cd runtime/doctruth-runtime && cargo test --test opendataloader_prediction_contract
```

### Task 5.2: Keep model execution behind MNN worker boundary

Files:

- `runtime/doctruth-runtime/src/bin/doctruth-mnn-model-worker.rs`
- `runtime/doctruth-runtime/tests/model_worker_contract.rs`
- `runtime/doctruth-runtime/tests/opendataloader_model_runtime_contract.rs`
- `docs/parser/opendataloader-parity-matrix.md`

Behavior:

- MNN model worker remains lazy and optional.
- Java parser core can request OCR/table/layout model outputs through the Rust worker protocol.
- No Torch/ONNXRuntime process is used in default mode.
- If model artifacts are missing, return `MODEL_ARTIFACT_MISSING` and mark the relevant case unsupported; do not silently fall back to Python.

Verification:

```bash
cd runtime/doctruth-runtime && cargo test --test model_worker_contract --test opendataloader_model_runtime_contract
```

Commit:

```bash
git add runtime/doctruth-runtime docs/parser/opendataloader-parity-matrix.md
git commit -m "feat: make rust own opendataloader packaging and model worker boundary"
```

## Phase 6: Restore OpenDataLoader Algorithm Coverage In Java

### Task 6.1: Build a processor parity checklist from reference behavior

Files:

- `docs/parser/opendataloader-processor-gap-report.md`
- `src/test/java/ai/doctruth/opendataloader/OpenDataLoaderProcessorParityTest.java`

Processor areas:

- PDF text normalization
- hidden/off-page/tiny/background text filtering
- duplicate text suppression
- XY-Cut / geometry projection reading order
- paragraph/line merging
- heading promotion and hierarchy
- table detection
- borderless table clustering
- table cell grid reconstruction
- caption handling
- OCR region routing
- scanned PDF error semantics

Tests:

- Each processor area has at least one focused fixture or synthetic contract.
- Current status is one of:
  - `matched`
  - `partial`
  - `oracle-only`
  - `missing`
- No area can be marked `matched` without a focused test and one full-bench evidence case.

Verification:

```bash
mvn -q -Dtest=OpenDataLoaderProcessorParityTest test
```

### Task 6.2: Copy/adapt OpenDataLoader behavior in Java first

Files will be added as needed under:

- `src/main/java/ai/doctruth/opendataloader/processors/`
- `src/test/java/ai/doctruth/opendataloader/processors/`

Implementation order:

1. Hidden/off-page/tiny/background text filters.
2. Duplicate text suppression.
3. Geometry projection reading order.
4. Heading hierarchy reconstruction.
5. Table border/cluster heuristics.
6. Borderless table reconstruction.
7. Caption binding.
8. OCR region routing contract.

Rule:

- Copy/adapt behavior from the Apache-2.0 OpenDataLoader reference where available.
- Keep license attribution in `NOTICE` and local source comments for copied/adapted algorithm sections.
- Do not implement targeted one-off fixes for only one benchmark PDF unless the rule generalizes and has a focused test.

Verification after each processor group:

```bash
mvn -q -Dtest='ai.doctruth.opendataloader.**.*Test' test
```

Commit after each meaningful processor group:

```bash
git add src/main/java/ai/doctruth/opendataloader src/test/java/ai/doctruth/opendataloader docs/parser/opendataloader-processor-gap-report.md NOTICE
git commit -m "feat: align opendataloader <processor-name> behavior"
```

Current Phase 6 progress:

- Table run segmentation and stacked header-band absorption are implemented in
  `PdfBorderlessTableExtractor`.
- First-column continuation merge is implemented for OpenDataLoader-style
  multi-line cells such as `Environment, Health and Safety`, `Compliances with
  imprisonment`, and `Percentage of imprisonment clauses`.
- Spacer-column collapse is implemented for header-only/data-only split columns
  such as `Small | Medium |  | Large`.
- Verified with `doctruth-java-core-phase6-table-spacer-collapse` smoke:
  - `01030000000083` TEDS `0.9958`
  - `01030000000127` TEDS `0.888889`
- Added wide long-text comparative table recovery for OpenDataLoader case
  `01030000000088`:
  - detects 4+ column long-text comparative tables without collapsing the
    page into one giant table row
  - uses word-zone column assignment only for the wide-text path, while keeping
    normal borderless tables on the existing cell-cluster assignment
  - merges multi-row headers into one Markdown/TrustDocument table header
  - merges blank-first continuation rows into the prior data row across
    long-text evidence columns
- Verified with refreshed Java CLI jar:
  - `01030000000088` single-doc bench TEDS `0.999827`, TEDS_s `1.0`,
    overall `0.983936`
  - `doctruth-java-core-phase6-wide-text-table` smoke parsed 5/5 documents,
    TEDS mean `0.9979`, no Python/Torch/Docling production residency
  - smoke cases: `01030000000083` TEDS `0.9958`, `01030000000127` TEDS `1.0`
- Added dense benchmark matrix table recovery for OpenDataLoader case
  `01030000000189`:
  - detects table rows where body rows expose many anchors but header rows
    contain one long spanning cell
  - splits spanning header cells with word-center column assignment while
    keeping normal table rows on existing cell-cluster assignment
  - adds `01030000000189` to the Java-core smoke gate as a dense matrix table
- Verified with refreshed Java CLI jar:
  - `01030000000189` single-doc bench improved from TEDS `0.783577`,
    overall `0.56443` to TEDS `0.947368`, overall `0.626801`
  - `doctruth-java-core-phase6-dense-matrix-table` smoke parsed 6/6
    documents, TEDS mean `0.981056`, no Python/Torch/Docling production
    residency
  - `cargo test --test opendataloader_table_processor_contract` passed 5/5,
    including the matrix-table case `01030000000189`
- Added sparse grid furniture rejection for OpenDataLoader cases
  `01030000000141` and `01030000000198`:
  - rejects whole-page sparse grids with only one non-blank cell instead of
    promoting repeated footer or contents-page text into fake Markdown tables
  - preserves the degenerate-grid fallback before sparse-grid rejection so
    wide comparative table case `01030000000088` remains recovered
  - focused tests guard that `01030000000141` does not emit repeated
    `and .org` table furniture and `01030000000198` keeps `Contents` /
    `Overview of OCR Pack` as text instead of a giant table row
- Verified with refreshed Java CLI jar and Rust contract tests:
  - `mvn -q -Dtest=PdfBorderlessTableExtractionTest test`
  - `mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest test`
  - `cd runtime/doctruth-runtime && cargo test --test opendataloader_table_processor_contract`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase8-sparse-grid-guard-smoke bash scripts/run-opendataloader-java-core-parity.sh --smoke`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase8-sparse-grid-guard-full200 bash scripts/run-opendataloader-java-core-parity.sh --full200`
- Latest full200 evidence:
  - artifact:
    `third_party/opendataloader-bench/prediction/doctruth-java-core-phase8-sparse-grid-guard-full200/full200`
  - parsed `200/200`
  - elapsed `15235.8335` ms, mean `76.179168` ms/doc
  - overall `0.626221`, NID `0.894930`, TEDS `0.341325`, MHS `0.006794`
  - no Python/Torch/Docling production residency
  - `01030000000198` improved to overall `0.477420`, NID `0.954839`
  - `01030000000088` stayed high at overall `0.916727`, TEDS `0.908856`
- Added clean Markdown heading-node rendering for existing TrustDocument
  heading units:
  - `TrustDocument.toMarkdownClean()` now emits short heading units as
    Markdown `# Heading` blocks instead of plain paragraphs
  - content/evidence JSON and plain-text output remain unchanged
  - this aligns the DocTruth LLM-facing Markdown output with the
    OpenDataLoader heading-hierarchy evaluator without changing parser
    classification rules
- Verified with refreshed Java CLI jar:
  - `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`
  - `mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest,PdfBorderlessTableExtractionTest test`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase9-heading-markdown-smoke bash scripts/run-opendataloader-java-core-parity.sh --smoke`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase9-heading-markdown-full200 bash scripts/run-opendataloader-java-core-parity.sh --full200`
- Latest phase9 full200 evidence:
  - artifact:
    `third_party/opendataloader-bench/prediction/doctruth-java-core-phase9-heading-markdown-full200/full200`
  - parsed `200/200`
  - elapsed `15343.369` ms, mean `76.716845` ms/doc
  - overall `0.706434`, NID `0.894879`, TEDS `0.341325`, MHS `0.315461`
  - no Python/Torch/Docling production residency
  - MHS improved from `0.006794` to `0.315461`; overall improved from
    `0.626221` to `0.706434`
- Added standalone title-case document heading classification:
  - promotes short section labels such as `Narratives in Chuj`,
    `Introduction to the Texts`, and `7 Variants of SJ Observer Models`
  - keeps page labels such as `Chapter 2`, key-value fields, lists, and
    sentence-like text as body
  - this improves heading hierarchy without adding benchmark-specific PDF
    patches
- Verified with refreshed Java CLI jar:
  - `mvn -q -Dtest=PdfHeadingClassificationTest test`
  - `mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest,PdfBorderlessTableExtractionTest,TrustDocumentRenderedOutputTest test`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase10-title-heading-smoke bash scripts/run-opendataloader-java-core-parity.sh --smoke`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase10-title-heading-full200 bash scripts/run-opendataloader-java-core-parity.sh --full200`
- Latest phase10 full200 evidence:
  - artifact:
    `third_party/opendataloader-bench/prediction/doctruth-java-core-phase10-title-heading-full200/full200`
  - parsed `200/200`
  - elapsed `15111.002791` ms, mean `75.555014` ms/doc
  - overall `0.746136`, NID `0.894655`, TEDS `0.341325`, MHS `0.472714`
  - no Python/Torch/Docling production residency
  - overall now slightly beats the historical baseline `0.745414`, but TEDS
    and MHS still miss acceptance
- Added column-stream numeric table reconstruction for text-layer tables such
  as OpenDataLoader case `01030000000051`:
  - detects tables where numeric data rows expose stable anchors but header
    rows and first-column labels are split across multiple visual rows
  - uses numeric data rows to derive anchors, zone-based projection for header
    rows, nearest-anchor projection for data rows, and first-column
    continuation merging for labels such as `House of Representatives`
  - runs only after the existing normal/wide/dense borderless paths fail, so it
    does not steal already recovered cases such as `01030000000083`
- Verified with refreshed Java CLI jar:
  - `mvn -q -Dtest=PdfBorderlessTableExtractionTest#opendataloaderColumnStreamGovernmentPositionsTableBecomesStructuredTable test`
  - `mvn -q -Dtest=PdfBorderlessTableExtractionTest test`
  - `mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest,TrustDocumentRenderedOutputTest test`
  - `cd runtime/doctruth-runtime && cargo test --test opendataloader_table_processor_contract`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase11-column-stream-table-smoke bash scripts/run-opendataloader-java-core-parity.sh --smoke`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase11-column-stream-table-full200 bash scripts/run-opendataloader-java-core-parity.sh --full200`
- Latest phase11 full200 evidence:
  - artifact:
    `third_party/opendataloader-bench/prediction/doctruth-java-core-phase11-column-stream-table-full200/full200`
  - parsed `200/200`
  - elapsed `15896.198792` ms, mean `79.480994` ms/doc
  - overall `0.749896`, NID `0.896324`, TEDS `0.378735`, MHS `0.472728`
  - no Python/Torch/Docling production residency
  - case `01030000000051` improved from TEDS `0.0` to `0.998662`
- Broadened column-stream numeric table reconstruction:
  - supports three-column observer/count tables such as
    `01030000000045`
  - supports data-only continuation tables without a header row such as
    `01030000000053`
  - treats comma-formatted values like `17,266` and `9,835` as numeric cells
  - preserves the phase11 `01030000000051` recovery and existing
    `01030000000083` comparative table recovery
- Verified with refreshed Java CLI jar:
  - `mvn -q -Dtest=PdfBorderlessTableExtractionTest#opendataloaderColumnStreamObserverTableBecomesStructuredTable+opendataloaderDataOnlyContinuationTableBecomesStructuredTable test`
  - `mvn -q -Dtest=PdfBorderlessTableExtractionTest test`
  - `mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest,TrustDocumentRenderedOutputTest test`
  - `cd runtime/doctruth-runtime && cargo test --test opendataloader_table_processor_contract`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase12-column-stream-batch-smoke bash scripts/run-opendataloader-java-core-parity.sh --smoke`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase12-column-stream-batch-full200 bash scripts/run-opendataloader-java-core-parity.sh --full200`
- Latest phase12 full200 evidence:
  - artifact:
    `third_party/opendataloader-bench/prediction/doctruth-java-core-phase12-column-stream-batch-full200/full200`
  - parsed `200/200`
  - elapsed `15199.047083` ms, mean `75.995235` ms/doc
  - overall `0.755331`, NID `0.898216`, TEDS `0.426354`, MHS `0.475145`
  - no Python/Torch/Docling production residency
  - cases `01030000000045` and `01030000000053` improved from TEDS `0.0`
    to `1.0`
- Remaining table work before claiming parity:
  - broader table-cell grid normalization beyond the current smoke and
    wide-text cases
  - model/OCR table cases
  - full200 parity; latest full200 is still below the historical target
    `overall=0.745414`, `TEDS=0.496416`, `MHS=0.483837`
- Added geometry-driven cluster fallback for text-heavy tables after the
  existing numeric/table-specific fallback:
  - covers stacked text headers and long prose cells such as
    `01030000000178`
  - covers single-cell header splitting over stable data anchors such as
    `01030000000117`
  - partially covers long service-flow tables such as `01030000000200`
  - keeps phase12 numeric column-stream tables ahead of the cluster fallback
  - rejects resume-style parallel section headings to avoid false table
    promotion
- Verified with refreshed Java CLI jar:
  - `mvn -q -Dtest=PdfBorderlessTableExtractionTest#opendataloaderTextContinuationPromotionalMaterialsTableBecomesStructuredTable+opendataloaderLongTextServiceFlowTableBecomesStructuredTable+opendataloaderMeasurementMatrixTableBecomesStructuredTable test`
  - `mvn -q -Dtest=PdfBorderlessTableExtractionTest test`
  - `mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest,TrustDocumentRenderedOutputTest test`
  - `cd runtime/doctruth-runtime && cargo test --test opendataloader_table_processor_contract`
  - `mvn -q -DskipTests package`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase13-cluster-text-table-smoke bash scripts/run-opendataloader-java-core-parity.sh --smoke`
  - `DOCTRUTH_OPENDATALOADER_GATE_TIMESTAMP=phase13-cluster-text-table-full200 bash scripts/run-opendataloader-java-core-parity.sh --full200`
- Latest phase13 full200 evidence:
  - artifact:
    `third_party/opendataloader-bench/prediction/doctruth-java-core-phase13-cluster-text-table-full200/full200`
  - parsed `200/200`
  - elapsed `16597.878291` ms, mean `82.989391` ms/doc
  - overall `0.758242`, NID `0.893380`, TEDS `0.503217`, MHS `0.483981`
  - no Python/Torch/Docling production residency
  - case `01030000000178`: overall `0.933164`, TEDS `0.998433`, MHS `0.820391`
  - case `01030000000117`: overall `0.734091`, TEDS `1.0`, MHS `0.270142`
  - case `01030000000200`: overall `0.551558`, TEDS `0.413180`, MHS `0.559491`
  - phase12 recoveries `01030000000045` and `01030000000053` remain at TEDS
    `1.0`
- Current acceptance status:
  - initial overall target `> 0.745414`: passed with `0.758242`
  - initial TEDS target `> 0.496416`: passed with `0.503217`
  - initial MHS target `> 0.483837`: passed with `0.483981`
  - full OpenDataLoader hybrid/model parity is still not claimed; remaining
    gaps are multi-segment rowspan tables, OCR/image-only tables,
    chart/table distinction, heading hierarchy, and reading-order/text
    normalization.

## Phase 7: Run Benchmark Only After Code-Level Parity Gates Pass

### Task 7.1: Add local benchmark gate script

Files:

- `scripts/run-opendataloader-java-core-parity.sh`
- `docs/parser/opendataloader-bench-runbook.md`

Script behavior:

- Build Java once.
- Build Rust once.
- Start Java backend once.
- Run selected smoke set first:
  - simple single column
  - two-column
  - sidebar resume
  - bordered table
  - borderless table
  - scanned/OCR fixture if model artifacts exist
- Then run full200 only if smoke passes.
- Write artifacts under:

```text
third_party/opendataloader-bench/prediction/doctruth-java-core-<timestamp>/
```

Verification:

```bash
bash scripts/run-opendataloader-java-core-parity.sh --smoke
```

### Task 7.2: Full200 acceptance

Run:

```bash
bash scripts/run-opendataloader-java-core-parity.sh --full200
```

Required report fields:

- overall/nid/teds/mhs
- parsed count
- failed count
- elapsed time
- mean ms/doc
- Java backend startup ms
- Java backend steady RSS range
- Rust runtime steady RSS range
- model worker steady RSS range when enabled
- top 20 worst deltas against reference
- processor bucket counts

Initial acceptance:

- Must beat current `rust-edge-fast` baseline:
  - `overall > 0.745414`
  - `teds > 0.496416`
  - `mhs > 0.483837`
- Must reduce gap buckets in at least two of:
  - reading order
  - heading hierarchy
  - table structure
- Must not use Python in default mode.
- Must keep one warm Java backend process for the corpus.

Parity target:

- Match or stay within a small documented delta of OpenDataLoader non-hybrid Java/PDF path.
- Hybrid/model parity is only required when matching model artifacts and preprocessing have been wired through the Rust/MNN worker.

Commit:

```bash
git add scripts docs/parser third_party/opendataloader-bench/prediction/<selected-report-files-only>
git commit -m "test: record opendataloader java core benchmark baseline"
```

## Done Criteria

This work is done when:

- Docs no longer claim Java/PDFBox is merely legacy for the current parser quality path.
- Java OpenDataLoader-compatible backend is callable directly and through a long-running stdio JSONL process.
- Rust benchmark runtime uses that warm Java backend by default.
- Default benchmark mode has no Python/Docling/Torch dependency.
- OpenDataLoader Bench prediction artifacts are generated by Rust packaging around Java parser output.
- Processor parity has code-level tests before full200 runs.
- Full200 report beats the current `overall=0.745414` baseline and explains remaining deltas by processor bucket.

## Expected Commit Sequence

1. `docs: correct opendataloader parser ownership boundary`
2. `feat: add opendataloader java parser backend`
3. `feat: add warm java opendataloader backend bridge`
4. `feat: route opendataloader bench through java quality backend`
5. `feat: make rust own opendataloader packaging and model worker boundary`
6. `feat: align opendataloader text filtering behavior`
7. `feat: align opendataloader reading order behavior`
8. `feat: align opendataloader table behavior`
9. `test: record opendataloader java core benchmark baseline`

## Commands For Final Verification

```bash
mvn -q -Dtest=ArchitectureContractTest test
mvn -q -Dtest='ai.doctruth.opendataloader.**.*Test' test
mvn -q test
cd runtime/doctruth-runtime && cargo test
cd ../.. && bash scripts/check-no-python-defaults.sh
bash scripts/run-opendataloader-java-core-parity.sh --smoke
bash scripts/run-opendataloader-java-core-parity.sh --full200
git diff --check
```
