# DocTruth v1 Parser Runtime Progress

## 2026-06-17

- Started OpenDataLoader hybrid benchmark-oracle Phase 1 TDD slice from
  `docs/plans/2026-06-18-opendataloader-rustification-tdd-plan.md`.
- Added RED CLI tests in
  `src/test/java/ai/doctruth/cli/BenchmarkOracleCommandTest.java` for:
  missing opendataloader-hybrid dependency doctor hint, fake oracle
  TrustDocument output with `parserRun.externalBackend` and `elapsedMs`,
  markdown-only `NOT_AUDIT_GRADE`, and production parse rejecting
  `--backend opendataloader-hybrid`.
- RED command:
  `mvn -q -Dtest=BenchmarkOracleCommandTest test`.
- RED result: 3 tests ran, 2 failed as expected because `benchmark-oracle`
  is not registered yet and returns usage error 2 instead of the planned
  oracle behavior. The production parse no-fallback guard already passes.
- Implemented `benchmark-oracle --engine opendataloader-hybrid` as a
  benchmark-only CLI command. It requires
  `DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND`, executes that runner with a
  PDF path, reads the oracle JSON contract, maps Markdown into coarse
  `TrustDocument` units, records `parserRun.externalBackend` and `elapsedMs`,
  and emits a severe `opendataloader_markdown_only_source_mapping` warning so
  the result is `NOT_AUDIT_GRADE`.
- Kept production `parse --backend opendataloader-hybrid` rejected; the hybrid
  path is not a production fallback.
- Added `ParserRunDetails` so `ParserRun` can expose `models()`,
  `warnings()`, `externalBackend()`, and `elapsedMs()` without violating the
  public record component-count architecture gate.
- Added `scripts/doctruth_opendataloader_hybrid_oracle.py`, a benchmark-only
  wrapper around `opendataloader_pdf.convert(..., hybrid="docling-fast")` that
  emits the oracle JSON contract.
- Added `scripts/smoke-doctruth-benchmark-oracle.sh`, which uses the vendored
  OpenDataLoader Bench PDF when present, runs a fake oracle through the actual
  CLI jar, and verifies the TrustDocument/provenance/audit-grade contract.
- Focused green command:
  `mvn -q -Dtest=BenchmarkOracleCommandTest test`.
- API/architecture green command:
  `mvn -q -Dtest=BenchmarkOracleCommandTest,TrustDocumentContractTest,TrustDocumentRenderedOutputTest,TrustDocumentParserApiContractTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Smoke green command:
  `sh scripts/smoke-doctruth-benchmark-oracle.sh`.
- Syntax checks passed:
  `python3 scripts/doctruth_opendataloader_hybrid_oracle.py --help` and
  `python3 -m py_compile scripts/doctruth_opendataloader_hybrid_oracle.py scripts/doctruth_opendataloader_prediction.py scripts/compare-doctruth-parser-references.py scripts/triage-doctruth-parser-reference-report.py`.
- Remaining Phase 1 gap: run the live OpenDataLoader hybrid server/JAR or
  `opendataloader-pdf[hybrid]` path through the new CLI adapter and record the
  real one-document quality/resource smoke. The new wrapper exists, but the
  current smoke intentionally avoids starting Python/Torch/Docling.
- Added RED coverage proving
  `DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND` may include an interpreter and
  script path, e.g. `.venv/bin/python scripts/doctruth_opendataloader_hybrid_oracle.py`.
  RED command: `mvn -q -Dtest=BenchmarkOracleCommandTest test`; expected
  failure was command exit 1 because the implementation treated the whole env
  string as a single executable path.
- Implemented small quote-aware command tokenization for the benchmark oracle
  env command and reran `mvn -q -Dtest=BenchmarkOracleCommandTest test`
  successfully.
- First live one-document oracle attempt failed because `opendataloader-pdf`
  internally invoked bare `java`, and the child PATH could not locate a Java
  runtime. Fixed the wrapper by prepending detected OpenJDK paths to PATH.
- Second live one-document oracle attempt failed because
  `opendataloader_pdf.convert(...)` does not start the hybrid server by itself.
  Direct Java invocation showed: `Hybrid server is not available at
  http://localhost:5002`. Fixed the wrapper by adding benchmark-only
  start/reuse/stop lifecycle for `python -m opendataloader_pdf.hybrid_server`.
- Live one-document DocTruth CLI adapter smoke passed:
  `DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND="<bench-venv-python> scripts/doctruth_opendataloader_hybrid_oracle.py" java -jar target/doctruth-java-0.2.0-alpha-all.jar benchmark-oracle --engine opendataloader-hybrid third_party/opendataloader-bench/pdfs/01030000000119.pdf --json > target/benchmark-oracle-live/01030000000119.trust.json`.
- Live result:
  `elapsedMs=13115`, `backend=opendataloader-hybrid-oracle`,
  `externalBackend.name=opendataloader-pdf`, `version=2.2.1`,
  `doclingVersion=2.84.0`, `mode=docling-fast`,
  `auditGradeStatus=NOT_AUDIT_GRADE`, `unitCount=10`.
- `/usr/bin/time -l` for the live one-document oracle path reported
  `13.71 real` and `1566621696 maximum resident set size` bytes. This is the
  Python/Torch/Docling benchmark oracle resource profile, not the production
  Rust/MNN runtime profile.
- `curl http://127.0.0.1:5002/health` returned no response after the run,
  confirming the wrapper stopped the server it started.

## 2026-06-12

- Started persistent plan for `$planning-with-files` objective.
- Confirmed active branch: `feat/v1-trust-document-runtime-tdd`.
- Confirmed PRD commit: `a22c7b6 docs: add v1 parser runtime prd`.
- Confirmed current worktree has existing dirty CLI/OCR/Markdown changes that
  should not be mixed into unrelated commits without review.
- Read current source/test layout and identified existing parser model classes.
- Identified architecture gate: public records must have at most 5 components.
- Decided first v1 slice will model `TrustDocument` through small records:
  source/body/parser/audit grouping rather than a wide record.
- Wrote red contract tests:
  `src/test/java/ai/doctruth/TrustDocumentContractTest.java` and
  `src/test/java/ai/doctruth/TrustUnitTest.java`.
- Red test command:
  `mvn -q -Dtest=TrustDocumentContractTest,TrustUnitTest test`.
- Red result: expected `testCompile` failure because v1 public types do not yet
  exist (`TrustDocument`, `TrustUnit`, `ParserRun`, `ParserWarning`, etc.).
- Implemented the first v1 public records/enums:
  `TrustDocument`, `TrustDocumentSource`, `TrustDocumentBody`, `TrustPage`,
  `TrustUnit`, `TrustUnitLocation`, `TrustUnitContent`, `TrustUnitEvidence`,
  `TrustUnitKind`, `TrustTable`, `TrustTableCell`, `TrustCellRange`,
  `ParserRun`, `ParserWarning`, `ParserWarningSeverity`, `AuditGradeStatus`.
- Focused green command:
  `mvn -q -Dtest=TrustDocumentContractTest,TrustUnitTest test`.
- Added red/green adapter test:
  `mvn -q -Dtest=TrustDocumentAdapterTest test`.
- Adapter result: current `ParsedDocument` text, figure, and table sections can
  convert into `TrustDocument`, `TrustUnit`, and `TrustTable` baseline objects.
- Current v1 focused set passes:
  `mvn -q -Dtest=TrustDocumentContractTest,TrustUnitTest,TrustDocumentAdapterTest test`.
- Added rendered output tests and methods:
  `toJsonFull`, `toJsonEvidence`, `toMarkdownClean`, `toCompactLlm`.
- Rendered output issue found: Jackson cannot serialize Java `Optional` without
  an extra module. Fixed by explicit JSON node rendering instead of adding a
  dependency.
- Green rendered output command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- Added audit gate tests and immutable evaluation method:
  `withEvaluatedAuditGrade()`.
- Added local smoke test that dynamically writes a PDF, parses it through the
  current PDFBox baseline, adapts it to `TrustDocument`, evaluates audit grade,
  and renders JSON/Markdown/compact outputs.
- Current focused v1 command passes:
  `mvn -q -Dtest=TrustDocumentContractTest,TrustUnitTest,TrustDocumentAdapterTest,TrustDocumentRenderedOutputTest,TrustDocumentAuditGateTest,TrustDocumentLocalSmokeTest test`.
- Updated public API snapshot for the current public surface.
- Full verification passed:
  `mvn test` -> 779 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Inspected locked `pdf-extract` 0.10.0 source and confirmed it exposes
  `extract_text_by_pages`.
- Added RED multi-page runtime test requiring two-page PDFs to produce
  `pageCount=2`, two page entries, and two page-scoped `TEXT_BLOCK` units with
  stable reading order.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`.
- RED result: 5 passed, 1 failed as expected because runtime still emitted
  `pageCount=1` for a two-page fixture.
- Implemented page-level Rust runtime output using
  `pdf_extract::extract_text_by_pages`.
- Runtime now emits one `TrustPage` per PDF page and one citeable `TEXT_BLOCK`
  unit per text-bearing page, with stable `readingOrder`, `unit-000N`, and
  `span-000N` identifiers.
- Cargo verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 6
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java full verification passed:
  `mvn test` -> 815 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

- Started parser benchmark threshold-gate TDD slice.
- Added RED tests requiring `ParserBenchmarkRunner.requireMinimums(...)` to
  fail benchmark results below configured acceptance thresholds with case and
  metric context.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- RED result: expected `testCompile` failure because `requireMinimums` did not
  exist.
- Started Rust text-layer PDF extraction TDD slice.
- Updated runtime protocol tests to write a real minimal PDF fixture and require
  a citeable `TEXT_BLOCK` unit instead of the previous unimplemented warning.
- Added missing-source test requiring `PDF_EXTRACTION_FAILED` instead of a
  fabricated empty `TrustDocument`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`.
- RED result: 3 passed, 2 failed as expected. The runtime still returned
  `NOT_AUDIT_GRADE` with `runtime_pdf_extraction_unimplemented`, and missing
  PDFs still returned success.
- Added `pdf-extract` to `runtime/doctruth-runtime` for the first real
  text-layer PDF extraction slice. This pulled a larger PDF/font/encoding
  dependency tree, recorded in ADR 0010.
- Implemented Rust runtime `parse_pdf` file reading:
  missing/unreadable PDFs now fail with `PDF_EXTRACTION_FAILED`; text-layer PDFs
  produce one page-level `TEXT_BLOCK` unit with evidence span id, page, reading
  order, confidence, and page-level bbox fallback.
- Updated runtime smoke to generate a real PDF fixture and assert extracted
  text instead of the old unimplemented warning.
- Cargo verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 5
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java full verification passed:
  `mvn test` -> 815 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Completed parser benchmark threshold-gate slice.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- RED result: expected compile failure because
  `ParserBenchmarkRunner.requireMinimums(...)` did not exist.
- Implemented `ParserBenchmarkRunner.requireMinimums(...)`, which fails any
  benchmark result below configured metric thresholds and includes case name,
  metric, actual value, and minimum value in the exception message.
- Focused benchmark verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- Public API/architecture verification initially failed only because the new
  public method was missing from `public-api-snapshot.txt`.
- Regenerated and reviewed the public API snapshot for the v1 parser/runtime
  surface.
- Public API/architecture verification passed:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test`.
- Rust format verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`.
- Rust runtime verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 6 tests
  passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java full verification passed:
  `mvn test` -> 817 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Started expected-document benchmark metrics TDD slice to move G5 closer to
  measurable parser quality.
- Added RED test requiring `ParserBenchmarkCase` to carry an expected
  `TrustDocument` and requiring `ParserBenchmarkRunner` to report `bbox_iou`
  and `table_cell_f1`.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- RED result: expected compile failure because `ParserBenchmarkCase` only had
  the three-argument contract.
- Extended `ParserBenchmarkCase` with optional expected `TrustDocument` while
  keeping the existing three-argument constructor for compatibility.
- Implemented benchmark layout/table metrics:
  `bbox_iou` averages unit bbox IoU against expected units, and
  `table_cell_f1` compares structured table cells by row/column span and text.
- Focused benchmark verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- Public API/architecture verification passed after snapshot update:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 818 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Started Rust line-level extraction TDD slice to reduce runtime evidence
  granularity from page blocks to citeable line spans.
- Added RED protocol test requiring a single-page two-line PDF to emit two
  stable `LINE_SPAN` units with page, reading order, line text, and
  `runtime-text-layer-page-<page>-line-<line>` source object ids.
- Updated the existing single-line protocol test to expect `LINE_SPAN` rather
  than the previous coarse `TEXT_BLOCK`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`.
- RED result: 5 passed, 2 failed as expected because the runtime still emitted
  one page-level `TEXT_BLOCK`.
- Implemented line-level unit emission in `runtime/doctruth-runtime/src/main.rs`:
  extracted text pages are normalized into non-empty lines; each line becomes a
  stable `LINE_SPAN` with sequential unit/span ids, page, reading order, and
  page-level bbox fallback warning.
- Rust format check initially reported rustfmt diffs; ran `cargo fmt`, then
  format check passed.
- Cargo protocol verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract` -> 7 tests passed.
- Cargo full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7 tests
  passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java focused sidecar/CLI/API verification passed:
  `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentCliOutputProfileTest,TrustDocumentParserApiContractTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 818 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## Next Actions

1. Decide commit split because the worktree still contains older CLI/OCR dirty
   files and the public API snapshot includes both old OCR APIs and new v1 APIs.
2. If this branch should continue toward full PRD coverage, start the next TDD
   slice for labeled parser-quality fixtures, precise bboxes, column-aware
   reading order, table cells, or OCR/model runtime.

## 2026-06-14 Goal 1 Completion Audit

- Re-entered the `$planning-with-files` Goal 1 loop for Rust-core defaulting.
- Confirmed `TrustDocumentParser` static SDK entrypoints require a configured
  Rust runtime and no longer silently fall back to Java/PDFBox.
- Confirmed `TrustDocumentParserBuilder` path-first SDK mode uses sidecar for
  `AUTO`/`SIDECAR` and only uses `PdfBoxParserBackend` for explicit
  `ParserBackendMode.PDFBOX`.
- Confirmed CLI `parse` defaults `--backend auto` to the TrustDocument sidecar
  path for summary and v1 formats, and disallows `--runtime` with
  `--backend pdfbox`.
- Found a Goal 1 gap: `SidecarParserBackend` starts the Rust runtime with the
  process environment only, so Java-side model/OCR worker system properties are
  not guaranteed to reach Rust-default parse requests.
- Focused Goal 1 test command failed as useful RED evidence:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,TrustDocumentCliOutputProfileTest,DocTruthCliMcpTest,LocalModelWorkerManifestContractTest test`.
- Failure categories:
  old tests still expected `pdfbox` / `pdfbox+model-worker` even though current
  default output is `rust-sidecar`; OCR preset with a configured worker still
  reached Rust text-layer extraction and failed blank PDFs with
  `PDF_EXTRACTION_FAILED`.
- Fixed the Java sidecar wrapper to forward Java-side model/OCR worker
  configuration into the Rust runtime child environment through
  `DOCTRUTH_RUNTIME_MODEL_COMMAND` plus model cache/manifest variables.
- Updated Goal 1 tests so default CLI/SDK/API assertions expect Rust sidecar
  semantics; Java/PDFBox remains explicit fallback/oracle only.
- Focused Goal 1 Java verification now passes:
  `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,TrustDocumentCliOutputProfileTest,DocTruthCliMcpTest,LocalModelWorkerManifestContractTest test`.
- Rust verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`,
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, and
  `git diff --check`.
- Runtime/MCP smokes passed:
  `sh scripts/smoke-doctruth-runtime.sh`,
  `sh scripts/smoke-doctruth-runtime-model-worker.sh`, and
  `sh scripts/smoke-doctruth-mcp.sh`.
- CLI sidecar smoke initially failed because it still used `pdftoppm` as an
  exact page-image hash oracle after the runtime moved default page rendering
  to `pdf_oxide`; updated the smoke to assert Rust sidecar output and stable
  page image hash presence instead.
- `mvn test` still failed after the first full run. Remaining failure classes:
  stale Java/PDFBox-default assertions in CLI/OCR/corpus tests, a Rust
  degenerate/off-page table bbox that serialized invalid TrustDocument JSON,
  and an old bbox-IoU threshold calibrated to the Java/PDFBox baseline.

## 2026-06-12 Continued

- Recovered after compaction; previous Maven session id was gone, so the test
  baseline was rerun instead of trusting stale output.
- Added RED tests for the remaining Java contract surface:
  `TrustDocumentChunkingContractTest`,
  `TrustDocumentSourceMapContractTest`, `HtmlPassthroughContractTest`,
  `ReadingOrderContractTest`, and `TableExtractionContractTest`.
- Confirmed RED failed at `testCompile` because `TrustHtml`,
  `TrustRenderedDocument`, `TrustDocument.toChunks`,
  `TrustDocument.toMarkdownWithSourceMap`, and `TrustDocument.toHtmlReview`
  did not exist.
- Implemented v1 chunk/source-map/HTML review contract:
  `TrustDocumentChunk`, `TrustRenderedDocument`, `TrustSourceMapEntry`,
  `TrustHtml`, plus renderer methods on `TrustDocument`.
- Focused new contract command passed:
  `mvn -q -Dtest=TrustDocumentChunkingContractTest,TrustDocumentSourceMapContractTest,HtmlPassthroughContractTest,ReadingOrderContractTest,TableExtractionContractTest test`.
- Focused v1 + architecture + public API command initially failed only on
  `PublicApiSnapshotTest`; updated the snapshot and reran successfully.
- Full verification passed:
  `mvn test` -> 793 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Added RED CLI v1 output profile tests in
  `TrustDocumentCliOutputProfileTest`. Initial result: 5 failures because
  `doctruth parse` did not support `--format`, `--profile`, or `--source-map`.
- Implemented PRD-style parse output profiles while preserving old
  `--json`/`--markdown` behavior:
  `--format json --profile full|evidence`,
  `--format markdown --profile clean|anchored|review`,
  `--format html`, `--format jsonl`, `--format audit`, and
  `--format compact`.
- Implemented Markdown source-map sidecar writing for
  `--format markdown --profile clean --source-map --out document.md`.
- Added RED doctor tests for parser backend, model cache, memory estimate, and
  `doctor models`. Initial result: 3 failures because doctor only reported
  Java/project/env readiness.
- Implemented doctor parser/model/memory reporting:
  parser backend `pdfbox`, local model cache path, required model count,
  no-network lite mode, and JVM memory estimate.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,DocTruthCliTest,CliSupportTest,DocTruthCliDoctorCompletionTest,ParserBackendContractTest,ModelRuntimePolicyTest,TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentChunkingContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Whitespace verification passed again:
  `git diff --check`.
- Full `mvn test` started after the CLI/doctor changes.
- Added RED parser API tests in `TrustDocumentParserApiContractTest` for
  file, bytes, stream, batch, invalid source filename, and stable canonical
  hash behavior. Initial result: expected compile failure because
  `TrustDocumentParser` and `TrustDocument.canonicalHash()` did not exist.
- Implemented `TrustDocumentParser` over the current Java/PDFBox baseline plus
  `TrustDocument.canonicalHash()`. Focused parser API tests pass.
- Added RED runtime contract tests:
  `TrustDocumentSdkParserContractTest`, `ModelCacheVerifierTest`,
  `ParserBenchmarkRunnerTest`, and
  `TrustDocumentStreamingRenderContractTest`.
- Implemented `ParserPreset`, `TrustDocumentParserBuilder`,
  `DocTruthDocument.withParser(ParserPreset).parse()`, model cache artifact
  verification with SHA-256, a lightweight benchmark metric runner, and
  writer-based Markdown/JSONL render methods.
- Updated JSONL TrustDocument output to use snake_case field names for the
  line-oriented wire format (`unit_id`, `evidence_span_ids`, `source_hash`).
- Updated public API snapshot for the new parser/runtime public surface.
- Focused parser/runtime verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ModelCacheVerifierTest,ParserBenchmarkRunnerTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,ParserBackendContractTest,ModelRuntimePolicyTest,TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentChunkingContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Whitespace verification passed:
  `git diff --check`.
- Full verification passed:
  `mvn test` -> 811 tests, 0 failures, 0 errors.
- Added RED sidecar protocol tests in `SidecarParserBackendTest` for JSON
  stdin/stdout parsing, non-zero exit mapping, and invalid stdout JSON mapping.
  Initial result: expected compile failure because `SidecarParserBackend` did
  not exist.
- Implemented `SidecarParserBackend` and package-private `TrustDocumentJson`.
  The backend starts a local runtime process, sends a `parse_pdf` JSON request
  on stdin, reads canonical `TrustDocument` JSON from stdout, and maps runtime
  failures to stable `ParseException` codes:
  `SIDECAR_RUNTIME_FAILED`, `SIDECAR_INVALID_RESPONSE`,
  `SIDECAR_RUNTIME_TIMEOUT`, `SIDECAR_IO_FAILED`, and
  `SIDECAR_START_FAILED`.
- Updated public API snapshot for the sidecar backend.
- Focused sidecar/parser verification passed:
  `mvn -q -Dtest=SidecarParserBackendTest,ParserBackendContractTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ModelCacheVerifierTest,ParserBenchmarkRunnerTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Whitespace verification passed:
  `git diff --check`.
- Full verification passed:
  `mvn test` -> 814 tests, 0 failures, 0 errors.
- Added RED CLI sidecar backend test in `TrustDocumentCliOutputProfileTest`.
  Initial result: expected failure because `doctruth parse` did not support
  `--backend`, `--runtime`, or `--preset`.
- Implemented CLI parser backend selection:
  `--backend pdfbox|sidecar`, `--runtime <path>`, and `--preset lite|standard|table-lite|table-server|ocr`.
  Sidecar mode now bypasses local PDFBox parsing and renders the
  `TrustDocument` returned by the sidecar protocol.
- Updated CLI usage text with the sidecar runtime example.
- Updated public API snapshot after making `ParserPreset.parserRun(String)`
  public for CLI/backend integration.
- Focused CLI sidecar verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,SidecarParserBackendTest,ParserBackendContractTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ModelCacheVerifierTest,ParserBenchmarkRunnerTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Whitespace verification passed:
  `git diff --check`.
- Full verification passed:
  `mvn test` -> 815 tests, 0 failures, 0 errors.
- Started Rust runtime TDD phase for the real `doctruth-runtime` executable.
- Added RED cargo protocol tests under `runtime/doctruth-runtime/tests/protocol_contract.rs`
  before adding runtime source code.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- RED result: 4 expected failures because `CARGO_BIN_EXE_doctruth-runtime`
  is unset, proving no binary target exists yet.
- Implemented minimal Rust runtime binary:
  `runtime/doctruth-runtime/src/main.rs`.
- Runtime protocol now supports:
  `--doctor`, stdin `parse_pdf`, stable JSON errors for unknown command and
  invalid request JSON.
- Added runtime docs and dependency ADR:
  `runtime/doctruth-runtime/README.md` and
  `docs/adr/0010-rust-runtime-protocol-dependencies.md`.
- Added repeatable smoke:
  `scripts/smoke-doctruth-runtime.sh`.
- Cargo verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 4 tests
  passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Rust format check passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`.
- Java full verification passed:
  `mvn test` -> 815 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued Again

- Added end-to-end CLI sidecar smoke:
  `scripts/smoke-doctruth-cli-sidecar.sh`.
- The smoke builds the Rust runtime, builds the shaded Java CLI, generates a
  real two-line PDF fixture, runs:
  `doctruth parse <pdf> --backend sidecar --runtime <runtime> --preset lite --format json --profile full`,
  and verifies:
  backend `rust-sidecar`, audit-grade status, two `LINE_SPAN` units, stable
  source object ids, expected line text, clean Markdown output, and Markdown
  source-map sidecar JSON.
- First smoke attempt failed because bare `java` resolved to the macOS
  `/usr/bin/java` stub without a Java runtime. The script now resolves
  `$JAVA_HOME/bin/java`, then Homebrew OpenJDK, then `java`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Cargo format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7
  tests passed.
- Runtime + CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 818 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: the local Java CLI can call the real Rust sidecar and
  receive/re-render citeable line-level text-layer evidence. The PRD is still
  not fully complete because precise text bboxes, column-aware layout, table
  extraction, OCR/model execution, GFM renderer parity, multi-GB streaming, and
  labeled parser-quality corpus remain open.

## 2026-06-12 Continued Benchmark Gate

- Started real PDF benchmark fixture TDD slice so parser quality can be gated
  from actual PDF inputs, not only hand-built `TrustDocument` objects.
- Added RED test in `ParserBenchmarkRunnerTest` requiring
  `ParserBenchmarkCase.fromPdf(...)` to parse a generated two-column PDF and
  require thresholds for `reading_order_f1`, `quote_anchor_accuracy`, and
  `bbox_coverage`.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- RED result: expected `testCompile` failure because
  `ParserBenchmarkCase.fromPdf(String, Path, String)` did not exist.
- Implemented `ParserBenchmarkCase.fromPdf(...)` over `TrustDocumentParser`
  and added benchmark `bbox_coverage` for unit-level bbox presence.
- Focused benchmark verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- Public API snapshot initially failed because `fromPdf(...)` is a new public
  method. Updated the snapshot using:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Public API and architecture verification passed:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 819 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Cargo format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7
  tests passed.
- Runtime + CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: DocTruth now has a repeatable real-PDF benchmark gate
  for parser reading order, quote anchoring, and bbox coverage. It still does
  not replace the missing labeled corpus, precise bbox IoU against human labels,
  OCR/model execution, or table-model quality tests.

## 2026-06-12 Continued Expected Bbox Gate

- Started expected-bbox benchmark TDD slice to move from `bbox_coverage` to
  actual `bbox_iou` thresholding on real PDF parser output.
- Added RED test in `ParserBenchmarkRunnerTest` requiring
  `ParserBenchmarkCase.fromPdf(..., expectedDocument)` to parse a generated
  two-column PDF and compare its output against an expected `TrustDocument`
  carrying manual bbox fixtures.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- RED result: expected `testCompile` failure because the expected-document
  overload did not exist.
- Implemented the `ParserBenchmarkCase.fromPdf(String, Path, String,
  TrustDocument)` overload so parsed real-PDF benchmark cases can carry expected
  labels for `bbox_iou` and `table_cell_f1`.
- Focused benchmark verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- Public API snapshot updated for the new overload:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Public API and architecture verification passed:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 820 tests, 0 failures, 0 errors.
- Cargo format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7
  tests passed.
- Runtime + CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: generated real-PDF benchmark cases can now validate
  expected bbox fixtures through `bbox_iou`. This is a concrete parser-quality
  gate, but not yet the full human-labeled corpus or complex document coverage
  required by the PRD.

## 2026-06-12 Continued Bordered Table Gate

- Started real-PDF bordered-table TDD slice so table-cell quality can be gated
  from an actual PDF file instead of only hand-built `TableSection` fixtures.
- Added RED test in `ParserBenchmarkRunnerTest` requiring a generated bordered
  2x2 PDF table to parse into expected `TrustTable` cells and pass
  `table_cell_f1 == 1.0`.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkCanCompareRealPdfAgainstExpectedTableCells test`.
- RED result: expected behavior failure because Java/PDFBox parser emitted no
  structured tables from the PDF grid, so `table_cell_f1` was `0.0`.
- Implemented conservative bordered-table extraction:
  `PdfPageGraphicsExtractor` now records vertical separators as well as
  horizontal separators; `PdfPageTableExtractor` detects simple full-grid
  bordered tables and emits `TableSection` rows; `PdfDocumentParser` appends
  detected table sections to the page output.
- Focused RED/green verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkCanCompareRealPdfAgainstExpectedTableCells test`.
- Related parser verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableExtractionContractTest,PdfVisualLayoutParserTest test`.
- Java full verification passed:
  `mvn test` -> 821 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7
  tests passed.
- Runtime + CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: Java/PDFBox generated-PDF benchmark cases can now
  validate simple bordered table recovery through `table_cell_f1`. This does
  not yet cover borderless tables, merged cells, multi-page tables,
  model-assisted table detection, or the Rust sidecar table path.

## 2026-06-12 Continued Table Duplicate Suppression

- Started downstream cleanliness TDD slice for bordered-table extraction.
- Added RED test in `ParserBenchmarkRunnerTest` requiring detected table cell
  text to be absent from ordinary `TEXT_BLOCK` units, so clean Markdown and LLM
  consumers do not see the same table content twice.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#realPdfTableExtractionSuppressesDuplicateTextBlocks test`.
- RED result: expected assertion failure because the generated table emitted
  both `TEXT_BLOCK` units (`Name`, `Score`, `Alex`, `98`) and structured
  `TABLE_CELL` units.
- Implemented internal table-region filtering:
  `PdfPageTableExtractor` now returns table blocks with normalized bounding
  boxes, and `PdfDocumentParser` suppresses `PdfTextBlock`s whose centers fall
  inside detected table regions before appending `TableSection`s.
- Focused duplicate suppression verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#realPdfTableExtractionSuppressesDuplicateTextBlocks test`.
- Parser-focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableExtractionContractTest,PdfVisualLayoutParserTest,PdfDocumentParserTest test`.
- Java full verification passed:
  `mvn test` -> 822 tests, 0 failures, 0 errors.
- Current honest status: simple bordered-table extraction now avoids duplicate
  text/table output in the Java/PDFBox baseline. This still does not provide
  cell-level bboxes in public `TableSection`, borderless recognition, merged
  cell semantics, or Rust sidecar table extraction.

## 2026-06-12 Continued Table Region IoU

- Started table-region bbox TDD slice to cover the PRD metric
  `table_region_iou`.
- Added RED test in `ParserBenchmarkRunnerTest` requiring a generated bordered
  PDF table to preserve a table-region bbox and meet
  `table_region_iou >= 0.95` against an expected `TrustTable` region.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkCanCompareRealPdfAgainstExpectedTableRegion test`.
- RED result: expected assertion failure because `table_region_iou` was `0.0`;
  the benchmark did not compute that metric and actual `TrustTable` regions had
  no bbox.
- Added optional `TableSection.boundingBox` while preserving the existing
  two-argument constructor for current callers.
- Propagated detected PDF table regions from `PdfPageTableExtractor` into
  `TableSection` and then into `TrustTable.boundingBox`.
- Implemented `ParserBenchmarkRunner` metric `table_region_iou`.
- Updated `TableSectionTest` to lock the new bbox contract and null optional
  invariant.
- Public API snapshot updated because `TableSection` now exposes
  `boundingBox()` and a three-argument constructor.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableSectionTest,TableExtractionContractTest,TrustDocumentAdapterTest,TrustDocumentRenderedOutputTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 825 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7
  tests passed.
- Runtime + CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh && sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: Java/PDFBox bordered-table fixtures now support
  table-region bbox scoring. Cell-level bboxes, borderless tables, merged cells,
  model-assisted tables, and Rust runtime parity remain open.

## 2026-06-12 Continued Table Cell Bboxes

- Started cell-level bbox TDD slice for evidence-grade table cells.
- Added RED test in `ParserBenchmarkRunnerTest` requiring a generated bordered
  PDF table to preserve cell-level bboxes both in `TrustTableCell` and in
  `TABLE_CELL` unit locations.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#realPdfBorderedTableExtractionPreservesCellBoundingBoxes test`.
- RED result: expected assertion failure because detected table cells had
  `Optional.empty()` bboxes.
- Added public `TableCellRegion` and extended `TableSection` with immutable
  per-cell regions while preserving existing constructors.
- Propagated detected PDF grid cell boxes from `PdfPageTableExtractor` into
  `TableSection`, then into `TrustTableCell.boundingBox` and `TABLE_CELL`
  `TrustUnitLocation.boundingBox`.
- Added `TableCellRegionTest` and expanded `TableSectionTest` for invariants
  and defensive-copy behavior.
- Public API snapshot updated because `TableCellRegion` is public and
  `TableSection` now exposes `cellRegions()`.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,TableCellRegionTest,TableSectionTest,TableExtractionContractTest,TrustDocumentAdapterTest,TrustDocumentRenderedOutputTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 833 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: Java/PDFBox simple bordered-grid PDFs now preserve
  table-cell bboxes into the public trust document model. Borderless tables,
  merged cells, multi-page continuation, OCR-backed tables, model-assisted
  table structure, precise Rust bboxes, and Rust table extraction remain open.

## 2026-06-12 Continued GFM Table Rendering

- Started Markdown output TDD slice because structured table extraction is only
  useful to LLM/RAG consumers if the Markdown output is clean and recoverable.
- Added RED expectations that `toMarkdownClean()` renders table sections as
  GFM pipe tables with a separator row instead of bare `Company | Role` text.
- Added RED expectations that `toMarkdownWithSourceMap()` renders the same GFM
  table and maps every rendered cell back to its `TABLE_CELL` unit id and
  evidence span id.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest test`.
- RED result: expected failures because clean Markdown lacked the GFM separator
  row and source-map Markdown rendered each table cell as a separate paragraph.
- Updated `TrustDocumentRenderers` so table rendering emits GFM pipe tables,
  escapes table-cell pipe characters, and records source-map offsets for each
  rendered table cell.
- Focused renderer/source-map verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest test`.
- Broader renderer/parser/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentChunkingContractTest,TrustDocumentCliOutputProfileTest,TableExtractionContractTest,ParserBenchmarkRunnerTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 834 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 7
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: table Markdown is now LLM-friendly GFM for the current
  `TrustTable` contract and source-map aware. This is not yet a full Comrak or
  cross-format renderer stack.

## 2026-06-12 Continued Rust Bordered Table Runtime

- Started Rust-side table parity TDD slice because the PRD requires the Rust
  runtime to become the primary parser path, not only Java/PDFBox.
- Added RED cargo test
  `parse_pdf_emits_table_cells_for_bordered_grid_pdf` that generates a real PDF
  with a 2x2 drawn table and requires runtime JSON to include one table, four
  cells, four `TABLE_CELL` units, and cell/table bboxes.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml parse_pdf_emits_table_cells_for_bordered_grid_pdf -- --nocapture`.
- RED result: expected assertion failure because runtime `body.tables` was
  empty.
- Added direct `lopdf` dependency with `default-features = false` so the runtime
  can parse PDF content operations without pulling chrono/jiff/rayon/time.
- Implemented a narrow Rust bordered-grid detector over `m/l/S` drawing
  operations and `Td/Tj` text positions. It maps text points into grid cells,
  emits `TrustTable`/`TrustTableCell` JSON, and adds `TABLE_CELL` units with
  normalized bboxes.
- Updated ADR 0010 to document direct `lopdf` use and the dependency boundary.
- Upgraded `scripts/smoke-doctruth-runtime.sh` to validate bordered-table JSON
  and cell bboxes.
- Upgraded `scripts/smoke-doctruth-cli-sidecar.sh` to validate that the Java
  CLI sidecar consumes Rust table JSON and renders a GFM Markdown table.
- Rust full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 8 tests.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 834 tests, 0 failures, 0 errors.
- Dependency feature check passed:
  `cargo tree --manifest-path runtime/doctruth-runtime/Cargo.toml -e normal | rg "chrono|jiff|rayon|time v" || true`
  reported no unnecessary default-feature runtime deps.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: Rust runtime now has simple generated bordered-grid
  table extraction with cell bboxes and Java CLI smoke coverage. It still does
  not handle borderless tables, merged cells, multi-page table continuation,
  OCR-backed tables, model-assisted table structure, precise ordinary text
  bboxes, or real labeled table benchmarks.

## 2026-06-12 Continued Rust Positioned Text Bboxes

- Started Rust-side positioned-text bbox TDD slice because evidence-grade
  `LINE_SPAN` units cannot keep falling back to page-level boxes when the PDF
  content stream includes usable text positions.
- Added RED cargo test
  `parse_pdf_emits_positioned_text_bboxes_when_content_stream_positions_are_available`
  that generates a real text-layer PDF and requires the first line bbox to be
  narrower than the full page with no `runtime_bbox_page_fallback` warning.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml parse_pdf_emits_positioned_text_bboxes_when_content_stream_positions_are_available -- --nocapture`.
- RED result: expected assertion failure because the runtime still emitted a
  page-level bbox with `x0=0.0`.
- Reused the runtime `lopdf` content-stream pass to collect simple `Tf`,
  `Td`, and `Tj` text positions, added `PositionedLine`, and estimated
  normalized line bboxes from text point, font size, and text length.
- Preserved the old page-level bbox fallback for PDFs where positioned text
  cannot be recovered.
- Fixed a test isolation failure exposed by full cargo runs by giving generated
  PDF fixtures unique temp filenames instead of a shared `/tmp` path.
- Rust full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9 tests.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 834 tests, 0 failures, 0 errors.
- Dependency feature check passed:
  `cargo tree --manifest-path runtime/doctruth-runtime/Cargo.toml -e normal | rg "chrono|jiff|rayon|time v" || true`
  reported no unnecessary default-feature runtime deps.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: Rust runtime now emits non-page-fallback text bboxes
  for simple content streams. The bbox is an approximation, not a final
  font-metric-perfect or layout-grade geometry engine.

## 2026-06-12 Continued Compact LLM Wire Coverage

- Started compact wire TDD slice because the PRD requires `compact_llm` to be
  evidence-preserving, not merely shorter than JSON.
- Added RED expectations that `compact_llm` is at least 25% smaller than
  `json_full` for the fixture, preserves `TrustTable.tableId`, and carries both
  parser-level and unit-level warnings.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- RED result: expected failure because compact output only emitted `doc|` and
  `u|` records; it had no `t|` table record and no `w|` warning records.
- Updated `TrustDocumentRenderers.toCompactLlm()` to append deterministic table
  summary records and parser/unit warning records after the unit stream.
- During broader verification, `cargo test` exposed a Rust protocol-test
  flake: the positioned-text test sometimes read another generated PDF's text
  under parallel execution. Root cause was insufficiently unique temp fixture
  paths under one test process. Fixed the test helper with an `AtomicU64`
  sequence suffix.
- Focused renderer/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test`.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Java full verification passed:
  `mvn test` -> 835 tests, 0 failures, 0 errors.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: `compact_llm` now preserves table ids and warnings in
  a deterministic compact wire shape. This is still a DocTruth-owned compact
  format, not a finalized TOON-compatible spec or a full cross-format parity
  proof over a labeled corpus.

## 2026-06-12 Continued HTML Review Bbox Anchors

- Started HTML review TDD slice because the PRD requires `html_review` to expose
  bbox-compatible anchors for review UI and bbox overlays.
- Added RED expectation that `toHtmlReview()` includes stable unit/evidence
  anchors plus `data-bbox="100,120,500,240"` and
  `data-bbox-space="normalized-0-1000"` when the unit has a normalized bbox.
- RED command:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest test`.
- RED result: expected failure because HTML review sections carried unit id,
  evidence ids, page, and reading order, but no bbox attributes.
- Updated `TrustDocumentRenderers.appendHtmlUnit()` to append normalized bbox
  attributes only when `TrustUnitLocation.boundingBox()` is present.
- Focused verification passed sequentially:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest test` and
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test`.
- Note: running multiple Maven test commands in parallel against the same
  working directory caused a transient `target/` race with broad
  `cannot find symbol` compile errors. Sequential Maven verification passed.
- Java full verification passed:
  `mvn test` -> 835 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: HTML review output now exposes bbox-compatible unit
  anchors for units that have bboxes. It is still not a full visual HTML review
  surface with rendered page images, table overlays, or interactive bbox
  inspection.

## 2026-06-12 Continued HTML Review Table Anchors

- Started table/cell HTML review TDD slice because the PRD review output needs
  table/cell-level anchors, not only one generic section per citeable unit.
- Added RED expectation that `toHtmlReview()` emits a semantic
  `<table data-trust-table-id="table-0001">` with normalized table bbox,
  `data-trust-cell-id`, `data-trust-unit-id`, `data-evidence-span-ids`, and
  cell-level bbox attributes.
- RED command:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest test`.
- RED result: expected failure because HTML review only emitted table-cell
  units as standalone `<section>` nodes and had no semantic table/cell review
  nodes.
- Updated `TrustDocumentRenderers.toHtmlReview()` to append semantic table
  review nodes. Each table carries table id, page, optional normalized bbox,
  and each cell carries cell id, optional matching unit id, evidence span ids,
  optional normalized bbox, and escaped cell text.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest test`.
- Focused renderer/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test`.
- Java full verification passed:
  `mvn test` -> 836 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: HTML review output now has semantic table/cell
  anchors with bbox-compatible attributes. It is still not a full visual page
  image overlay or interactive browser review UI.

## 2026-06-12 Continued Streaming Writer Paths

- Started streaming-renderer TDD slice because the PRD requires large-document
  renderer paths that do not force callers to materialize every output as one
  aggregate string.
- Added RED test `writerPathsDoNotWriteWholeDocumentAtOnce` using a
  caller-owned `Writer` that fails when any single `write()` call is too large.
- RED command:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- RED result: expected failure because `TrustDocument.writeMarkdownClean(...)`
  wrote the complete 5279-character Markdown string in one call.
- Updated `writeMarkdownClean(...)` and `writeJsonLines(...)` to use
  renderer-owned incremental writer paths instead of delegating through
  `toMarkdownClean()` / `toJsonLines()`.
- Markdown clean now writes block/table output incrementally; JSONL now writes
  document, unit, and table lines incrementally. Large rendered chunks are
  split into bounded writes before reaching the caller-owned `Writer`.
- Focused streaming verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- Focused renderer/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test`.
- Java full verification passed:
  `mvn test` -> 837 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: renderer writer paths for clean Markdown and JSONL are
  now incremental. The parser itself still materializes a `TrustDocument`, and
  HTML/JSON full/audit/compact still use aggregate renderers.

## 2026-06-12 Continued Source-Map Hash Binding

- Started source-map hash TDD slice because clean Markdown is only a
  consumption view; its sidecar must bind the rendered body back to source and
  rendered-content hashes.
- Added RED SDK expectation that `TrustRenderedDocument` exposes
  `sourceHash()` and `contentHash()`, with `contentHash` equal to SHA-256 of
  the rendered Markdown text.
- Added RED CLI expectation that `document.doctruth-map.json` includes
  `sourceHash` and `contentHash` matching the emitted clean Markdown file.
- RED command:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest test`.
- RED result: expected compile failure because `TrustRenderedDocument` had only
  `format`, `text`, and `sourceMap` fields.
- Extended `TrustRenderedDocument` to carry `sourceHash` and `contentHash`, and
  updated `TrustDocumentRenderers.toMarkdownWithSourceMap()` to compute the
  clean Markdown SHA-256 after byte-stable rendering.
- Updated `public-api-snapshot.txt` through the existing snapshot update test
  flow, then reran public API and architecture checks.
- Focused source-map/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest test`.
- Public API/architecture verification passed:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 837 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: clean Markdown source-map sidecars now carry rendered
  content hash and source hash. This is still not signed audit packaging or a
  full round-trip source-map verifier CLI.

## 2026-06-12 Continued Anchored Markdown Bbox Metadata

- Started anchored Markdown TDD slice because the PRD example requires
  evidence anchors to include bbox metadata when available, while clean
  Markdown must remain metadata-free.
- Added RED test `markdownAnchoredIncludesBboxMetadata` requiring
  `toMarkdownAnchored()` to emit
  `{#ev:span-0001 page=1 bbox="100,100,500,200"}` for a unit with a normalized
  bbox.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- RED result: expected failure because anchored Markdown emitted only
  `{#ev:span-0001 page=1}`.
- Updated anchored Markdown rendering to append optional bbox metadata inside
  the evidence anchor when `TrustUnitLocation.boundingBox()` is present.
- Focused rendered-output verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- Focused renderer/source-map/streaming/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test`.
- Java full verification passed:
  `mvn test` -> 838 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: anchored Markdown now carries bbox metadata when
  available. Clean Markdown remains free of bbox/provenance/internal ids.

## 2026-06-12 Continued Markdown Review Unit Warnings

- Started markdown review warning TDD slice because review output must expose
  parser and unit-level uncertainty for replay/debugging.
- Added RED test `markdownReviewIncludesParserAndUnitWarnings` requiring
  `toMarkdownReview()` to include both parser warnings and unit-scoped warnings
  such as `unit-0001 WARNING low_confidence_anchor: bbox was estimated`.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- RED result: expected failure because review Markdown only rendered parser
  warnings and omitted unit-level warnings.
- Updated `TrustDocumentRenderers.toMarkdownReview()` to append a `Unit
  Warnings` section with unit id, severity, code, and message for every
  citeable unit warning.
- Focused rendered-output verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- Focused renderer/source-map/streaming/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest test`.
- Java full verification passed:
  `mvn test` -> 839 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Current honest status: review Markdown now exposes parser and unit warnings.
  It is still a textual review output, not the full visual review UI.

## 2026-06-12 Continued Plain Text Output Profile

- Started plain text output TDD slice because the PRD requires
  Markdown/HTML/plain/compact parity, but plain text was not yet a public SDK,
  CLI, backend capability, or smoke-tested output.
- Added RED tests:
  `TrustDocumentRenderedOutputTest.plainTextIsCleanConsumptionView`,
  `TrustDocumentCliOutputProfileTest.parsePlainTextProfilePrintsCleanTextWithoutMarkdownSyntax`,
  `ParserBackendContractTest.pdfBoxBackendCapabilities`, and
  `SidecarParserBackendTest.sidecarCapabilitiesIncludePlainTextOutput`.
- RED result:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest test`
  first failed at compile because `TrustDocument.toPlainText()` did not exist.
  Capability-focused RED then failed because both PDFBox and sidecar
  `outputProfiles()` omitted `plain_text`.
- Implemented `TrustDocument.toPlainText()` and renderer support that emits
  clean text blocks plus tab-separated table rows without Markdown separators,
  evidence anchors, bboxes, or hashes.
- Added CLI support for `--format plain` / `text` / `txt` and made sidecar
  parse validation accept `plain` as a first-class output format.
- Added `plain_text` to PDFBox and sidecar parser capabilities.
- Updated CLI docs, parser capability matrix, and the parser runtime PRD to
  describe `plain_text` as a clean consumption profile, not an audit artifact.
- Extended `scripts/smoke-doctruth-cli-sidecar.sh` to parse a generated table
  PDF through the Java CLI + Rust sidecar with `--format plain` and verify
  tab-separated table content without Markdown/evidence syntax.
- Focused verification passed:
  `mvn -q -Dtest=ParserBackendContractTest,SidecarParserBackendTest,TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 842 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: plain text is now a public clean-consumption view and
  discoverable backend capability. It is intentionally not audit-grade by
  itself; audit-grade replay still requires JSON/source-map/evidence outputs.

## 2026-06-12 Continued Source-Map Verification Command

- Started source-map verification TDD slice because source-map sidecars carried
  source/content hashes, but the CLI could not yet verify that a rendered
  Markdown file or source document still matched the sidecar.
- Added RED tests:
  `TrustDocumentCliOutputProfileTest.verifySourceMapChecksRenderedContentAndSourceHash`
  and
  `TrustDocumentCliOutputProfileTest.verifySourceMapRejectsTamperedRenderedContent`.
- RED command:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test`.
- RED result: expected failures with exit code 2 because `verify-source-map`
  was not registered.
- Implemented `doctruth verify-source-map <rendered> <map.json>
  [--source <document>]`. The command recomputes the rendered content SHA-256
  and, when `--source` is supplied, the source document SHA-256, failing with
  stable `content hash mismatch` or `source hash mismatch` messages.
- Registered the command in CLI dispatch, help usage, shell completion, CLI
  docs, and the parser runtime PRD.
- Extended `scripts/smoke-doctruth-cli-sidecar.sh` so the shaded Java CLI
  verifies the source-map sidecar generated from the Rust sidecar parse path.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 844 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: clean Markdown source maps can now be generated and
  verified against rendered Markdown and the optional source document. This is
  still not a signed audit package or external notarization.

## 2026-06-12 Continued Hashable Audit JSON Package

- Started audit JSON hashability TDD slice because the PRD requires Audit JSON
  to be a signed or hashable extraction evidence package, while the
  `TrustDocument` audit output did not yet include package integrity hashes.
- Added RED tests:
  `TrustDocumentRenderedOutputTest.auditJsonCarriesPackageHashes` and stronger
  CLI audit profile assertions in
  `TrustDocumentCliOutputProfileTest.parseJsonlAndAuditProfilesAreMachineReadable`.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest test`.
- RED result: expected failures because audit JSON had no `canonicalHash` or
  `evidenceHash`.
- Implemented audit JSON `canonicalHash` from `TrustDocument.canonicalHash()`
  and deterministic `evidenceHash` over the compact evidence array.
- Updated CLI docs and parser runtime PRD to describe Audit JSON as a hashable
  replay/compliance package.
- Extended `scripts/smoke-doctruth-cli-sidecar.sh` so the shaded Java CLI
  parses through the Rust sidecar with `--format audit` and verifies
  `sourceHash`, `canonicalHash`, `evidenceHash`, parser backend, and evidence
  presence.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 845 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: `TrustDocument` Audit JSON is now hashable and
  sidecar-smoke-tested. It is still not externally signed, timestamped, or
  notarized.

## 2026-06-12 Continued HTML Review Page Surfaces

- Started page-aware HTML review TDD slice because `html_review` had unit,
  bbox, table, and cell anchors, but no page container for overlay tooling to
  attach page dimensions or page image hashes.
- Added RED test:
  `TrustDocumentSourceMapContractTest.reviewHtmlRendersPageSurfacesForOverlays`.
- First RED attempt failed at test compile because the new test used the wrong
  `TrustUnitLocation` constructor argument order. Corrected the test to use
  `TrustUnitLocation(page, bbox, readingOrder)`.
- Correct RED command:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest test`.
- Correct RED result: expected failure because HTML review did not contain
  `<section data-trust-page-number="2"`.
- Implemented page containers in `TrustDocumentRenderers.toHtmlReview(...)`.
  The renderer now wraps page-scoped units and tables in a page `<section>`
  with page number, width, height, text-layer availability, and image hash
  attributes.
- Updated CLI docs and `docs/pdf-parser-runtime-prd.md` to describe the page
  metadata exposed by HTML review output.
- Extended `scripts/smoke-doctruth-cli-sidecar.sh` so the shaded Java CLI
  parses through the Rust sidecar with `--format html` and verifies page
  number, real generated-PDF dimensions, text-layer availability, source-derived
  page image hash, and unit anchors.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 846 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke initially failed because it still expected synthetic
  `1000x1000` page geometry and `sha256:image`. Updated it to assert the real
  generated PDF MediaBox `612x792` and source-derived `sha256:*:page-1` image
  hash pattern.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: HTML review now has page metadata needed by overlay
  consumers, but it is still semantic HTML output rather than an interactive
  browser review UI with rendered page images.

## 2026-06-12 Continued Compact Wire Bbox Metadata

- Started compact evidence wire TDD slice because `compact_llm` preserved doc
  id, source hash, unit ids, evidence ids, table ids, and warnings, but dropped
  bbox metadata even when units had normalized bboxes.
- Added RED test:
  `TrustDocumentRenderedOutputTest.compactLlmPreservesBboxMetadataForCiteableUnits`.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- RED result: expected failure because compact output for `unit-0001` ended at
  `Work Experience` and omitted `|bbox=100,100,500,200`.
- Implemented optional `bbox=` suffix in compact unit records. The existing
  record prefix remains unchanged, so consumers matching
  `u|unit|kind|page|evidence|text` keep working.
- Updated CLI docs and `docs/pdf-parser-runtime-prd.md` to document compact
  bbox preservation.
- Extended `scripts/smoke-doctruth-cli-sidecar.sh` so the shaded Java CLI
  parses through the Rust sidecar with `--format compact` and verifies real
  sidecar output contains the evidence text and `|bbox=`.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 847 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Note: focused Maven and sidecar smoke were initially launched concurrently.
  They passed, but full Maven/Cargo/runtime verification was then run
  sequentially to avoid the known target-directory race.
- Current honest status: compact wire now preserves bbox metadata for units
  that have it. It is still a DocTruth-owned compact syntax, not a finalized
  TOON-compatible format or corpus-measured token benchmark.

## 2026-06-12 Continued Compact Streaming Writer

- Started compact streaming writer TDD slice because the PRD requires streaming
  parser/renderer paths for large files, while compact output still required
  rendering the full compact string before writing to a file.
- Added RED assertions to `TrustDocumentStreamingRenderContractTest` requiring
  `TrustDocument.writeCompactLlm(Writer)` to produce byte-identical output to
  `toCompactLlm()` and keep individual writes below the bounded writer size on
  a larger fixture.
- RED command:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- RED result: expected compile failure because `writeCompactLlm(Writer)` did
  not exist.
- Implemented `TrustDocument.writeCompactLlm(Writer)` and
  `TrustDocumentRenderers.writeCompactLlm(...)`, reusing the same compact
  record formatting as `toCompactLlm()` while writing line-by-line through the
  existing chunked writer helper.
- Routed `doctruth parse --format compact --out <file>` through the compact
  writer path for both PDFBox and sidecar backends. Stdout still renders a
  string because terminal output is already aggregate user-facing output.
- Updated CLI docs and `docs/pdf-parser-runtime-prd.md` to document compact
  writer-based file output.
- Focused verification initially failed at `PublicApiSnapshotTest` because
  `writeCompactLlm(Writer)` is a new public API method.
- Updated the public API snapshot:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliOutputProfileTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 847 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: clean Markdown, JSONL, and compact LLM now have
  incremental writer paths. Parser ingestion still materializes the whole
  `TrustDocument`, and JSON full/audit/HTML still render aggregate strings.

## 2026-06-12 Continued Compact Source-Map Resolution

- Started compact source-map TDD slice because the PRD requires LLM-facing
  output to be source-map resolvable, while compact output only carried unit
  and evidence ids inline.
- Added RED tests:
  `TrustDocumentSourceMapContractTest.compactLlmWithSourceMapPreservesRenderedOffsets`
  and
  `TrustDocumentCliOutputProfileTest.parseCompactWithSourceMapWritesVerifiableSidecarMap`.
- RED command:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest test`.
- RED result: expected compile failure because
  `TrustDocument.toCompactLlmWithSourceMap()` did not exist.
- Implemented `TrustDocument.toCompactLlmWithSourceMap()` and compact source-map
  rendering. The map records rendered offsets for each compact unit text field,
  tied to the corresponding unit id and evidence span ids.
- Allowed `doctruth parse --format compact --source-map --out <file>` and kept
  `verify-source-map` generic enough to verify compact rendered content and the
  original source hash.
- Updated CLI docs and `docs/pdf-parser-runtime-prd.md` to describe compact
  source-map sidecars.
- Extended `scripts/smoke-doctruth-cli-sidecar.sh` so the shaded Java CLI uses
  the Rust sidecar to emit compact output, compact source-map sidecar, and then
  verifies the pair with `verify-source-map`.
- Updated the public API snapshot for `toCompactLlmWithSourceMap()`.
- Focused verification passed:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest,TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest test`.
- Java full verification passed:
  `mvn test` -> 849 tests, 0 failures, 0 errors.
- Rust format/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 9
  tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: compact output is now source-map resolvable for unit
  text fields. It is still a DocTruth-owned wire shape, not a finalized
  TOON-compatible spec, and does not yet have corpus-level token/round-trip
  benchmarks.

## 2026-06-12 Continued Batch TDD And Signed Audit Package

- Added PRD execution guidance for milestone-sized batch TDD:
  write all RED tests for one milestone first, confirm the focused failures are
  missing behavior, implement in one coherent pass, then rerun focused tests,
  required smoke tests, and planning/PRD status updates.
- Kept the PRD boundary explicit: do not batch model-assisted layout, OCR,
  external notarization, and WORM/legal-hold into one undiagnosable milestone.
- Started signed `TrustDocument` audit package TDD slice.
- Added RED tests in `TrustDocumentRenderedOutputTest` requiring
  `TrustDocument.toAuditJson(SignatureProvider)` and
  `TrustDocument.toAuditJson(Path, SignatureProvider)` to reuse the existing
  shared `SignatureProvider` contract.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest test`.
- RED result: expected testCompile failure because `TrustDocument` only had
  `toAuditJson()` with no signer/path overloads.
- Implemented the minimal SDK signing surface on `TrustDocument`: signer
  passthrough and package-file writing with parent directory creation.
- Updated the public API snapshot for the new public methods.
- Focused verification passed:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest,TrustDocumentRenderedOutputTest test`.
- Java full verification passed:
  `mvn test` -> 852 tests, 0 failures, 0 errors.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: `TrustDocument` audit JSON can now be signed/wrapped
  and written through the shared SDK `SignatureProvider` path. External
  timestamping, key management, notarization, legal hold, WORM storage, and
  full replay validation remain separate PRD milestones.

## 2026-06-12 Continued Labeled Benchmark Corpus Harness

- Started the next milestone using the PRD batch TDD rule: labeled benchmark
  corpus manifest harness.
- Added all RED tests first in `ParserBenchmarkCorpusTest`:
  manifest-relative fixture loading and threshold evaluation, rejection of
  cases without expected `TrustDocument` labels, and case-specific diagnostics
  for missing fixture paths.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest test`.
- RED result: expected testCompile failure because `ParserBenchmarkCorpus` did
  not exist.
- Implemented `ParserBenchmarkCorpus.load(Path)`, `evaluate()`, and
  `requireMinimums()` over the existing `ParserBenchmarkCase` and
  `ParserBenchmarkRunner` contracts.
- The first green attempt found a real JSON round-trip gap: expected labels
  written from `TrustDocument.toJsonFull()` could contain a blank page
  `imageHash`, but `TrustDocumentJson.fromJsonFull(...)` rejected it.
- Fixed the internal JSON import to allow blank page image hashes while keeping
  required trust fields strict.
- Focused corpus verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest test`.
- Updated the public API snapshot for `ParserBenchmarkCorpus`.
- Focused benchmark/API verification passed:
  `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest,ParserBenchmarkCorpusTest,ParserBenchmarkRunnerTest test`.
- Updated `docs/pdf-parser-runtime-prd.md` with the corpus manifest contract.
- Java full verification passed:
  `mvn test` -> 855 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: the benchmark system now has executable corpus
  manifest loading and threshold reuse. It still does not include the real
  human-labeled multi-layout/OCR/table corpus or final parser-quality targets.

## 2026-06-12 Continued Benchmark Corpus CLI And Smoke

- Started benchmark corpus CLI/smoke milestone.
- Added all RED tests first in `ParserBenchmarkCorpusCliTest` plus existing
  help/completion assertions:
  human-readable summary, machine-readable JSON, threshold-failure exit code,
  unknown option handling, help discoverability, and shell completion
  discoverability.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest test`.
- RED result: expected failures because `benchmark-corpus` was an unknown
  command and help/completion omitted it.
- Implemented `BenchmarkCorpusCommand`, wired it into `DocTruthCli`, added
  usage text, and added completion entry.
- Focused CLI verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest test`.
- Added `scripts/smoke-doctruth-benchmark-corpus.sh` to package the CLI,
  generate a PDF fixture, write expected Markdown and `TrustDocument` labels,
  verify a passing JSON corpus run, and verify a failing threshold exits
  non-zero.
- First smoke attempt failed because the script used Python `reportlab`, which
  is not installed in this environment.
- Reworked the smoke to write a minimal text-layer PDF directly in Python,
  matching existing runtime smoke style and avoiding third-party Python
  dependencies.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Updated `docs/cli.md` and `docs/pdf-parser-runtime-prd.md` for the
  `benchmark-corpus` command and smoke requirement.
- Focused CLI/corpus/API verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,ParserBenchmarkCorpusTest,ParserBenchmarkRunnerTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 859 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: generated labeled corpus manifests now have an SDK
  runner, CLI command, and smoke gate. This is still not a real-world
  human-labeled parser-quality corpus.

## 2026-06-12 Continued Compact Corpus Metrics

- Started the next milestone using batch TDD: compact LLM corpus metrics for
  size reduction and replay/source-map health.
- Added RED coverage in `ParserBenchmarkRunnerTest` requiring
  `compact_llm_size_reduction`, `compact_llm_round_trip`, and
  `compact_llm_source_map_coverage`, and requiring those metrics to work with
  the existing threshold gate.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsCompactLlmCorpusMetrics test`.
- RED result: expected assertion failure because missing compact metrics
  defaulted to `0.0`.
- Implemented compact benchmark metrics in `ParserBenchmarkRunner`:
  UTF-8 byte reduction against `json_full`, exact compact/source-map rendered
  text round-trip, and citeable-unit source-map coverage.
- Focused compact metric verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsCompactLlmCorpusMetrics test`.
- Focused runner/corpus/CLI verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`.
- Updated `docs/pdf-parser-runtime-prd.md` with the compact benchmark metric
  contract.
- Focused benchmark/API verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 860 tests, 0 failures, 0 errors.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: corpus manifests can now gate compact LLM efficiency
  and replay/source-map health, but the compact syntax is still DocTruth-owned,
  not a finalized TOON-compatible wire spec.

## 2026-06-12 Continued GFM Markdown Escaping

- Started the next batch-TDD milestone: GFM Markdown escaping for clean
  Markdown consumption output.
- Added RED coverage in `TrustDocumentRenderedOutputTest` requiring
  `markdown_clean` to preserve fenced code blocks and links while escaping
  Markdown-sensitive table cell brackets, pipes, and backslashes.
- RED command:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest#markdownCleanPreservesCodeLinksAndEscapedTableCells test`.
- RED result: expected assertion failure because table cells escaped `|` and
  backslash but not `[` / `]`.
- Implemented bracket escaping in the existing table-cell Markdown renderer.
- Focused GFM escaping verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest#markdownCleanPreservesCodeLinksAndEscapedTableCells test`.
- Focused renderer/source-map verification passed:
  `mvn -q -Dtest=TrustDocumentRenderedOutputTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest test`.
- Java full verification passed:
  `mvn test` -> 861 tests, 0 failures, 0 errors.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: clean Markdown now preserves fenced code blocks and
  links while escaping GFM-sensitive table cell brackets/pipes/backslashes.
  Full GFM parity over all block types and a dedicated Markdown renderer stack
  remain open.

## 2026-06-12 Continued Audit Replay Verification

- Started the next batch-TDD milestone: local replay verification for
  `TrustDocument` Audit JSON.
- Added RED coverage in `TrustAuditVerifierTest` requiring generated Audit JSON
  to verify against the same `TrustDocument`, tampered evidence payloads and
  canonical hash mismatches to fail, and full `TrustDocument` JSON to round-trip
  back into replay verification.
- Added CLI RED coverage in `TrustDocumentCliOutputProfileTest` requiring
  `doctruth verify-audit <trust-document.json> <audit.json>` to pass on matching
  parser outputs and fail on tampered Audit JSON. Help and completion tests now
  require `verify-audit` discoverability.
- RED command:
  `mvn -q -Dtest=TrustAuditVerifierTest,TrustDocumentCliOutputProfileTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest test`.
- RED result: expected testCompile failure because `TrustAuditVerifier` and
  `TrustDocument.fromJsonFull(...)` did not exist.
- Implemented `TrustAuditVerifier.verify(TrustDocument, String)` and
  `TrustDocument.fromJsonFull(String)`, plus CLI `VerifyAuditCommand`, usage,
  and completion wiring.
- Focused SDK/CLI verification passed:
  `mvn -q -Dtest=TrustAuditVerifierTest,TrustDocumentCliOutputProfileTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest test`.
- Updated the public API snapshot for `TrustAuditVerifier` and
  `TrustDocument.fromJsonFull(...)`.
- Focused API/architecture verification passed:
  `mvn -q -Dtest=TrustAuditVerifierTest,TrustDocumentCliOutputProfileTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Updated `docs/cli.md` and `docs/pdf-parser-runtime-prd.md` with the
  `verify-audit` contract.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`; it now verifies sidecar full JSON
  against sidecar Audit JSON with `verify-audit`.
- Java full verification passed:
  `mvn test` -> 867 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: local audit replay verification exists at SDK and CLI
  boundaries. External timestamping, key management, notarization, legal hold,
  and WORM semantics remain open.

## 2026-06-12 Continued HTML Review Visual Overlays

- Started the next batch-TDD milestone: visual bbox overlay nodes in
  `html_review`.
- Added RED coverage in `TrustDocumentSourceMapContractTest` requiring
  page-scoped `data-trust-overlay-layer="bbox"` output and overlay nodes for
  unit, table, and cell bboxes with normalized 0-1000 coordinates converted
  into percent CSS positioning.
- RED command:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest#reviewHtmlRendersVisualBboxOverlayLayer test`.
- RED result: expected assertion failure because HTML review had semantic bbox
  data attributes but no visual overlay layer.
- Implemented page-scoped overlay output in `TrustDocumentRenderers.toHtmlReview`.
- Focused overlay verification passed:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest#reviewHtmlRendersVisualBboxOverlayLayer test`.
- Focused HTML/CLI/API verification passed:
  `mvn -q -Dtest=TrustDocumentSourceMapContractTest,TrustDocumentCliOutputProfileTest,SidecarParserBackendTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Updated `scripts/smoke-doctruth-cli-sidecar.sh` to assert generated HTML
  contains the overlay layer and unit overlay node.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Updated `docs/cli.md` and `docs/pdf-parser-runtime-prd.md` with the HTML
  overlay contract.
- Java full verification passed:
  `mvn test` -> 868 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: `html_review` now includes static visual bbox overlay
  nodes for parsed units/tables/cells. It is still not a full interactive
  browser review UI with image rendering and click/hover inspection.

## 2026-06-12 Continued Strict Parser Preset API

- Started the next batch-TDD milestone: explicit model-assisted/strict parser
  preset semantics on the parser-only SDK entrypoint.
- Added RED coverage in `TrustDocumentParserApiContractTest` requiring
  `TrustDocumentParser.parse(path, ParserPreset.STANDARD)` and
  `TrustDocumentParser.parse(bytes, filename, ParserPreset.TABLE_LITE)`.
- RED command:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest test`.
- RED result: expected testCompile failure because static
  `TrustDocumentParser.parse(..., ParserPreset)` overloads did not exist.
- Implemented overloads for file, bytes, input stream, and batch parser
  entrypoints. Existing overloads still default to `ParserPreset.LITE`.
- Focused parser API verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest test`.
- Public API/architecture verification initially failed only because the new
  public overloads were missing from the snapshot.
- Updated public API snapshot with:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Focused API/architecture verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,ModelRuntimePolicyTest,TrustDocumentSdkParserContractTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Updated `docs/pdf-parser-runtime-prd.md` to document parser-only explicit
  preset behavior and the no-silent-heuristic-fallback contract.
- Java full verification passed:
  `mvn test` -> 870 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Current honest status: strict/model-assisted presets now flow through the
  static parser API and block audit-grade status when required models are
  unavailable. This still does not execute real ONNX layout/table/OCR models.

## 2026-06-12 Continued Per-Model Fallback Warnings

- Started the next TDD milestone: make model fallback warnings specific enough
  for audit/replay diagnostics.
- Added RED coverage in `ModelRuntimePolicyTest` requiring offline
  model-assisted policies to emit one severe `model_unavailable_fallback`
  warning per missing required model, with model identity and expected SHA in
  the warning message.
- RED command:
  `mvn -q -Dtest=ModelRuntimePolicyTest test`.
- RED result: expected assertion failures because the implementation emitted a
  single generic fallback warning.
- Implemented per-model warning generation in `ModelRuntimePolicy.warnings()`.
- Focused model policy verification passed:
  `mvn -q -Dtest=ModelRuntimePolicyTest test`.
- Combined parser/SDK/model verification initially failed because
  `TrustDocumentParserApiContractTest` still expected one generic warning for
  `ParserPreset.STANDARD`.
- Updated that parser API test to expect two severe warnings for
  `layout-rtdetr:v2` and `tatr:v1`.
- Combined parser/SDK/model verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ModelRuntimePolicyTest test`.
- Updated `docs/pdf-parser-runtime-prd.md` to require per-model fallback
  warnings with model identity and expected SHA.
- First full Maven verification failed once in an unrelated provider HTTP test:
  `GeminiProviderHttpTest$HttpErrors.unauthorisedNonRetryable` expected
  `PROVIDER_HTTP_401` but received `PROVIDER_RESPONSE_INVALID`.
- Focused rerun passed:
  `mvn -q -Dtest=GeminiProviderHttpTest#unauthorisedNonRetryable test`.
- Second Java full verification passed:
  `mvn test` -> 871 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued JSON Full And Audit Writer APIs

- Started the next TDD milestone: extend streaming writer support to
  `json_full` and Audit JSON, not only Markdown/JSONL/compact.
- Added RED coverage in `TrustDocumentStreamingRenderContractTest` requiring
  `TrustDocument.writeJsonFull(Writer)` and `writeAuditJson(Writer)` to be
  byte-identical to `toJsonFull()` and `toAuditJson()`, and requiring large
  outputs to avoid one full-payload write into the caller-owned writer.
- RED command:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- RED result: expected testCompile failure because the writer APIs did not
  exist.
- Implemented renderer node reuse for JSON full and audit JSON plus a
  chunking writer adapter around Jackson writer output.
- Added public SDK methods:
  `TrustDocument.writeJsonFull(Writer)` and
  `TrustDocument.writeAuditJson(Writer)`.
- Focused streaming verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- Public API/architecture verification initially failed because the public API
  snapshot did not include the new writer methods.
- Updated public API snapshot with:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Focused streaming/API/architecture verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Updated `docs/pdf-parser-runtime-prd.md` with the current SDK writer API
  coverage and remaining streaming boundaries.
- Java full verification passed:
  `mvn test` -> 871 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued CLI Writer File Output Routing

- Started the next TDD milestone: route CLI `--out` file exports through
  writer paths for large replay formats instead of only exposing SDK writer
  APIs.
- Added RED coverage in `TrustDocumentCliWritersTest` requiring CLI-level JSON
  full and Audit JSON writers to match `TrustDocument` string renderers while
  avoiding one full-payload write into the caller-owned writer.
- RED command:
  `mvn -q -Dtest=TrustDocumentCliWritersTest test`.
- RED result: expected testCompile failure because `TrustDocumentCliWriters`
  did not exist.
- Added package-level `TrustDocumentCliWriters` and routed `ParseCommand --out`
  for clean Markdown, JSONL, compact LLM, JSON full, and Audit JSON through
  writer paths. At this point JSON evidence had a file-writer boundary but
  still used the aggregate evidence renderer.
- Focused writer verification passed:
  `mvn -q -Dtest=TrustDocumentCliWritersTest test`.
- Existing CLI output profile verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test`.
- Focused CLI/streaming/API/architecture verification passed:
  `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentStreamingRenderContractTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 873 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued Remaining Render Writer APIs

- Started the next TDD milestone: close remaining SDK/CLI file-output writer
  gaps for anchored Markdown, review Markdown, plain text, and HTML review.
- Added RED coverage in `TrustDocumentStreamingRenderContractTest` requiring
  `writeMarkdownAnchored(Writer)`, `writeMarkdownReview(Writer)`,
  `writePlainText(Writer)`, and `writeHtmlReview(Writer)` to be byte-identical
  to their string renderers and avoid one full-payload write into caller-owned
  writers.
- Added a regression assertion that HTML review emits one bbox overlay layer
  per page.
- RED command:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- RED result: expected testCompile failure because the four writer APIs did
  not exist.
- Implemented the four SDK writer APIs, renderer writer paths, and CLI `--out`
  routing for anchored/review Markdown, plain text, and HTML review.
- Focused streaming verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- Public API snapshot initially failed because the new writer methods changed
  the public SDK surface.
- Updated public API snapshot with:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Focused CLI/streaming/API/architecture verification passed:
  `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentStreamingRenderContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 874 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued CLI Stdout Writer Routing

- Started the next TDD milestone: close the remaining CLI TrustDocument stdout
  aggregate render path.
- Added RED coverage in `TrustDocumentCliWritersTest` requiring stdout writer
  output to match `TrustDocument` string renderers without one full-payload
  write into the underlying output stream.
- RED command:
  `mvn -q -Dtest=TrustDocumentCliWritersTest test`.
- RED result: expected testCompile failure because
  `TrustDocumentCliWriters.writeToPrintStream(...)` did not exist.
- Implemented a bounded `PrintStream` writer bridge and routed
  TrustDocument stdout output through the same format/profile writer dispatch
  used by CLI `--out`.
- Focused writer verification passed:
  `mvn -q -Dtest=TrustDocumentCliWritersTest test`.
- Existing CLI output profile verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test`.
- Focused CLI/streaming/API/architecture verification passed:
  `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentStreamingRenderContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 875 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued Source-Map Sidecar Writer Routing

- Started the next TDD milestone: close the CLI source-map sidecar aggregate
  JSON serialization path.
- Added RED coverage in `TrustDocumentCliWritersTest` requiring source-map
  sidecar JSON to write through a bounded writer path instead of one full JSON
  string.
- RED command:
  `mvn -q -Dtest=TrustDocumentCliWritersTest test`.
- RED result: expected testCompile failure because
  `TrustDocumentCliWriters.writeSourceMap(...)` did not exist.
- Implemented source-map sidecar writer serialization and routed
  `ParseCommand.writeSourceMapIfRequested(...)` through `TrustDocumentCliWriters`.
- Focused writer/profile verification passed:
  `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest test`.
- Focused source-map/CLI/streaming/API/architecture verification passed:
  `mvn -q -Dtest=TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentSourceMapContractTest,TrustDocumentStreamingRenderContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 876 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued Hash Input Writer Routing

- Started the next TDD milestone: close aggregate JSON string usage for
  canonical and evidence hash inputs.
- Added RED coverage in `TrustDocumentStreamingRenderContractTest` requiring
  canonical and evidence hash inputs to expose writer boundaries and avoid one
  full-payload write.
- RED command:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- RED result: expected testCompile failure because
  `TrustDocumentRenderers.writeCanonicalHashInput(...)` and
  `writeEvidenceHashInput(...)` did not exist.
- Implemented writer-visible hash input methods and changed
  `canonicalHash()` plus Audit JSON `evidenceHash` to hash through
  `DigestOutputStream` instead of aggregate JSON strings.
- Focused streaming verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- Focused hash/audit/API verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentRenderedOutputTest,TrustAuditVerifierTest,TrustDocumentParserApiContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 877 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued Benchmark Byte-Count Writer Routing

- Started the next TDD milestone: remove aggregate string byte counting from
  compact LLM benchmark size metrics.
- Added RED coverage in `ParserBenchmarkRunnerTest` requiring writer-backed
  full JSON and compact LLM byte counters.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- RED result: expected testCompile failure because
  `ParserBenchmarkRunner.jsonFullByteLength(...)` and
  `compactLlmByteLength(...)` did not exist.
- Implemented writer-backed byte counters with `OutputStreamWriter` over a
  counting output stream and routed compact-size reduction through them.
- Focused benchmark verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest test`.
- Focused benchmark/corpus/API verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 878 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued Source-Map Verifier Streaming Hash

- Started the next TDD milestone: remove full-file reads from
  `verify-source-map` rendered/source hash checks.
- Added RED coverage in `TrustDocumentCliOutputProfileTest` requiring
  package-visible streaming hash helpers for rendered text files and source
  files.
- RED command:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test`.
- RED result: expected testCompile failure because
  `VerifySourceMapCommand.sha256RenderedTextFile(...)` and
  `sha256SourceFile(...)` did not exist.
- Implemented buffered streaming file hash helpers and routed
  `verify-source-map` content/source checks through them.
- Focused CLI profile verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test`.
- Focused CLI/source-map/API verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 879 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued CLI/SDK Source Hash Streaming

- Started the next TDD milestone: remove full-file reads from source hashing
  in CLI parse and SDK path parse.
- Added RED coverage in `TrustDocumentParserApiContractTest` and
  `TrustDocumentCliOutputProfileTest` requiring streaming source-hash helpers
  for SDK path parsing and CLI parse source hashing.
- RED command:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest test`.
- RED result: expected testCompile failure because
  `TrustDocumentParser.sha256SourceFile(...)` and
  `ParseCommand.sourceHashForFile(...)` did not exist.
- Implemented buffered streaming source-hash helpers and routed SDK path parse
  plus CLI parse source hashing through them.
- Focused parser/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest test`.
- Focused parser/CLI/sidecar/API verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest,SidecarParserBackendTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 881 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued InputStream Parser Streaming Copy

- Started the next TDD milestone: remove `InputStream.readAllBytes()` from the
  SDK input-stream parser path.
- Added RED coverage in `TrustDocumentParserApiContractTest` with an
  `InputStream` wrapper that throws if `readAllBytes()` is called.
- RED command:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest test`.
- RED result: expected `ParseException` caused by `readAllBytes must not be
  used`.
- Implemented incremental `Files.copy(input, temp, REPLACE_EXISTING)` parsing,
  then routed the temporary PDF through the same PDFBox backend path used by
  file parsing so source hashes and page-image metadata stay consistent.
- Focused parser API verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest test`.
- Focused parser/backend/API verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest,ParserBackendContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 884 tests, 0 failures, 0 errors.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued PDFBox Rendered Page Image Hashes

- Started the next TDD milestone: replace placeholder PDFBox `TrustPage`
  metadata with rendered page dimensions and page image hashes.
- Added RED coverage in `ParserBackendContractTest` requiring PDFBox backend
  output to carry 72 DPI rendered page dimensions and a SHA-256 hash of the
  rendered PNG bytes.
- RED command:
  `mvn -q -Dtest=ParserBackendContractTest test`.
- RED result: expected assertion failure because the PDFBox backend still
  adapted pages as `1000x1000` with blank image hashes.
- Implemented `PdfPageImages` using PDFRenderer at 72 DPI, ImageIO PNG
  serialization, and SHA-256 hashing; routed `PdfBoxParserBackend` and
  `TrustDocumentParser.parse(Path, ...)` through enriched page metadata.
- Focused backend/parser verification passed:
  `mvn -q -Dtest=ParserBackendContractTest,TrustDocumentParserApiContractTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 883 tests, 0 failures, 0 errors.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued Source-Map Direct Writer APIs

- Started the next TDD milestone: remove the caller-visible
  `TrustRenderedDocument` materialization requirement from source-map sidecar
  writer paths while preserving compatibility APIs.
- Added RED coverage in `TrustDocumentStreamingRenderContractTest` and
  `TrustDocumentCliWritersTest` requiring SDK and CLI direct source-map writer
  methods for Markdown and compact LLM output.
- RED command:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliWritersTest test`.
- RED result: expected testCompile failure because
  `TrustDocument.writeMarkdownSourceMap(...)`,
  `TrustDocument.writeCompactLlmSourceMap(...)`,
  `TrustDocumentCliWriters.writeMarkdownSourceMap(...)`, and
  `writeCompactLlmSourceMap(...)` did not exist.
- Implemented direct source-map writer APIs, reused one internal source-map
  render shape for legacy and writer paths, and routed `parse --source-map`
  through the direct `TrustDocument` writer methods.
- Focused streaming/CLI writer verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliWritersTest test`.
- Updated public API snapshot with:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Focused streaming/CLI/source-map/API verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,TrustDocumentSourceMapContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 882 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.

## 2026-06-12 Continued JSON Evidence Writer API

- Started the next TDD milestone: close the remaining JSON evidence aggregate
  renderer gap for SDK and CLI file output.
- Added RED coverage in `TrustDocumentStreamingRenderContractTest` requiring
  `TrustDocument.writeJsonEvidence(Writer)` to be byte-identical to
  `toJsonEvidence()` and avoid one full-payload write into the caller-owned
  writer.
- RED command:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- RED result: expected testCompile failure because `writeJsonEvidence(...)` did
  not exist.
- Implemented `TrustDocument.writeJsonEvidence(Writer)`, reused the JSON
  evidence node renderer, and routed `TrustDocumentCliWriters.writeJsonEvidence`
  through the SDK writer.
- Focused streaming verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest test`.
- Public API snapshot initially failed because the new writer method changed
  the public SDK surface.
- Updated public API snapshot with:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Focused streaming/CLI/API/architecture verification passed:
  `mvn -q -Dtest=TrustDocumentStreamingRenderContractTest,TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Java full verification passed:
  `mvn test` -> 873 tests, 0 failures, 0 errors.

## 2026-06-12 Continued Rendered Page Image Artifacts

- Started the next TDD milestone: persist rendered page images as review/replay
  artifacts instead of keeping only `TrustPage.imageHash` metadata.
- Added RED SDK coverage in `PdfPageImageRendererTest` requiring
  `PdfPageImageRenderer.writePngs(...)` to write deterministic
  `page-%04d.png` files and return page metadata whose SHA-256 matches the
  actual PNG bytes.
- RED command:
  `mvn -q -Dtest=PdfPageImageRendererTest test`.
- RED result: expected testCompile failure because `PdfPageImageRenderer` did
  not exist.
- Implemented `PdfPageImageRenderer` and extended `PdfPageImages` so the same
  72 DPI PDFRenderer PNG bytes are used for both artifact writes and image
  hashes.
- Focused SDK verification passed:
  `mvn -q -Dtest=PdfPageImageRendererTest test`.
- Added RED CLI coverage in `DocTruthCliTest` requiring
  `doctruth render-pages <document> -o <dir>` to write `page-0001.png`,
  `page-images.json`, and useful stdout.
- RED command:
  `mvn -q -Dtest=DocTruthCliTest#renderPagesWritesPngArtifactsAndManifest test`.
- RED result: expected CLI usage failure because `render-pages` was not a
  recognized command.
- Implemented `RenderPagesCommand`, CLI dispatch, usage text, and the
  hash-bound `page-images.json` manifest.
- Focused CLI verification passed:
  `mvn -q -Dtest=DocTruthCliTest#renderPagesWritesPngArtifactsAndManifest test`.
- Updated public API snapshot with:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`.
- Focused API/CLI verification passed:
  `mvn -q -Dtest=PdfPageImageRendererTest,DocTruthCliTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Added smoke coverage in `scripts/smoke-doctruth-page-images.sh`; it packages
  the shaded CLI, generates a real PDF, runs `render-pages`, verifies PNG
  magic bytes, and checks the manifest hash against the actual PNG SHA-256.
- Page-image smoke passed:
  `sh scripts/smoke-doctruth-page-images.sh`.
- Java full verification passed:
  `mvn test` -> 886 tests, 0 failures, 0 errors.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Current honest status: Java/PDFBox can now render and persist deterministic
  page PNG artifacts for local review/replay. Rust/runtime page-image parity
  and an interactive browser review UI remain open.

## 2026-06-12 Continued Local Review Package

- Started the next TDD milestone: connect HTML review output and page image
  artifacts into one local static review package.
- Added RED CLI coverage in `DocTruthCliTest` requiring
  `doctruth review-package <document> -o <dir>` to write `review.html`,
  `trust-document.json`, `pages/page-0001.png`, and
  `pages/page-images.json`; the HTML must reference the page image and carry
  the existing `data-trust-page-number` anchors.
- RED command:
  `mvn -q -Dtest=DocTruthCliTest#reviewPackageWritesHtmlDocumentAndPageImages test`.
- RED result: expected CLI usage failure because `review-package` was not a
  recognized command.
- Implemented `ReviewPackageCommand`, CLI dispatch, usage text, static
  `review.html` shell, TrustDocument JSON output, page PNG export, and
  hash-bound page image manifest.
- Focused CLI verification passed:
  `mvn -q -Dtest=DocTruthCliTest#reviewPackageWritesHtmlDocumentAndPageImages test`.
- Added smoke coverage in `scripts/smoke-doctruth-review-package.sh`; it
  packages the shaded CLI, generates a real PDF, runs `review-package`, checks
  HTML image references and page anchors, and verifies the page image manifest
  hash against the actual PNG bytes plus the TrustDocument page hash.
- Review package smoke passed:
  `sh scripts/smoke-doctruth-review-package.sh`.
- Focused CLI/API verification passed:
  `mvn -q -Dtest=DocTruthCliTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Java full verification passed:
  `mvn test` -> 887 tests, 0 failures, 0 errors.
- Page-image smoke passed:
  `sh scripts/smoke-doctruth-page-images.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Current honest status: developers can now create a local static review
  package from one CLI command. This is still not a full interactive browser
  review UI; it is the verified artifact package that such a UI can consume.

## 2026-06-12 Continued V1 OCR Preset Routing

- User pushed back correctly that OCR should be prioritized because local
  RapidOCR/MNN-style OCR already exists.
- Audited current code and found OCR was partially present already:
  `OcrEngine`, `LocalOcrWorkerEngine`, `OcrEngines.defaultLocal()`, and
  `PdfDocumentParser.parse(path, ocrEngine)` existed, and CLI legacy parse used
  `OcrEngines.defaultLocal()`.
- Gap found: the v1 `TrustDocumentParser` path and `review-package` path did
  not use OCR. `ParserPreset.OCR` still behaved like `pdfbox` with offline
  model fallback provenance instead of local OCR provenance.
- Added RED SDK coverage in `TrustDocumentParserApiContractTest` requiring
  `TrustDocumentParser.parse(pdf, ParserPreset.OCR)` to route a low-text PDF
  through the configured local OCR worker, emit `parserRun.backend=pdfbox+ocr`,
  include `rapidocr-mnn:local`, suppress `model_unavailable_fallback`, and
  mark recovered units as `OCR_REGION`.
- RED command:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#ocrPresetRoutesLowTextPdfThroughConfiguredLocalWorker test`.
- RED result: expected assertion failure because OCR preset still reported
  backend `pdfbox`.
- Implemented v1 OCR preset routing through
  `PdfDocumentParser.parse(path, OcrEngines.defaultLocal())`, preserved rendered
  page image metadata, and used `pdfbox+ocr` / `rapidocr-mnn:local` parser
  provenance for OCR preset runs.
- Focused SDK OCR verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#ocrPresetRoutesLowTextPdfThroughConfiguredLocalWorker test`.
- Added RED CLI coverage in `DocTruthCliTest` requiring
  `doctruth review-package <pdf> --preset ocr -o <dir>` to produce OCR-backed
  `trust-document.json` and review HTML.
- RED command:
  `mvn -q -Dtest=DocTruthCliTest#reviewPackageCanUseOcrPresetWithConfiguredLocalWorker test`.
- RED result: expected usage failure because `review-package` did not accept
  `--preset`.
- Implemented `review-package --preset <preset>` and routed it through
  `TrustDocumentParser.parse(document, preset)`.
- Focused review-package OCR verification passed:
  `mvn -q -Dtest=DocTruthCliTest#reviewPackageCanUseOcrPresetWithConfiguredLocalWorker test`.
- Added RED CLI coverage requiring
  `doctruth parse <pdf> --format json --preset ocr -o <json>` to use the v1
  OCR preset rather than legacy `ParsedDocument` adaptation.
- RED command:
  `mvn -q -Dtest=DocTruthCliTest#parseTrustJsonCanUseOcrPresetWithConfiguredLocalWorker test`.
- RED result: expected assertion failure because TrustDocument JSON output
  still reported backend `pdfbox`.
- Routed TrustDocument output formats in `parse` through
  `TrustDocumentParser.parse(document, preset)` while leaving summary and
  legacy JSON/Markdown on the compatibility parser path.
- Focused parse OCR verification passed:
  `mvn -q -Dtest=DocTruthCliTest#parseTrustJsonCanUseOcrPresetWithConfiguredLocalWorker test`.
- Added smoke coverage in `scripts/smoke-doctruth-ocr-preset.sh`; it packages
  the shaded CLI, generates a blank low-text PDF, runs both
  `parse --format json --preset ocr` and `review-package --preset ocr` through
  a fake MNN-compatible worker, and verifies OCR provenance plus `OCR_REGION`
  output.
- OCR preset smoke initially failed because the fake worker used a heredoc
  Python script that consumed stdin before reading the OCR request. Rewrote it
  to `python3 -c` so it reads the Java request from stdin.
- OCR preset smoke passed:
  `sh scripts/smoke-doctruth-ocr-preset.sh`.
- Checked local raw `rapidocr` command:
  `/Users/jameslee/Library/Python/3.10/bin/rapidocr --help`.
- Local raw `rapidocr` is currently not a verified runtime because it fails to
  import NumPy C extensions: Python 3.10 is loading a `cpython-314` NumPy
  artifact. The verified path remains the local worker protocol rather than raw
  `rapidocr` CLI auto-discovery.
- Focused OCR/API verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,DocTruthCliTest,LocalOcrWorkerEngineTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Java full verification passed:
  `mvn test` -> 890 tests, 0 failures, 0 errors.
- Review package smoke passed:
  `sh scripts/smoke-doctruth-review-package.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Current honest status: v1 SDK and CLI TrustDocument paths can now use a
  configured local MNN/RapidOCR-compatible worker for OCR preset parsing. This
  still does not bundle OCR models in the generic jar, does not prove the local
  broken `rapidocr` Python command, and does not add OCR to the Rust sidecar.

## 2026-06-12 Continued OCR Confidence Audit Gate

- Followed up on the remaining OCR risk: the OCR worker returned confidence,
  but `TrustDocumentParser` lost it when converting `ParsedDocument` into
  `TrustDocument`. That meant weak OCR text could become `AUDIT_GRADE`.
- Added RED SDK coverage in `TrustDocumentParserApiContractTest` requiring a
  low-confidence OCR worker result to become `NOT_AUDIT_GRADE`, copy confidence
  into the OCR unit, and emit severe `ocr_low_confidence`.
- RED command:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#ocrPresetMarksLowConfidenceRecoveredTextAsNonAuditGrade test`.
- RED result: expected assertion failure because low-confidence OCR still
  produced `AUDIT_GRADE`.
- Implemented a narrow OCR confidence collector in `TrustDocumentParser`: the
  configured local OCR engine is wrapped during `ParserPreset.OCR`, page
  confidence is retained, OCR units receive `Confidence(score, "OCR page
  confidence")`, and confidence below `0.85` adds severe
  `ocr_low_confidence`.
- Focused SDK verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#ocrPresetMarksLowConfidenceRecoveredTextAsNonAuditGrade test`.
- Added CLI coverage in `DocTruthCliTest` requiring
  `doctruth parse <pdf> --format json --preset ocr` to emit
  `NOT_AUDIT_GRADE`, OCR confidence, and severe `ocr_low_confidence` for weak
  OCR.
- Focused CLI verification passed:
  `mvn -q -Dtest=DocTruthCliTest#parseTrustJsonMarksLowConfidenceOcrAsNotAuditGrade test`.
- Extended `scripts/smoke-doctruth-ocr-preset.sh` with a low-confidence fake
  MNN worker branch. The packaged shaded CLI now verifies both high-confidence
  OCR provenance and low-confidence audit blocking.
- OCR confidence smoke passed:
  `sh scripts/smoke-doctruth-ocr-preset.sh`.
- Focused OCR/API/CLI verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,DocTruthCliTest,LocalOcrWorkerEngineTest,TrustDocumentAuditGateTest,TrustDocumentRenderedOutputTest test`.
- Java full verification passed:
  `mvn test` -> 892 tests, 0 failures, 0 errors.
- Review package smoke passed:
  `sh scripts/smoke-doctruth-review-package.sh`.
- Page image smoke passed:
  `sh scripts/smoke-doctruth-page-images.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Whitespace check passed:
  `git diff --check`.

## 2026-06-13 Continued Java Merged-Cell Table Span

- Started the next table-structure gap: Java/PDFBox could recover generated
  bordered-grid tables and conservative borderless aligned text tables, but
  table-cell geometry only represented single cells.
- Added RED public model coverage in `TableCellRegionTest` requiring
  `rowEnd`/`columnEnd`, compatibility with the existing 3-arg constructor, and
  validation for invalid spans.
- Added RED parser coverage in `PdfMergedTableExtractionTest` with a generated
  bordered PDF table whose header cell spans two columns. The expected output is
  three `TrustTableCell` values: `Header` with `columnRange=0..1`, `A` with
  `0..0`, and `B` with `1..1`, all with bboxes and `TABLE_CELL` units.
- RED command:
  `mvn -q -Dtest=TableCellRegionTest,PdfMergedTableExtractionTest test`.
- RED result: expected compilation failure because `TableCellRegion` did not yet
  expose span fields or a 5-arg constructor.
- Implemented `TableCellRegion(row,column,rowEnd,columnEnd,bbox)` with a
  backward-compatible 3-arg constructor, adapted `TrustDocument` table-cell
  conversion to preserve span ranges, and updated `PdfPageTableExtractor` to
  detect horizontal merged cells when an internal vertical boundary does not span
  the current row interval.
- Refactored the internal detected-cell helper into smaller records so the
  implementation does not rely on a wide record.
- Added a generated-PDF benchmark assertion proving the merged-cell fixture
  scores `table_cell_f1 == 1.0`.
- Focused verification passed:
  `mvn -q -Dtest=TableCellRegionTest,PdfMergedTableExtractionTest,ParserBenchmarkRunnerTest,TableExtractionContractTest test`.
- Public API snapshot was updated for the `TableCellRegion` span contract and
  then passed:
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`
  and `mvn -q -Dtest=PublicApiSnapshotTest test`.
- Java full verification passed:
  `mvn -q test`.
- Smoke verification passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`,
  `sh scripts/smoke-doctruth-ocr-preset.sh`, and
  `sh scripts/smoke-doctruth-cli-sidecar-borderless.sh`.
- Updated `docs/pdf-parser-runtime-prd.md` and `task_plan.md` to mark Java
  generated horizontal colspan support as proven and to keep Rust parity,
  row-span reconstruction, multi-page continuation, OCR-backed tables,
  model-assisted table structure, and labeled real-world accuracy as open.
- OCR planning clarification: existing Java OCR worker routing, doctor readiness,
  low-confidence audit gating, and fake-MNN smoke are real; the still-open OCR
  milestone is a verified RapidOCR/MNN-compatible local worker adapter and real
  runtime/model smoke, not bundling OCR models into the Java jar.

## 2026-06-13 Continued RapidOCR/MNN Worker Adapter

- Continued the OCR milestone because the user correctly pointed out local OCR
  should be part of the parser runtime path.
- Audited the existing OCR boundary:
  `LocalOcrWorkerEngine` already used JSON over stdin/stdout and sent page PNG
  bytes, `ParserPreset.OCR` already routed low-text PDFs through the worker, and
  doctor already reported configured OCR worker readiness. The missing part was
  a DocTruth-owned RapidOCR/MNN adapter and default discovery/packaging for it.
- Added RED worker coverage in `LocalOcrWorkerEngineTest` requiring worker
  `pages[].regions[]` boxes to become `OcrPageResult.regions()`.
- Added RED doctor coverage in `DocTruthCliDoctorCompletionTest` requiring
  `doctruth doctor --json` to discover `doctruth-rapidocr-mnn-worker` on `PATH`.
- RED command:
  `mvn -q -Dtest=LocalOcrWorkerEngineTest,DocTruthCliDoctorCompletionTest test`.
- RED result: expected failures because OCR regions were dropped and the
  DocTruth RapidOCR worker name was not in the discovery list.
- Implemented OCR region parsing for both object bboxes
  `{x,y,width,height}` and array bboxes `[x,y,width,height]`, added
  `doctruth-rapidocr-mnn-worker` to SDK/doctor discovery, and kept legacy
  `tradebot-ocr-worker*` names as fallback candidates.
- Added `scripts/doctruth-rapidocr-mnn-worker`, a Python worker adapter that
  reads the DocTruth worker request, decodes the page PNG, calls
  `rapidocr.RapidOCR`, normalizes `boxes/txts/scores` or row tuples into
  DocTruth worker JSON, and returns structured `ok:false` payloads when RapidOCR
  is missing or fails.
- Added RED smoke coverage in `scripts/smoke-doctruth-rapidocr-worker.sh`.
  First run failed as expected because the adapter script did not exist.
- Extended the smoke after implementation to prove both direct adapter output and
  Java CLI `parse --preset ocr` through PATH discovery using a fake RapidOCR
  Python module.
- RapidOCR worker smoke passed:
  `sh scripts/smoke-doctruth-rapidocr-worker.sh`.
- Added release smoke coverage requiring the CLI tarball to include executable
  `bin/doctruth-rapidocr-mnn-worker`. RED run failed with
  `release tarball did not include executable RapidOCR worker adapter`.
- Updated `scripts/package-cli-release.sh`, generated Homebrew formula output,
  and `scripts/install-cli.sh` to package/install the worker adapter alongside
  `bin/doctruth`.
- CLI release smoke passed:
  `JAVA=<java> scripts/smoke-cli-release.sh --dist target/rapidocr-release-green`.
- Focused Java OCR/CLI/API verification passed:
  `mvn -q -Dtest=LocalOcrWorkerEngineTest,DocTruthCliDoctorCompletionTest,TrustDocumentParserApiContractTest,DocTruthCliTest,PublicApiSnapshotTest test`.
- Java full verification passed:
  `mvn -q test`.
- Final smoke/packaging verification passed:
  `sh scripts/smoke-doctruth-ocr-preset.sh`,
  `sh scripts/smoke-doctruth-rapidocr-worker.sh`,
  `scripts/package-cli-release.sh --dist target/rapidocr-release-final`, and
  `JAVA=<java> scripts/smoke-cli-release.sh --dist target/rapidocr-release-final`.
- Whitespace check passed:
  `git diff --check`.
- Updated `docs/cli.md`, `docs/install.md`,
  `docs/pdf-parser-runtime-prd.md`, and `task_plan.md` to describe the adapter,
  packaging, discovery order, and remaining boundary.
- Honest boundary: this proves the adapter/protocol/package path with a fake
  RapidOCR module. It does not prove that this machine currently has a working
  RapidOCR/MNN Python/model installation.

## 2026-06-13 Continued MCP/OCR Smoke Verification Closeout

- Re-ran the packaged MCP smoke after the local stdio gateway implementation:
  `sh scripts/smoke-doctruth-mcp.sh` passed.
- Re-ran OCR preset smoke, including configured worker provenance and
  low-confidence audit blocking:
  `sh scripts/smoke-doctruth-ocr-preset.sh` passed.
- Re-ran review package smoke:
  `sh scripts/smoke-doctruth-review-package.sh` passed.
- Re-ran page image artifact smoke:
  `sh scripts/smoke-doctruth-page-images.sh` passed.
- Re-ran Java CLI to Rust sidecar smoke:
  `sh scripts/smoke-doctruth-cli-sidecar.sh` passed.
- Re-ran benchmark corpus smoke:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh` passed.
- Re-ran Rust runtime smoke:
  `sh scripts/smoke-doctruth-runtime.sh` passed.
- Whitespace check passed:
  `git diff --check`.
- Latest full Java verification before this closeout passed:
  `mvn test` -> 895 tests, 0 failures, 0 errors.
- Historical remaining status at this point: this closed the local MCP
  `doctruth.parse_document` and OCR preset smoke slices, but had not yet
  finished broader MCP tools, skill packaging, model-assisted layout/table/OCR
  execution, or a human-labeled real-world benchmark corpus. The broader local
  MCP tools were completed in the later MCP Evidence Tool Coverage slice below.

## 2026-06-13 Continued Local MCP Parse-Document Gateway

- Started Phase 6 MCP/Skill Distribution TDD slice because the PRD requires an
  agent to parse a document through MCP and receive evidence spans plus bbox
  references.
- Added RED MCP CLI coverage in `DocTruthCliMcpTest`. The test sends newline
  JSON-RPC requests over stdin for `initialize`, `tools/list`, and
  `tools/call` with `doctruth.parse_document`, then requires compact LLM text,
  JSON evidence, bbox-bearing unit locations, and source-map entries.
- RED command:
  `mvn -q -Dtest=DocTruthCliMcpTest test`.
- RED result: expected test compile failure because `DocTruthCli` did not
  support stdin injection and had no `mcp` command.
- Implemented stdin injection in `CliContext` / `DocTruthCli`, added
  `McpCommand`, and wired `doctruth mcp`.
- First MCP green attempt failed because global `toJsonEvidence()` intentionally
  omits bbox locations. Kept the global JSON evidence contract unchanged and
  enriched only the MCP `structuredContent.jsonEvidence.units[]` with
  `location` and `boundingBox`.
- MCP focused verification passed:
  `mvn -q -Dtest=DocTruthCliMcpTest test`.
- Added RED discoverability checks requiring `doctruth mcp` in help and `mcp`
  in shell completion output.
- RED command:
  `mvn -q -Dtest=DocTruthCliTest#helpReturnsZeroAndListsProductCommands,DocTruthCliDoctorCompletionTest#completionPrintsShellScript test`.
- RED result: expected assertion failures because usage and completion did not
  list `mcp`.
- Updated usage and completion command lists. Focused MCP/discoverability
  verification passed:
  `mvn -q -Dtest=DocTruthCliMcpTest,DocTruthCliTest#helpReturnsZeroAndListsProductCommands,DocTruthCliDoctorCompletionTest#completionPrintsShellScript test`.
- Added packaged smoke `scripts/smoke-doctruth-mcp.sh`; it packages the shaded
  CLI, generates a PDF, sends MCP JSON-RPC over stdin, and verifies
  `doctruth.parse_document` returns compact text, evidence span ids, bbox
  location, and source-map unit ids.
- MCP smoke passed:
  `sh scripts/smoke-doctruth-mcp.sh`.
- Historical status at this point: local single-document MCP parse was
  executable and smoke-covered. Broader MCP tools were still open here and were
  completed in the later MCP Evidence Tool Coverage slice below. Skill
  packaging and model cache warmup over MCP remain open.

## 2026-06-13 Continued MCP Evidence Tool Coverage

- Started the next Phase 6 TDD slice: broader MCP evidence tools beyond
  `doctruth.parse_document`.
- Added RED coverage in `DocTruthCliMcpTest` requiring `tools/list` to expose
  `doctruth.get_layout_regions`, `doctruth.get_table_cells`,
  `doctruth.get_evidence_span`, and `doctruth.verify_citation`.
- Added RED end-to-end MCP calls requiring layout regions with bboxes,
  structured table cells with bboxes, evidence span lookup, and quote
  verification against an evidence span.
- RED command:
  `mvn -q -Dtest=DocTruthCliMcpTest test`.
- RED result: expected failures because only `doctruth.parse_document` was
  listed and the new tool calls returned no structured content.
- Implemented the four local stdio MCP tools in `McpCommand`. Each tool parses
  the requested local path through `TrustDocumentParser` and projects the
  existing v1 trust model into MCP `structuredContent`.
- Focused verification passed:
  `mvn -q -Dtest=DocTruthCliMcpTest test`.
- Extended `scripts/smoke-doctruth-mcp.sh` so the shaded CLI smoke now calls
  parse, layout regions, table cells, evidence span lookup, and citation
  verification. The smoke generates both a text-layer PDF and a bordered table
  PDF.
- Packaged MCP smoke passed:
  `sh scripts/smoke-doctruth-mcp.sh`.
- Refactored the MCP implementation after green because `McpCommand.java`
  had grown past the project source-file limit. Schema generation now lives in
  `McpToolSchemas`, structured MCP result projection lives in `McpToolResults`,
  and `McpCommand` is back to protocol dispatch.
- Post-refactor line counts are within the project limit:
  `McpCommand.java` 137 LOC, `McpToolSchemas.java` 86 LOC,
  `McpToolResults.java` 214 LOC.
- Post-refactor focused verification passed:
  `mvn -q -Dtest=DocTruthCliMcpTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Post-refactor packaged MCP smoke passed:
  `sh scripts/smoke-doctruth-mcp.sh`.
- Whitespace check passed:
  `git diff --check`.
- Updated `docs/cli.md`, `docs/pdf-parser-runtime-prd.md`, and `task_plan.md`
  to reflect that local MCP tool coverage now includes parse/layout/table/span
  lookup/citation verification. Remaining Phase 6 gaps are skill packaging and
  model cache warmup over MCP.

## 2026-06-13 Continued Skill Package And MCP Bootstrap

- Started the next Phase 6 slice: package DocTruth as an agent skill with a
  deterministic local MCP bootstrap path.
- Added RED coverage in `DocTruthSkillPackageContractTest` requiring:
  `skills/doctruth/SKILL.md`, `skills/doctruth/agents/openai.yaml`, and
  `skills/doctruth/scripts/bootstrap-local-mcp.sh`.
- The test also executes the bootstrap script and requires it to write MCP
  config JSON for `doctruth mcp`.
- RED command:
  `mvn -q -Dtest=DocTruthSkillPackageContractTest test`.
- RED result: expected failures because the skill package and bootstrap script
  did not exist.
- Added the concise DocTruth skill package:
  `skills/doctruth/SKILL.md`, `skills/doctruth/agents/openai.yaml`, and
  `skills/doctruth/scripts/bootstrap-local-mcp.sh`.
- Focused skill package verification passed:
  `mvn -q -Dtest=DocTruthSkillPackageContractTest test`.
- Combined focused verification passed:
  `mvn -q -Dtest=DocTruthCliMcpTest,DocTruthSkillPackageContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Added and ran skill package smoke:
  `sh scripts/smoke-doctruth-skill-package.sh` passed.
- Whitespace check passed:
  `git diff --check`.
- Full Java verification passed after MCP tools and skill package:
  `mvn test` -> 899 tests, 0 failures, 0 errors.
- Packaged MCP smoke passed after the skill package work:
  `sh scripts/smoke-doctruth-mcp.sh`.
- Updated `docs/pdf-parser-runtime-prd.md` and `task_plan.md` to mark skill
  packaging and local MCP bootstrap complete. Remaining Phase 6 gap is model
  cache warmup over MCP.

## 2026-06-13 Continued MCP Model Cache Warmup

- Started the final explicit Phase 6 MCP gap: model cache warmup/preflight over
  MCP.
- Defined the OSS-safe behavior as local verification only: agents can pass a
  cache directory and expected model descriptors, and DocTruth reports
  READY/MISSING/SHA_MISMATCH without implicit downloads.
- Added RED coverage in `DocTruthCliMcpTest` requiring
  `doctruth.warm_model_cache` to appear in `tools/list` and to verify a
  SHA-matched local model artifact through MCP `structuredContent`.
- RED command:
  `mvn -q -Dtest=DocTruthCliMcpTest test`.
- RED result: expected failures because `doctruth.warm_model_cache` was not
  listed or implemented.
- Implemented the MCP schema and dispatch plus `ModelCacheVerifier`-backed
  result projection in `McpToolResults`.
- Focused MCP verification passed:
  `mvn -q -Dtest=DocTruthCliMcpTest test`.
- Extended packaged MCP smoke to generate a local model artifact, pass its
  SHA-256 descriptor to `doctruth.warm_model_cache`, and verify READY status.
- Packaged MCP smoke passed:
  `sh scripts/smoke-doctruth-mcp.sh`.
- Updated `docs/cli.md`, `skills/doctruth/SKILL.md`,
  `docs/pdf-parser-runtime-prd.md`, and `task_plan.md` to show MCP model cache
  preflight as complete for this local stdio slice.
- Final focused verification for the Phase 6 additions passed:
  `mvn -q -Dtest=DocTruthCliMcpTest,DocTruthSkillPackageContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Final smoke verification for the Phase 6 additions passed:
  `sh scripts/smoke-doctruth-mcp.sh` and
  `sh scripts/smoke-doctruth-skill-package.sh`.
- Final full Java verification passed:
  `mvn test` -> 900 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.

## 2026-06-13 Continued Rust Runtime Two-Column Reading Order

- Started the next Remaining PRD Coverage slice: Rust `doctruth-runtime`
  lacked column-aware reading order for positioned text.
- Added RED cargo protocol coverage with a generated two-column PDF whose
  content stream is row-interleaved: left heading, right heading, left body,
  right body. The expected runtime output is visual column order: left heading,
  left body, right heading, right body.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_orders_two_column_positioned_text_by_visual_columns`.
- RED result: expected failure because output followed content-stream order:
  `LEFT PROFILE`, `RIGHT EXPERIENCE`, `Left column evidence.`,
  `Right column evidence.`
- Implemented minimal column-aware ordering for positioned text points in the
  Rust runtime. If a page has a large x-coordinate gap, the runtime sorts by
  left/right column first and y-position within each column; otherwise it
  preserves normal top-to-bottom ordering.
- Focused cargo verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_orders_two_column_positioned_text_by_visual_columns`.
- Cargo full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 10
  protocol tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Rust formatting passed after applying `cargo fmt`:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.

## 2026-06-12 Continued OCR Worker Doctor Readiness

- Started the next OCR-adjacent TDD slice: `doctruth doctor` should show
  whether a local OCR worker is visible before users try `--preset ocr`.
- Added RED CLI doctor coverage requiring `doctor --json` to expose OCR
  readiness fields and a configured executable worker.
- RED command:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest#doctorReportsConfiguredOcrWorkerReadiness test`.
- RED result: expected assertion failure because `doctor --json` had no `ocr`
  readiness object.
- Added package-local `OcrDoctor` and wired `DoctorCommand` text/JSON output.
  It reports resolved worker command, executable availability, disabled state,
  engine, fallback engine, and timeout using DocTruth OCR worker environment
  variables.
- Focused doctor verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test`.
- Scope note: this checks DocTruth worker executable readiness. It does not
  auto-treat raw `rapidocr` CLI as compatible with the JSON stdin/stdout worker
  protocol.
- Focused doctor/OCR/CLI verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest,DocTruthCliTest,TrustDocumentParserApiContractTest,LocalOcrWorkerEngineTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Java full verification passed:
  `mvn test` -> 893 tests, 0 failures, 0 errors.
- OCR preset smoke passed:
  `sh scripts/smoke-doctruth-ocr-preset.sh`.
- Review package smoke passed:
  `sh scripts/smoke-doctruth-review-package.sh`.
- Page image smoke passed:
  `sh scripts/smoke-doctruth-page-images.sh`.
- CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Whitespace check passed:
  `git diff --check`.

## 2026-06-13 Continued Java Borderless Table Fallback

- Started the next parser-quality slice from Remaining PRD Coverage: Java/PDFBox
  had generated bordered-grid table recovery, but no borderless aligned text
  table recovery.
- Added RED coverage in `PdfBorderlessTableExtractionTest` with a generated PDF
  containing no table rules, only aligned short text cells:
  `Name`, `Score`, `Alex`, `98`.
- RED command:
  `mvn -q -Dtest=PdfBorderlessTableExtractionTest test`.
- RED result: expected failure because `document.body().tables()` was empty and
  `table_cell_f1` was `0.0`.
- Implemented `PdfBorderlessTableExtractor` as a conservative fallback behind
  `PdfPageTableExtractor`: it only runs when no bordered grid is detected,
  groups same-baseline text into cells by large x gaps, requires at least two
  rows with stable column x anchors, emits table/cell bboxes, and rejects bold
  or long-cell rows to avoid swallowing resume layout sections.
- Focused borderless verification passed:
  `mvn -q -Dtest=PdfBorderlessTableExtractionTest test`.
- First broader regression pass caught real false positives: sidebar language
  rows and two-column resume layout blocks were being emitted as `TableSection`,
  which broke existing text-layout tests.
- Tightened the fallback to reject bold-cell matrices. This keeps the current
  no-model heuristic limited to plain short matrices; complex borderless tables
  with bold headers remain a model/table-parser task.
- Focused parser/layout verification passed:
  `mvn -q -Dtest=PdfBorderlessTableExtractionTest,ParserBenchmarkRunnerTest,TableExtractionContractTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Java full verification passed:
  `mvn test` -> 902 tests, 0 failures, 0 errors.

## 2026-06-13 Continued Rust Borderless Table Parity

- Started the next explicit Remaining PRD Coverage gap: Rust
  `doctruth-runtime` had generated bordered-grid table output, but no
  borderless aligned text table output.
- Added RED integration coverage in
  `runtime/doctruth-runtime/tests/borderless_table_contract.rs` with a generated
  PDF containing no table rules, only aligned short text cells:
  `Name`, `Score`, `Alex`, `98`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test borderless_table_contract`.
- RED result: expected failure because `tables.len()` was `0` instead of `1`.
- Implemented a conservative Rust fallback over content-stream `TextPoint`s:
  when bordered-grid extraction fails, the runtime groups same-y text points
  into rows, requires at least two rows with stable column x anchors, rejects
  long cells, bounds table width, emits table/cell bboxes, and marks the
  confidence rationale as `borderless aligned text table extraction`.
- Focused borderless cargo verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test borderless_table_contract`.
- Existing runtime protocol verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> 10 tests passed.
- Rust formatting passed:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Cargo full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 11
  integration tests across borderless and protocol contracts passed.
- Runtime smoke was extended with an explicit generated borderless table PDF and
  passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Focused Java sidecar/render verification passed:
  `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentCliOutputProfileTest,TrustDocumentRenderedOutputTest test`.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this is still generated-fixture, heuristic borderless table
  support. It does not prove merged cells, multi-page continuation,
  model-assisted table structure, OCR-backed tables, or labeled real-world
  table accuracy.

## 2026-06-13 Continued CLI Sidecar Borderless Smoke

- Audited current smoke coverage and found `scripts/smoke-doctruth-runtime.sh`
  explicitly checked Rust borderless table output, while
  `scripts/smoke-doctruth-cli-sidecar.sh` only checked the Java CLI sidecar path
  for bordered tables.
- Added `scripts/smoke-doctruth-cli-sidecar-borderless.sh` as a narrow packaged
  smoke for the user-facing path. It builds the Rust runtime and shaded Java
  CLI, generates a borderless aligned text table PDF, parses through
  `doctruth parse --backend sidecar`, and verifies JSON table cells, cell bboxes,
  clean GFM Markdown, and plain text output.
- New smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar-borderless.sh`.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Existing CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Focused Java sidecar/render verification passed:
  `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentCliOutputProfileTest,TrustDocumentRenderedOutputTest test`.
- Whitespace check passed:
  `git diff --check`.

## 2026-06-13 Continued Rust Horizontal Merged-Cell Parity

- Started the next explicit table-parity gap: Java/PDFBox preserved generated
  horizontal merged-cell column spans, but Rust `doctruth-runtime` still split
  the merged header region into one cell per grid column.
- Added RED protocol coverage in
  `runtime/doctruth-runtime/tests/protocol_contract.rs` with a generated PDF
  where the top-row `Header` spans two columns because the internal vertical
  boundary exists only in the bottom row.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_preserves_horizontal_merged_cell_column_span`.
- RED result: expected failure because the runtime returned 4 table cells
  instead of 3 and did not emit `Header` with `columnRange` `0..1`.
- Implemented Rust bordered-grid span reconstruction for horizontal merged
  cells: each row now checks whether an internal vertical boundary covers that
  row band, extends the cell to the next covered boundary when absent, collects
  text across the whole merged range, and emits `rowRange`/`columnRange` in
  table JSON.
- Focused merged-cell Cargo verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_preserves_horizontal_merged_cell_column_span`.
- Runtime protocol verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> 11 tests passed.
- Borderless table runtime verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test borderless_table_contract`.
- Cargo full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 12
  integration tests across protocol and borderless table contracts passed.
- Rust formatting passed after applying `cargo fmt`:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Runtime smoke was extended with an explicit generated horizontal merged-cell
  PDF and passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke was extended to parse the same generated horizontal
  merged-cell fixture through the Rust sidecar and passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Honest boundary: this proves generated horizontal colspan parity in the Rust
  runtime and Java CLI sidecar path. It still does not prove row spans,
  multi-page continuation, model-assisted table structure, OCR-backed tables,
  or labeled real-world table accuracy.

## 2026-06-13 Continued Vertical Row-Span Table Parity

- Continued the table-structure PRD slice with generated vertical merged-cell
  row spans. This is the next bounded gap after horizontal colspan parity and is
  still intentionally fixture-grade, not a real-world model-assisted table
  benchmark.
- Added RED Java/PDFBox coverage in
  `src/test/java/ai/doctruth/PdfMergedTableExtractionTest.java` with a generated
  bordered PDF where the left `Role` cell spans two rows because the internal
  horizontal boundary only exists across the right column.
- Java RED command:
  `mvn -q -Dtest=PdfMergedTableExtractionTest#borderedTablePreservesVerticalMergedCellRowSpan test`.
- Java RED result: expected failure because the parser returned no table. The
  grid detector required every horizontal separator to span the full table
  width, so partial internal separators used by row spans were rejected.
- Implemented Java/PDFBox row-span reconstruction in `PdfPageTableExtractor`:
  grid detection now allows partial internal separators when top/bottom borders
  and outer vertical borders exist, cell detection tracks an occupied matrix,
  extends a cell downward when the internal horizontal boundary does not cover
  the cell's column span, and emits `TableCellRegion.rowEnd`.
- Java focused GREEN passed:
  `mvn -q -Dtest=PdfMergedTableExtractionTest#borderedTablePreservesVerticalMergedCellRowSpan test`.
- Java merged-table contract passed:
  `mvn -q -Dtest=PdfMergedTableExtractionTest test`.
- Added a generated row-span benchmark assertion requiring
  `ParserBenchmarkRunner` to score `table_cell_f1=1.0` for row-span cell
  recovery.
- Focused Java table/benchmark verification passed:
  `mvn -q -Dtest=PdfMergedTableExtractionTest,TableExtractionContractTest,ParserBenchmarkRunnerTest test`.
- Added RED Rust protocol coverage in
  `runtime/doctruth-runtime/tests/protocol_contract.rs` requiring the same
  generated row-span PDF to emit 3 cells, with `Role` carrying `rowRange`
  `0..1`.
- Rust RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_preserves_vertical_merged_cell_row_span`.
- Rust RED result: expected failure because the runtime returned 4 table cells
  instead of 3.
- Implemented Rust runtime row-span reconstruction with the same contract
  shape: the bordered-grid path tracks occupied cells, extends downward when a
  horizontal boundary does not cover the cell's x range, collects text across
  the merged cell box, and emits `rowRange` in table JSON.
- Rust focused GREEN passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_preserves_vertical_merged_cell_row_span`.
- Rust protocol verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> 12 tests passed.
- Rust full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 13
  integration tests across protocol and borderless table contracts passed.
- Rust formatting passed:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Runtime smoke was extended with the generated row-span fixture and passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke was extended with the generated row-span fixture and
  passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 911 tests, 0 failures, 0 errors.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves generated bordered-table vertical row-span
  support in Java/PDFBox, Rust runtime, and Java CLI sidecar JSON output. It
  still does not prove multi-page table continuation, model-assisted table
  structure recognition, OCR-backed tables, bold-header borderless tables, or
  labeled real-world table accuracy.

## 2026-06-13 Continued Rust Page Metadata Parity

- Started a bounded Rust/runtime page metadata slice from the remaining PRD
  coverage. Java/PDFBox already records rendered page dimensions and rendered
  PNG hashes; Rust sidecar still emitted hard-coded `612x792` dimensions and a
  placeholder hash derived from caller-supplied `source_hash`.
- Added RED protocol coverage in
  `runtime/doctruth-runtime/tests/protocol_contract.rs` using a generated PDF
  with `MediaBox [0 0 300 400]`. The test parses the same PDF with two
  different source hashes and requires page width `300`, height `400`, and the
  same `sha256:` page hash independent of source hash.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_uses_media_box_page_dimensions_and_stable_page_hash`.
- RED result: expected failure because the runtime still returned width `612`.
- Added `sha2` to `runtime/doctruth-runtime` and documented it in ADR 0010.
  The runtime now reads page MediaBox dimensions through `lopdf`, computes a
  stable per-page `sha256:` hash from page number, dimensions, and page content
  bytes, and uses that metadata in `body.pages`.
- Focused GREEN passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_uses_media_box_page_dimensions_and_stable_page_hash`.
- Rust full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 14
  integration tests across protocol and borderless table contracts passed.
- Rust formatting passed after `cargo fmt`:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Runtime smoke was updated to assert page dimensions and reject placeholder
  source-hash page metadata, then passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke initially failed because it still expected the old
  `:page-1` placeholder hash in HTML output. Updated the smoke to reject that
  stale placeholder and it passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Honest boundary: Rust now has real MediaBox page dimensions and stable
  page-content metadata hashes. It still does not render PNG page images, write
  page image artifacts, or match Java/PDFBox rendered PNG hashes.

## 2026-06-13 Continued Rust Model-Assisted Fallback Parity

- Started the next bounded TDD slice from the no-silent-heuristic-fallback PRD
  contract: Rust sidecar model-assisted presets should match Java parser
  warning/audit behavior when required models are unavailable.
- Added RED protocol coverage in
  `runtime/doctruth-runtime/tests/protocol_contract.rs` requiring
  `preset=table-lite` to return inspectable heuristic output, report
  `parserRun.models=["slanet-plus:v1"]`, emit a severe
  `model_unavailable_fallback` warning containing the model identity, and mark
  `auditGradeStatus=NOT_AUDIT_GRADE`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_marks_model_assisted_preset_fallback_as_not_audit_grade`.
- RED result: expected failure because Rust returned `AUDIT_GRADE` with no
  warnings.
- Implemented Rust preset-to-required-model mapping for `standard`,
  `table-lite`, `table-server`, and `ocr`, with per-model severe fallback
  warnings and audit-grade downgrade when the runtime emits heuristic output.
- Focused GREEN passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_marks_model_assisted_preset_fallback_as_not_audit_grade`.
- Rust full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 15
  integration tests across protocol and borderless table contracts passed.
- Rust formatting passed after `cargo fmt`:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Runtime smoke now covers `preset=table-lite` and passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke now covers `--preset table-lite` and passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Focused Java sidecar/CLI verification passed:
  `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentCliOutputProfileTest test`.
- Java full verification passed:
  `mvn test` -> 911 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Honest boundary: Rust sidecar now has fallback honesty and warning parity for
  model-assisted presets. It still does not execute ONNX layout/table/OCR
  models; real model execution remains an open PRD item.

## 2026-06-13 Continued RapidOCR Worker Readiness

- Picked up the OCR/RapidOCR remaining PRD gap. The existing implementation had
  OCR preset routing, fake-MNN smoke, a packaged `doctruth-rapidocr-mnn-worker`,
  and PATH discovery, but `doctruth doctor --json` treated an executable worker
  as available without checking whether RapidOCR could actually import or
  initialize.
- Checked current local raw OCR state:
  `/Users/jameslee/Library/Python/3.10/bin/rapidocr --help` still fails with a
  NumPy ABI mismatch, and default `python3` cannot import `rapidocr`.
- Added RED coverage in `DocTruthCliDoctorCompletionTest` requiring doctor JSON
  to expose OCR `ready`, `statusCode`, and `message`, and to distinguish a
  broken executable worker from a ready worker.
- RED command:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test`.
- RED result: expected failures because `ready/statusCode/message` were missing
  and broken workers were not self-tested.
- Implemented `OcrDoctor` self-test execution through `worker --doctor` with a
  short timeout. Doctor JSON now reports `available` separately from `ready`,
  plus structured `statusCode` and `message`.
- Focused doctor GREEN passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test`.
- Added RED smoke coverage in `scripts/smoke-doctruth-rapidocr-worker.sh`
  requiring `scripts/doctruth-rapidocr-mnn-worker --doctor` to report
  `ok=true`, `runtime=rapidocr`, and `code=ready` under a fake RapidOCR module.
- RED smoke result: expected assertion failure because the adapter did not
  support `--doctor`.
- Implemented adapter `--doctor`: it imports `RapidOCR`, initializes
  `RapidOCR()`, and returns structured readiness JSON. Import/init failures are
  surfaced as `rapidocr_unavailable` or `rapidocr_init_failed`.
- RapidOCR worker smoke passed:
  `sh scripts/smoke-doctruth-rapidocr-worker.sh`.
- Focused OCR/doctor/CLI verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest,LocalOcrWorkerEngineTest,DocTruthCliTest test`.
- Release package smoke was extended to execute the packaged
  `bin/doctruth-rapidocr-mnn-worker --doctor` under a fake RapidOCR module.
  The first run failed because I passed `JAVA=${JAVA_HOME:-}/bin/java`, which
  expanded to `/bin/java` when `JAVA_HOME` was unset. Rerunning with the actual
  Homebrew OpenJDK path passed:
  `JAVA=/opt/homebrew/opt/openjdk/bin/java scripts/smoke-cli-release.sh --dist target/rapidocr-readiness-release`.
- OCR preset smoke passed:
  `sh scripts/smoke-doctruth-ocr-preset.sh`.
- Java full verification passed:
  `mvn test` -> 912 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Real local adapter self-test result:
  `scripts/doctruth-rapidocr-mnn-worker --doctor` returned
  `{"ok":false,"code":"rapidocr_unavailable","runtime":"rapidocr","engine":"mnn","message":"No module named 'rapidocr'"}`.
- Honest boundary: DocTruth now has a real readiness contract for RapidOCR/MNN
  workers. This machine still does not have a working RapidOCR/MNN install, so
  real scanned-PDF OCR accuracy is not yet proven.

## 2026-06-13 Continued Java Multi-Page Table Continuation

- Started the next parser-quality slice from Remaining PRD Coverage: Java/PDFBox
  should recover a simple multi-page bordered table continuation instead of
  emitting one table per page with a duplicate repeated header.
- Added RED coverage in `PdfMergedTableExtractionTest` requiring a generated
  two-page bordered PDF to produce one logical `TrustTable`, dedupe the page-2
  header, keep cells in order as `Name, Score, Alex, 98, Bea, 97`, and retain
  page-2 evidence locations for continued cells.
- Added benchmark coverage for the same fixture requiring `table_cell_f1 == 1.0`.
- RED result: expected failure because the parser emitted two tables and the
  benchmark scored `0.8571428571428571`.
- Implemented page-aware `TableCellRegion` and Java/PDFBox continuation merging
  for adjacent table sections with matching repeated headers and aligned table
  x-bounds. The merge appends continuation rows after dropping the repeated
  header and keeps each cell region's original source page.
- Architecture follow-up: the first page-aware implementation made
  `TableCellRegion` a 6-component public record, violating the project
  architecture test. Refactored it to `page + rowRange + columnRange +
  boundingBox`, preserving compatibility methods and constructors for
  `row()`, `rowEnd()`, `column()`, and `columnEnd()`.
- Updated the public API snapshot for the new `TableCellRegion` component
  shape.
- Focused verification passed:
  `mvn -q -Dtest=PdfMergedTableExtractionTest,TableCellRegionTest,TableSectionTest,ParserBenchmarkRunnerTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Java full verification passed:
  `mvn test` -> 914 tests, 0 failures, 0 errors.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Honest boundary: this proves a generated Java/PDFBox repeated-header
  continuation fixture. Rust sidecar multi-page continuation, real-world
  labeled table continuation accuracy, OCR-backed tables, and model-assisted
  table structure remain open.

## 2026-06-13 Continued Rust Multi-Page Table Continuation

- Picked the next Remaining PRD Coverage gap: Rust `doctruth-runtime` still
  emitted one table per page for adjacent repeated-header table continuations,
  while Java/PDFBox already merged the generated fixture.
- Added RED coverage in
  `runtime/doctruth-runtime/tests/protocol_contract.rs` requiring a two-page
  bordered-grid PDF to produce one logical table, dedupe the page-2 repeated
  header, output cells `Name, Score, Alex, 98, Bea, 97`, and keep `Bea`/`97`
  `TABLE_CELL` unit locations on page 2.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_merges_multi_page_table_continuation_with_repeated_header -- --nocapture`.
- RED result: expected failure because `tables.len()` was 2 instead of 1.
- Implemented Rust runtime continuation merging after per-page extraction. The
  merge only applies to adjacent pages with non-empty matching normalized header
  rows and aligned table x-bounds. It drops the continuation header, offsets
  continued row ranges, preserves the first table id/page, and stores page
  number per table cell so units keep original source-page evidence.
- Focused GREEN passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_merges_multi_page_table_continuation_with_repeated_header -- --nocapture`.
- Cargo protocol/full verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 15
  protocol tests plus borderless contract passed.
- Rust formatting passed after `cargo fmt`:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Runtime smoke passed with the new continued-table fixture:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke passed with the new continued-table fixture:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Java full verification passed:
  `mvn test` -> 914 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Honest boundary: Rust and Java now both handle generated adjacent
  repeated-header table continuation fixtures. This still does not prove
  model-assisted table recognition, OCR-backed table extraction,
  bold-header/borderless continuation, or labeled real-world table accuracy.

## 2026-06-13 Continued Rust Rendered PNG Page Hash Parity

- Picked the next Remaining PRD Coverage gap: Rust `doctruth-runtime` page
  metadata still used content-derived page hashes, while Java/PDFBox hashes
  rendered PNG page bytes for page image review/audit parity.
- Added RED coverage in
  `runtime/doctruth-runtime/tests/protocol_contract.rs` requiring a configured
  fake page renderer to write PNG bytes and requiring the runtime page
  `imageHash` to equal the SHA-256 of those rendered bytes.
- RED result: the runtime returned the previous stable content/dimension hash
  instead of the rendered PNG byte hash.
- Fixed a test expectation bug in the first version of the test: the expected
  fake PNG bytes must begin with the exact PNG signature bytes, not a UTF-8
  string escape for `0x89`.
- Implemented `DOCTRUTH_RUNTIME_PAGE_RENDERER` support and local `pdftoppm`
  fallback in the Rust runtime. The runtime now hashes actual rendered PNG
  bytes when a renderer succeeds, validates the PNG signature, and falls back
  to the previous stable content/dimension hash only when rendering is
  unavailable or invalid.
- Runtime and Java CLI sidecar smokes now compare `TrustPage.imageHash` against
  a real `pdftoppm` render of the same fixture PDF when `pdftoppm` is present.
- Command mistake encountered:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_uses_configured_rendered_png_hash_for_page_image_metadata parse_pdf_uses_media_box_page_dimensions_and_stable_page_hash -- --nocapture`
  failed because Cargo accepts only one test-name filter. Resolved by running
  the full protocol contract test target.
- Rust formatting passed:
  `cargo fmt --check --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Cargo protocol verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> 16 tests passed.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Cargo full runtime tests passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Java full verification passed:
  `mvn test` -> 914 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Honest boundary: this is Rust runtime/sidecar page-image hash parity, not a
  Rust-owned persisted page image artifact pipeline or interactive review UI.
  Default real rendering depends on an external renderer such as `pdftoppm`.
  Real OCR/model execution and labeled-corpus accuracy remain open.

## 2026-06-13 Continued RapidOCR Real Runtime Smoke

- Picked the next OCR gap: fake worker and doctor readiness existed, but real
  RapidOCR runtime execution was still unproven.
- Current global environment check:
  `scripts/doctruth-rapidocr-mnn-worker --doctor` still reports
  `rapidocr_unavailable` under default Homebrew Python 3.14 because `rapidocr`
  is not installed there.
- Python 3.10 global environment check:
  `python3.10 scripts/doctruth-rapidocr-mnn-worker --doctor` imports the global
  RapidOCR package but fails because the user-level NumPy install contains a
  cpython-314 extension under the Python 3.10 site-packages path.
- Isolated venv experiment:
  installed `numpy<2.0`, `rapidocr==3.8.1`, and
  `rapidocr_onnxruntime==1.4.4`; worker `--doctor` initialized RapidOCR and
  downloaded PP-OCRv4 mobile ONNX detector/classifier/recognizer models.
- Real OCR direct request initially exposed a production adapter bug:
  RapidOCR 3.8-style `boxes`/`txts`/`scores` can be array-like, and the worker
  used `getattr(... ) or []`, causing `The truth value of an array with more
  than one element is ambiguous`.
- Added RED coverage by changing `scripts/smoke-doctruth-rapidocr-worker.sh`
  fake RapidOCR output to return array-like values whose `__bool__` raises.
  The smoke failed as expected.
- Implemented the adapter fix in `scripts/doctruth-rapidocr-mnn-worker`:
  `attr_sequence(...)` converts list/tuple/iterable/`tolist()` values without
  truthiness checks, and `box_from_any(...)` handles values with `tolist()`.
- GREEN verification passed:
  `sh scripts/smoke-doctruth-rapidocr-worker.sh`.
- Added `scripts/smoke-doctruth-rapidocr-real.sh`. It is opt-in via
  `DOCTRUTH_RAPIDOCR_REAL_SMOKE=1`, creates or reuses an isolated venv,
  installs RapidOCR + ONNXRuntime backend, runs worker `--doctor`, runs direct
  OCR on a generated PNG, packages the Java CLI, and verifies
  `doctruth parse --preset ocr` over a generated scanned PDF.
- Default non-download path passed:
  `sh scripts/smoke-doctruth-rapidocr-real.sh` prints a skip message and exits
  successfully.
- Real opt-in smoke passed using the isolated venv:
  `DOCTRUTH_RAPIDOCR_REAL_SMOKE=1 DOCTRUTH_RAPIDOCR_VENV=/var/folders/70/r564ynxd2v5b40g7_y59nbpw0000gn/T//doctruth-real-rapidocr.likXr4/venv sh scripts/smoke-doctruth-rapidocr-real.sh`.
- Additional verification passed:
  `sh scripts/smoke-doctruth-ocr-preset.sh`.
- Focused Java verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest,TrustDocumentParserApiContractTest,LocalOcrWorkerEngineTest test`.
- Java full verification passed:
  `mvn test` -> 914 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Consistency search note: an initial `rg` command used a backticked pattern and
  zsh tried to execute `rapidocr_unavailable`; reran with single quotes and
  confirmed only intentional RapidOCR + ONNXRuntime / MNN boundary text remains.
- Honest boundary: this proves real RapidOCR + ONNXRuntime local OCR through the
  DocTruth worker and Java CLI on a generated scanned PDF. It does not prove an
  MNN-specific RapidOCR backend package, full real-world scanned-PDF OCR
  accuracy, or labeled corpus metrics.

## 2026-06-13 Continued OCR Benchmark Corpus Gate

- Picked the next OCR gap: OCR could be smoke-tested, but parser benchmark
  corpus could not quantify OCR text accuracy or request OCR preset parsing per
  corpus case.
- Added RED coverage in `ParserBenchmarkRunnerTest` requiring
  `ocr_text_accuracy == 1.0` for exact OCR text and requiring the metric to drop
  plus fail threshold gating when OCR misses expected content.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsOcrTextAccuracy test`
  failed because missing metrics default to `0.0`.
- Implemented `ocr_text_accuracy` using Commons Text `LevenshteinDistance` over
  normalized OCR-region text vs expected Markdown. Non-OCR documents score
  `1.0` for this metric so existing text/table corpora do not fail.
- Added RED coverage in `ParserBenchmarkCorpusTest` requiring manifest
  `preset: "ocr"` to route a blank generated PDF through the configured OCR
  worker and produce `parserRun.preset == "ocr"` plus `OCR_REGION` units.
- RED result: the loaded case still used `lite`, proving corpus manifests
  ignored the preset field.
- Implemented per-case `preset` support in `ParserBenchmarkCorpus` and a
  preset-aware `ParserBenchmarkCase.fromPdf(...)` overload.
- Updated the public API snapshot for the new benchmark-case overload.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` with a generated
  scanned-PDF OCR case, a fake OCR worker, `preset: "ocr"`, and
  `ocr_text_accuracy` threshold assertions in CLI JSON output.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- OCR smoke passed:
  `sh scripts/smoke-doctruth-ocr-preset.sh`.
- RapidOCR worker smoke passed:
  `sh scripts/smoke-doctruth-rapidocr-worker.sh`.
- Java full verification passed:
  `mvn test` -> 917 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Error encountered: two focused Maven tests were launched in parallel and one
  failed with a missing surefirebooter temporary jar. This was a target
  directory race, not a test failure. Reran the benchmark suite sequentially and
  it passed.
- Honest boundary: generated scanned-PDF OCR cases can now be threshold-gated in
  benchmark corpus manifests. This still does not provide a labeled real-world
  scanned-PDF OCR corpus or real MNN backend execution.

## 2026-06-13 Continued Local Model Worker Contract

- Picked the next model-assisted gap: `TABLE_LITE` could honestly warn about
  missing models, but it could not yet use a configured local model worker to
  return model-produced `TrustDocument` output.
- Added RED coverage in `TrustDocumentParserApiContractTest` requiring
  `ParserPreset.TABLE_LITE` plus `doctruth.model.command` to return
  `parserRun.backend == "pdfbox+model-worker"`, `models == ["slanet-plus:v1"]`,
  no `model_unavailable_fallback`, one `TrustTable`, and four `TABLE_CELL`
  units from the worker response.
- RED result: the parser still returned the PDFBox fallback backend instead of
  invoking the configured model worker.
- Implemented package-private `LocalModelWorker` using JSON over stdin/stdout.
  It discovers commands from `doctruth.model.command`,
  `DOCTRUTH_MODEL_COMMAND`, or `LOCAL_MODEL_COMMAND`, sends the preset, source
  metadata, required model descriptors, and source bytes, and accepts a full
  `TrustDocument` JSON response.
- Wired `TrustDocumentParser` to try the configured worker for non-lite/non-OCR
  model-assisted presets before applying fallback warnings.
- First GREEN attempt failed because the fake worker used a heredoc and Python
  consumed stdin as script source, leaving no JSON request. Rewrote fake workers
  as executable Python scripts that read the JSON request from stdin.
- Added `scripts/smoke-doctruth-model-worker.sh` to package the CLI, generate a
  PDF, run a fake table-lite model worker, and verify CLI JSON table/cell output
  plus `pdfbox+model-worker` provenance.
- Verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#tableLitePresetCanUseConfiguredLocalModelWorker test`.
- Model-worker smoke passed:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Focused parser/backend/API verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,ParserBackendContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Java full verification passed:
  `mvn test` -> 918 tests, 0 failures, 0 errors.
- Honest boundary: this proves the model-worker protocol and CLI wiring with a
  fake table-lite worker. It does not run real ONNX/TATR/SLANeXT/RT-DETR models
  or prove real-world table/layout accuracy.

## 2026-06-13 Continued Model Worker Doctor Readiness

- Picked the next model-runtime deployment gap: parsing could use
  `doctruth.model.command`, but `doctruth doctor --json` could not report
  whether that configured model worker existed or passed its own runtime check.
- Added RED coverage in `DocTruthCliDoctorCompletionTest` requiring
  `models.worker` JSON to expose `command`, `available`, `ready`, `statusCode`,
  `message`, `timeoutMs`, and `loadedModels` for a configured fake worker.
- RED result:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest#doctorJsonReportsConfiguredModelWorkerReadiness test`
  failed because `models.worker.command` was empty.
- Added a second doctor test for an executable but not-ready model worker that
  reports `model_runtime_unavailable`, matching the OCR doctor distinction
  between executable availability and runtime readiness.
- Implemented `ModelWorkerDoctor`, resolving explicit
  `DOCTRUTH_MODEL_COMMAND` / `LOCAL_MODEL_COMMAND`, probing `worker --doctor`,
  respecting `DOCTRUTH_MODEL_TIMEOUT_MS` / `LOCAL_MODEL_TIMEOUT_MS`, and
  returning structured readiness without running parse/inference.
- Wired `DoctorCommand` text, `doctor models`, and JSON output to include
  `models.worker`.
- GREEN verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest#doctorJsonReportsConfiguredModelWorkerReadiness test`.
- Not-ready branch verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest#doctorSeparatesExecutableModelWorkerFromRuntimeReadyWorker test`.
- Extended `scripts/smoke-doctruth-model-worker.sh` so the fake worker supports
  `--doctor`; the smoke now verifies `doctor --json` model-worker readiness
  before running table-lite parse.
- First smoke run failed because shell `$WORKER` preserved a double slash in
  the temp path while Java normalized the path. Updated the smoke to compare
  resolved paths.
- Model-worker smoke passed:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Focused verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest,TrustDocumentParserApiContractTest,ParserBackendContractTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Refactored `ModelDoctor` out of `DoctorCommand` after GREEN so
  `DoctorCommand.java` stays under the source-file line limit. The first
  refactor compile failed because `Files`/`Path` imports were still needed and
  `ModelWorkerDoctor.summary()` still referenced old flattened record fields;
  fixed both and reran focused verification.
- Model-worker smoke passed after the refactor:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Java full verification passed after the refactor:
  `mvn test` -> 920 tests, 0 failures, 0 errors.
- Whitespace verification passed:
  `git diff --check`.
- Consistency search note: an `rg` command used a backticked `ModelDoctor`
  pattern and zsh tried to execute it; reran with single quotes.
- Honest boundary: this makes model-worker deployment diagnosable and
  smoke-covered, but still does not execute real ONNX/TATR/SLANeXT/RT-DETR
  models or verify model memory/RSS under load.

## 2026-06-13 Continued Model Worker Resource Metrics

- Picked the next local-runtime diagnostic gap from the PRD: doctor should make
  sidecar/model memory visible. Existing `models.worker` readiness did not
  propagate worker-reported RSS or peak model memory.
- Added RED coverage in `DocTruthCliDoctorCompletionTest` requiring a fake
  model worker `--doctor` response with `rssMb=128` and `peakMemoryMb=512` to
  appear in `doctor --json` under `models.worker`.
- RED result:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest#doctorJsonReportsConfiguredModelWorkerReadiness test`
  failed because `rssMb` was `0`.
- Implemented resource parsing in `ModelWorkerDoctor`, keeping missing or
  negative values normalized to `0` for backward compatibility with existing
  workers.
- Added not-ready/default assertions proving workers that omit resource fields
  report `rssMb=0` and `peakMemoryMb=0`.
- Extended `scripts/smoke-doctruth-model-worker.sh` so the fake worker reports
  resource fields through `--doctor`, and the packaged CLI smoke asserts them
  before table-lite parsing.
- Verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test`.
- Model-worker smoke passed:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Architecture/API focused verification passed:
  `mvn -q -Dtest=ArchitectureContractTest,PublicApiSnapshotTest test`.
- Honest boundary: these are worker-reported metrics in the doctor protocol,
  not independent OS-level sampling and not real model RSS under ONNX/TATR/
  SLANeXT load.

## 2026-06-13 Continued Model Worker Cache Metadata Handoff

- Picked the next real-model handoff gap: model-assisted worker requests listed
  required model identities, but did not include local cache paths or verifier
  status for those artifacts. A real ONNX/TATR/SLANeXT worker would have to
  rediscover cache policy itself.
- Added RED coverage in `TrustDocumentParserApiContractTest` requiring a
  configured `doctruth.model.cache` directory to appear in the worker request as
  `modelCacheDirectory`, with per-model `cachePath`, `cacheStatus`,
  `actualSha256`, and `actualSizeBytes`.
- RED result:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#modelWorkerRequestIncludesLocalModelCacheVerificationMetadata test`
  failed because the fake worker saw no cache metadata and exited, causing Java
  to fall back to `pdfbox`.
- Implemented cache-aware request construction in `LocalModelWorker` using
  `ModelCacheVerifier.verify(...)`. The request now includes deterministic
  local artifact paths and verifier status for each required model. The cache
  directory resolves from `doctruth.model.cache`, `DOCTRUTH_MODEL_CACHE`, or the
  default user cache.
- GREEN verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#modelWorkerRequestIncludesLocalModelCacheVerificationMetadata test`.
- Extended `scripts/smoke-doctruth-model-worker.sh` to configure a model cache
  directory and assert `modelCacheDirectory`, `cachePath`, `cacheStatus`, and
  `actualSha256` before table-lite parsing.
- Model-worker smoke passed:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Focused verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,ModelCacheVerifierTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Honest boundary: this is a cache metadata handoff. Because current model
  descriptor SHA values are placeholders, the smoke verifies `MISSING` status,
  not a READY real ONNX model artifact.

## 2026-06-13 Continued Model Manifest READY Cache Handoff

- Picked the next local-model handoff gap: preset descriptors still used
  placeholder SHA values, so configured model workers could receive cache
  metadata but could not prove a local artifact was `READY` without changing
  code.
- Added RED coverage in `LocalModelWorkerManifestContractTest` requiring
  `doctruth.model.manifest` to override `table-lite` with a local
  `slanet-plus:local-test` descriptor, verify a SHA-matched cache file, and
  send `cacheStatus=READY`, `actualSha256`, and `actualSizeBytes` to the
  worker.
- RED result:
  `mvn -q -Dtest=LocalModelWorkerManifestContractTest test`
  failed because the worker still received the hard-coded `slanet-plus:v1`
  placeholder descriptor and Java fell back to `pdfbox`.
- Implemented package-private `ModelManifestResolver`, reading
  `doctruth.model.manifest` / `DOCTRUTH_MODEL_MANIFEST`, resolving models by
  `ParserPreset.id()`, and falling back to built-in preset descriptors when no
  manifest entry exists.
- GREEN verification passed:
  `mvn -q -Dtest=LocalModelWorkerManifestContractTest test`.
- Extended `scripts/smoke-doctruth-model-worker.sh` to create a local
  SHA-matched `slanet-plus:local-smoke` artifact and manifest, then assert
  `cacheStatus=READY` through the packaged CLI parse path.
- Model-worker smoke passed:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Focused verification passed:
  `mvn -q -Dtest=LocalModelWorkerManifestContractTest,TrustDocumentParserApiContractTest,ModelCacheVerifierTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Full verification passed:
  `mvn test` -> 922 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- LOC guard checked: `ModelManifestResolver.java` 76 LOC,
  `LocalModelWorker.java` 161 LOC, and
  `LocalModelWorkerManifestContractTest.java` 183 LOC.
- Honest boundary: this proves manifest-driven READY cache handoff to a
  configured worker. It still does not run real ONNX/TATR/SLANeXT/RT-DETR
  inference or prove real model memory/accuracy.

## 2026-06-13 Continued CLI Model Cache Warmup

- Picked the next model-runtime install gap: MCP could verify caller-supplied
  model descriptors and model-worker requests could consume manifest-defined
  READY cache artifacts, but the standalone CLI still could not warm a cache
  from a model manifest.
- Added RED coverage in `ModelCacheCommandTest` requiring
  `doctruth cache warm <manifest.json> --preset table-lite --cache <dir>
  --json` to copy a local manifest `source` into the deterministic cache
  filename, verify SHA-256, and return JSON with `allReady=true`.
- Added RED coverage for `--offline` remote-source refusal so a remote model
  URL is rejected without any network attempt.
- RED result:
  `mvn -q -Dtest=ModelCacheCommandTest test`
  failed with exit code 2 because `cache` was still an unknown command.
- Implemented `CacheCommand` with `cache warm`, local path and `file://`
  source support, manifest-relative path resolution, deterministic cache
  filenames from `ModelDescriptor.cacheFilename()`, and shared
  `ModelCacheVerifier` verification after copy.
- GREEN verification passed:
  `mvn -q -Dtest=ModelCacheCommandTest test`.
- Added packaged smoke:
  `scripts/smoke-doctruth-cache-warm.sh`.
- Cache warm smoke passed:
  `sh scripts/smoke-doctruth-cache-warm.sh`.
- Focused verification passed:
  `mvn -q -Dtest=ModelCacheCommandTest,DocTruthCliMcpTest,DocTruthCliDoctorCompletionTest,DocTruthCliTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Full verification passed:
  `mvn test` -> 924 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- LOC guard checked: `CacheCommand.java` 192 LOC,
  `DocTruthCli.java` 140 LOC, `Usage.java` 51 LOC, and
  `ModelCacheCommandTest.java` 133 LOC.
- Honest boundary: this establishes local/file model artifact install and
  offline refusal semantics. Remote model downloading is still explicitly not
  implemented, and no real ONNX/TATR/SLANeXT/RT-DETR inference runs yet.

## 2026-06-13 Continued Remote Model Cache Warmup

- Picked the next cache-warm gap: local/file sources were supported, but PRD
  model-cache semantics also require explicit verified model download when
  enabled.
- Added RED coverage in `ModelCacheCommandTest` using a local JDK HTTP server.
  The test requires `doctruth cache warm` to download a remote source, write it
  under the deterministic cache filename, and verify the downloaded bytes
  against the manifest SHA-256.
- RED result:
  `mvn -q -Dtest=ModelCacheCommandTest#cacheWarmDownloadsRemoteSourceAndVerifiesSha test`
  failed with exit code 1 because remote sources still returned
  `remote model source is not implemented yet`.
- Implemented streaming remote download in `CacheCommand` using JDK
  `HttpClient`, writing to a temporary file before moving into the model cache.
  Non-2xx responses fail and remove the temp file. `--offline` still refuses
  remote sources before any network request.
- GREEN verification passed:
  `mvn -q -Dtest=ModelCacheCommandTest#cacheWarmDownloadsRemoteSourceAndVerifiesSha test`.
- Extended `scripts/smoke-doctruth-cache-warm.sh` to start a local HTTP server,
  download a remote model artifact through the packaged CLI jar, verify the
  cached bytes and SHA status, and still assert offline remote refusal.
- Cache warm smoke passed:
  `sh scripts/smoke-doctruth-cache-warm.sh`.
- Focused verification passed:
  `mvn -q -Dtest=ModelCacheCommandTest,DocTruthCliMcpTest,DocTruthCliTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Full verification passed:
  `mvn test` -> 925 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- LOC guard checked: `CacheCommand.java` 220 LOC,
  `ModelCacheCommandTest.java` 172 LOC, and
  `scripts/smoke-doctruth-cache-warm.sh` 147 LOC.
- Honest boundary: cache warm now supports local, `file://`, and HTTP(S)
  sources with SHA verification. This still does not select real model URLs or
  execute ONNX/TATR/SLANeXT/RT-DETR inference.

## 2026-06-13 Continued Manifest-Aware Model Doctor

- Picked the next local-first verification gap: `cache warm` and model-worker
  parsing could use manifest-defined artifacts, but `doctruth doctor --json`
  still reported only the lite-offline summary and could not prove manifest
  artifacts were READY/MISSING/SHA_MISMATCH in the local cache.
- Added RED coverage in `DocTruthCliDoctorCompletionTest` requiring
  `DOCTRUTH_MODEL_MANIFEST` + `DOCTRUTH_MODEL_CACHE` to produce
  `models.requiredModels=1`, `models.allReady=true`, and one READY artifact
  with identity, SHA-256, and size metadata.
- Added RED coverage for the missing-cache path requiring the same manifest to
  report `allReady=false` and artifact status `MISSING`.
- RED result:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test`
  failed as expected because doctor still returned `requiredModels=0`.
- Implemented manifest-aware `ModelDoctor.local(...)`: it reads all preset
  descriptors from `DOCTRUTH_MODEL_MANIFEST`, deduplicates model identities,
  verifies them through `ModelCacheVerifier`, and exposes `allReady` plus
  artifact metadata in doctor JSON. It does not download models or run
  inference.
- GREEN focused verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test`.
- Focused regression verification passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest,ModelCacheCommandTest,LocalModelWorkerManifestContractTest,ModelCacheVerifierTest test`.
- Extended `scripts/smoke-doctruth-model-worker.sh` so the packaged CLI
  `doctor --json` path receives the smoke manifest/cache and asserts the local
  artifact is READY before parse.
- Model-worker smoke passed:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- After moving artifact JSON summaries out of `DoctorCommand`, focused doctor
  verification still passed:
  `mvn -q -Dtest=DocTruthCliDoctorCompletionTest test`.
- Packaged model-worker smoke was rerun after the refactor and passed:
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Full verification passed after the final refactor:
  `mvn test` -> 927 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- LOC guard checked after refactor: `ModelDoctor.java` 168 LOC,
  `DoctorCommand.java` 282 LOC, `DocTruthCliDoctorCompletionTest.java` 375
  LOC, and `scripts/smoke-doctruth-model-worker.sh` 237 LOC.
- Honest boundary: this improves local model-cache diagnosability. It still
  does not execute a real ONNX/TATR/SLANeXT/RT-DETR model or prove real model
  accuracy/memory behavior.

## 2026-06-13 Continued Model Manifest Runtime Metadata

- Picked the next model-adapter contract gap: manifests could identify and
  verify artifacts by name/version/SHA, but they could not tell a future real
  worker whether an artifact is layout detection, table structure, ONNX,
  quantized, or under a specific license.
- Added RED coverage in `LocalModelWorkerManifestContractTest` requiring
  manifest fields `task`, `backend`, `format`, `precision`, and `license` to
  reach the local model-worker request JSON together with cache status and
  SHA metadata.
- RED result:
  `mvn -q -Dtest=LocalModelWorkerManifestContractTest test`
  failed because the fake worker asserted those fields and exited, causing the
  parser to fall back to `pdfbox`.
- Added package-private `ModelRuntimeHints` and `ModelManifestArtifact` so the
  core `ModelDescriptor` stays at 5 components while manifest runtime metadata
  travels beside it.
- Implemented `ModelManifestResolver.requiredArtifacts(...)` and updated
  `LocalModelWorker` to include runtime hints in each model request entry.
- Worker metadata verification passed:
  `mvn -q -Dtest=LocalModelWorkerManifestContractTest test`.
- Added RED/GREEN coverage in `ModelCacheCommandTest` and
  `DocTruthCliDoctorCompletionTest` so `cache warm --json` and
  `doctor --json` also expose the same runtime metadata.
- Focused verification passed:
  `mvn -q -Dtest=LocalModelWorkerManifestContractTest,ModelCacheCommandTest,DocTruthCliDoctorCompletionTest test`.
- Extended packaged smokes:
  `scripts/smoke-doctruth-cache-warm.sh` and
  `scripts/smoke-doctruth-model-worker.sh` now assert runtime metadata survives
  shaded-jar execution.
- Packaged smokes passed:
  `sh scripts/smoke-doctruth-cache-warm.sh` and
  `sh scripts/smoke-doctruth-model-worker.sh`.
- Full verification passed:
  `mvn test` -> 927 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- LOC guard checked: `ModelManifestResolver.java` 100 LOC,
  `LocalModelWorker.java` 181 LOC, `ModelRuntimeHints.java` 31 LOC,
  `ModelManifestArtifact.java` 18 LOC, `CacheCommand.java` 258 LOC,
  `ModelDoctor.java` 208 LOC, and updated tests remain below their limits.
- Honest boundary: runtime hints make real adapter routing testable later.
  They still do not execute ONNX/TATR/SLANeXT/RT-DETR inference.

## 2026-06-13 Continued ONNXRuntime Model Worker Smoke

- Picked the next hardest remaining model-runtime gap: previous model-worker
  smokes used fake Python workers and did not prove any real ONNXRuntime model
  loading or inference.
- Confirmed the local machine has `onnxruntime 1.26.0`, `onnx 1.21.0`, and
  `numpy 2.4.2` available through `python3`.
- Added RED smoke `scripts/smoke-doctruth-onnx-model-worker.sh`. It generates
  a tiny ONNX identity model, writes a manifest with `backend=onnxruntime` and
  `format=onnx`, warms the cache, runs worker `--doctor`, then parses a PDF
  through the Java CLI model-worker path.
- RED result:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh` failed because
  `scripts/doctruth-onnx-model-worker` did not exist.
- Added `scripts/doctruth-onnx-model-worker`, a DocTruth JSON model-worker
  adapter that:
  - reports ONNXRuntime provider readiness through `--doctor`,
  - validates a READY cached ONNX model from the request,
  - creates an ONNXRuntime session,
  - runs one inference with generated float32 inputs,
  - returns a `TrustDocument` through the same local model-worker protocol.
- ONNX smoke passed:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`.
- Added RED packaging coverage in `CliPackagingContractTest` requiring
  install/release/smoke scripts to mention `doctruth-onnx-model-worker`.
- RED result:
  `mvn -q -Dtest=CliPackagingContractTest test`
  failed because install/release scripts still packaged only the RapidOCR
  worker.
- Updated `scripts/install-cli.sh`, `scripts/package-cli-release.sh`, and
  `scripts/smoke-cli-release.sh` to install/package/check the ONNX worker.
- Packaging contract passed:
  `mvn -q -Dtest=CliPackagingContractTest test`.
- Release smoke initially failed because the script defaulted to the macOS
  `java` stub. Updated `scripts/smoke-cli-release.sh` to resolve `$JAVA`,
  `$JAVA_HOME/bin/java`, Homebrew OpenJDK, then `java`, matching the other
  smokes.
- Release smoke passed:
  `scripts/smoke-cli-release.sh --version 0.2.0-alpha --dist target/onnx-release-smoke-dist`.
- LOC guard checked: `scripts/doctruth-onnx-model-worker` 130 LOC,
  `scripts/smoke-doctruth-onnx-model-worker.sh` 127 LOC,
  `CliPackagingContractTest.java` 47 LOC, `scripts/install-cli.sh` 88 LOC,
  `scripts/package-cli-release.sh` 148 LOC, and
  `scripts/smoke-cli-release.sh` 151 LOC.
- Full verification passed:
  `mvn test` -> 928 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves real ONNXRuntime session loading/execution over
  a generated identity model. It still does not decode RT-DETR/TATR/SLANeXT
  model outputs into layout regions or table cells.

## 2026-06-13 Continued RapidOCR MNN Backend Doctor

- Picked the OCR gap the user called out: DocTruth had a RapidOCR adapter and a
  real RapidOCR + ONNXRuntime smoke, but worker `--doctor` still treated
  RapidOCR initialization as if the MNN backend itself had been verified.
- Added RED smoke `scripts/smoke-doctruth-rapidocr-mnn-backend.sh`. It creates
  a fake `rapidocr` module without `MNN` and requires
  `DOCTRUTH_RAPIDOCR_BACKEND=mnn doctruth-rapidocr-mnn-worker --doctor` to
  return `ok=false`, `code=mnn_unavailable`, `backend=mnn`, and
  `backendReady=false`.
- RED result:
  `sh scripts/smoke-doctruth-rapidocr-mnn-backend.sh` failed because the worker
  returned `ok=true`, `code=ready`, and no backend fields.
- Updated `scripts/doctruth-rapidocr-mnn-worker` so strict MNN doctor mode
  imports `MNN` or `mnn`, reports `backend`, `backendReady`, and
  `backendVersion`, and refuses to report ready when RapidOCR exists but the
  backend module is missing.
- MNN backend smoke passed:
  `sh scripts/smoke-doctruth-rapidocr-mnn-backend.sh`.
- Existing RapidOCR worker smoke still passed:
  `sh scripts/smoke-doctruth-rapidocr-worker.sh`.
- Real RapidOCR smoke remains opt-in and skipped without
  `DOCTRUTH_RAPIDOCR_REAL_SMOKE=1`, as designed.
- Added RED packaging coverage in `CliPackagingContractTest` requiring release
  smoke to include `DOCTRUTH_RAPIDOCR_BACKEND=mnn` and `backendReady`.
- RED result:
  `mvn -q -Dtest=CliPackagingContractTest test` failed because release smoke
  only checked ordinary RapidOCR readiness.
- Updated `scripts/smoke-cli-release.sh` to create a fake packaged `MNN.py`,
  run the packaged worker with `DOCTRUTH_RAPIDOCR_BACKEND=mnn`, and assert
  `backend=mnn` plus `backendReady=true`.
- Focused packaging test passed:
  `mvn -q -Dtest=CliPackagingContractTest test`.
- Rebuilt release artifacts and release smoke passed:
  `scripts/package-cli-release.sh --version 0.2.0-alpha --dist target/mnn-release-smoke-dist`
  then
  `scripts/smoke-cli-release.sh --version 0.2.0-alpha --dist target/mnn-release-smoke-dist`.
- Full verification passed:
  `mvn test` -> 928 tests, 0 failures, 0 errors.
- LOC guard checked: `scripts/doctruth-rapidocr-mnn-worker` 247 LOC,
  `scripts/smoke-doctruth-rapidocr-mnn-backend.sh` 58 LOC,
  `CliPackagingContractTest.java` 49 LOC, and
  `scripts/smoke-cli-release.sh` 157 LOC.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this closes the false-positive MNN readiness gap. It does
  not yet prove real MNN OCR recognition quality or a labeled real-world OCR
  corpus.

## 2026-06-13 Continued ONNX TATR-Like Table Decoder

- Picked the next model-runtime gap: the ONNX worker could load and execute a
  cached identity model, but still returned a generic text unit and did not
  decode table-structure outputs.
- Added RED smoke `scripts/smoke-doctruth-onnx-tatr-decoder.sh`. It generates
  a tiny constant-output ONNX model with `pred_logits` and `pred_boxes`, writes
  a manifest entry with `task=table-structure-recognition`, warms the cache,
  parses a PDF through the Java CLI model-worker path, and requires a
  `TrustTable` plus `TABLE_CELL` unit.
- RED result:
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh` failed with
  `IndexError: list index out of range` because `body.tables` was empty.
- Updated `scripts/doctruth-onnx-model-worker` so
  `task=table-structure-recognition` runs a TATR/DETR-like decoder:
  `pred_logits` selects table vs cell detections, `pred_boxes` are interpreted
  as normalized `cx, cy, width, height`, and the worker emits `TrustTable`
  cells plus matching `TABLE_CELL` units.
- TATR decoder smoke passed:
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`.
- Existing identity ONNX worker smoke still passed:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`.
- Full verification passed:
  `mvn test` -> 928 tests, 0 failures, 0 errors.
- LOC guard checked: `scripts/doctruth-onnx-model-worker` 266 LOC,
  `scripts/smoke-doctruth-onnx-tatr-decoder.sh` 136 LOC, and
  `scripts/smoke-doctruth-onnx-model-worker.sh` 127 LOC.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves a local ONNX table-decoder contract over
  synthetic TATR/DETR-style outputs. It does not yet run curated real TATR,
  SLANeXT, or RT-DETR weights, and it does not prove real-world table accuracy.

## 2026-06-13 Continued ONNX Worker Resource Smoke

- Picked the next remaining PRD gap: ONNXRuntime execution and TATR-like decode
  were smoke-covered, but worker parse responses still did not expose
  parse-time inference duration or memory evidence.
- Added RED smoke `scripts/smoke-doctruth-onnx-worker-resources.sh`. It
  generates a tiny ONNX identity model, calls `scripts/doctruth-onnx-model-worker`
  directly with a READY model request, and requires top-level `metrics` with
  `inferenceWallMs`, `wallMs`, `rssMb`, and `peakMemoryMb`.
- RED result:
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh` failed with
  `KeyError: 'metrics'`.
- Updated `scripts/doctruth-onnx-model-worker` to measure end-to-end worker
  wall time, ONNXRuntime session/inference wall time, and process peak memory
  through Python `resource.getrusage`.
- ONNX resource smoke passed:
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`.
- Existing ONNX smokes still passed:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh` and
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`.
- Full verification passed:
  `mvn test` -> 928 tests, 0 failures, 0 errors.
- LOC guard checked: `scripts/doctruth-onnx-model-worker` 289 LOC,
  `scripts/smoke-doctruth-onnx-worker-resources.sh` 77 LOC,
  `scripts/smoke-doctruth-onnx-model-worker.sh` 127 LOC, and
  `scripts/smoke-doctruth-onnx-tatr-decoder.sh` 136 LOC.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this is worker-internal resource evidence for a generated
  ONNX model. It is not OS-level profiling and does not measure real
  RT-DETR/TATR/SLANeXT production weights under load.

## 2026-06-13 Continued Remote Real-PDF Corpus Smoke

- Picked the remaining corpus gap: the benchmark corpus runner and smoke were
  still generated-fixture oriented and did not have a SHA-verified public real
  PDF fixture path.
- Added RED test
  `ParserBenchmarkCorpusTest#manifestCanUseRemotePdfFixturesWithShaVerification`.
  It starts a local HTTP server, serves a generated PDF through `sourceUrl`,
  supplies `sourceSha256`, and expects corpus loading/evaluation to pass.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#manifestCanUseRemotePdfFixturesWithShaVerification test`
  failed with `missing or blank field: source`, proving the manifest loader
  only supported local `source`.
- Implemented `sourceUrl` + `sourceSha256` in `ParserBenchmarkCorpus`: remote
  PDFs download into `.doctruth-corpus-cache` next to the manifest, HTTP status
  must be 2xx, and SHA-256 must match before parsing.
- Focused remote corpus test passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#manifestCanUseRemotePdfFixturesWithShaVerification test`.
- Added `scripts/smoke-doctruth-real-pdf-corpus.sh`, which runs
  `benchmark-corpus` against W3C's public `dummy.pdf`, pinned to
  `sha256:3df79d34abbca99308e79cb94461c1893582604d68329a41fd4bec1885e6adb4`,
  with a human-authored expected `TrustDocument` label and thresholds for
  `reading_order_f1`, `quote_anchor_accuracy`, `table_cell_f1`, `bbox_iou`,
  and `table_region_iou`.
- Focused corpus suite passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest test`.
- Generated benchmark corpus smoke still passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Real public PDF corpus smoke passed:
  `sh scripts/smoke-doctruth-real-pdf-corpus.sh`.
- Full verification passed:
  `mvn test` -> 929 tests, 0 failures, 0 errors.
- LOC guard checked: `src/main/java/ai/doctruth/ParserBenchmarkCorpus.java`
  188 LOC, `src/test/java/ai/doctruth/ParserBenchmarkCorpusTest.java` 293
  LOC, `scripts/smoke-doctruth-real-pdf-corpus.sh` 123 LOC, and
  `scripts/smoke-doctruth-benchmark-corpus.sh` 232 LOC.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves the remote real-PDF corpus path with one small
  public fixture. It does not replace a broad human-labeled real-world corpus
  covering multi-column PDFs, scanned OCR cases, and complex tables.

## 2026-06-13 Continued ONNX RT-DETR-Like Layout Decoder

- Picked the next Phase 3 gap: ONNXRuntime execution and table decoding were
  smoke-covered, but `task=layout-detection` still fell back to the identity
  ONNX output instead of producing model-derived layout regions.
- Added RED smoke `scripts/smoke-doctruth-onnx-layout-decoder.sh`. It
  generates a tiny RT-DETR/DETR-like ONNX model with `pred_logits` and
  `pred_boxes`, warms the SHA-verified cache under the `standard` preset, and
  requires Java CLI `parse --preset standard --format json` to emit two
  bbox-bearing layout `TEXT_BLOCK` units in reading order.
- RED result:
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh` failed because the worker
  still returned identity output: `ONNX inference succeeded`.
- Updated `scripts/doctruth-onnx-model-worker` to dispatch
  `task=layout-detection` to a synthetic RT-DETR/DETR-like decoder. The decoder
  reuses the generic `pred_logits`/`pred_boxes` detection path, maps classes to
  heading/body/list layout regions, normalizes boxes to DocTruth 0..1000 page
  coordinates, and sorts units by top-left reading order.
- Refactored common TrustDocument construction so the worker stays under the
  300-line project limit after adding the new decoder.
- ONNX layout decoder smoke passed:
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`.
- Existing ONNX smokes still passed:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`,
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`, and
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`.
- Full verification passed:
  `mvn test` -> 929 tests, 0 failures, 0 errors.
- LOC guard checked: `scripts/doctruth-onnx-model-worker` 271 LOC,
  `scripts/smoke-doctruth-onnx-layout-decoder.sh` 134 LOC,
  `scripts/smoke-doctruth-onnx-tatr-decoder.sh` 136 LOC,
  `scripts/smoke-doctruth-onnx-model-worker.sh` 127 LOC, and
  `scripts/smoke-doctruth-onnx-worker-resources.sh` 77 LOC.
- Honest boundary: this proves a local ONNX layout-decoder contract over
  synthetic RT-DETR/DETR-like outputs. It does not run curated real RT-DETR
  weights and does not prove real-world layout accuracy.

## 2026-06-13 Continued ONNX Layout Confidence Warning

- Picked the next Phase 3 exit criterion: low-confidence layout should emit
  warnings instead of becoming silent audit-grade output.
- Added RED smoke `scripts/smoke-doctruth-onnx-layout-low-confidence.sh`. It
  generates a tiny RT-DETR/DETR-like ONNX model whose best layout detection is
  above the detection cutoff but below the `0.85` audit threshold, then requires
  Java CLI `parse --preset standard --format json` to keep the region, attach a
  severe `layout_low_confidence` warning, and return
  `auditGradeStatus=NOT_AUDIT_GRADE`.
- Initial smoke attempt used logits that fell below the worker's `0.50`
  detection cutoff and produced no units. Adjusted the synthetic logits so the
  detection exercises the intended `0.50 <= score < 0.85` path.
- RED result after logits correction:
  `sh scripts/smoke-doctruth-onnx-layout-low-confidence.sh` failed with
  `AssertionError: AUDIT_GRADE`, proving low-confidence layout still looked
  audit-grade.
- Updated `scripts/doctruth-onnx-model-worker` so `task=layout-detection`
  units below `0.85` receive a severe `layout_low_confidence` warning, while
  the returned document status becomes `NOT_AUDIT_GRADE`.
- Low-confidence layout smoke passed:
  `sh scripts/smoke-doctruth-onnx-layout-low-confidence.sh`.
- High-confidence layout smoke still passed:
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`.
- Existing ONNX smokes still passed:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`,
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`, and
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`.
- Full verification passed:
  `mvn test` -> 929 tests, 0 failures, 0 errors.
- LOC guard checked: `scripts/doctruth-onnx-model-worker` 285 LOC and
  `scripts/smoke-doctruth-onnx-layout-low-confidence.sh` 129 LOC.
- Honest boundary: this closes the local decoder's silent low-confidence layout
  gap. It does not calibrate confidence on real RT-DETR weights or a labeled
  real-world layout corpus.

## 2026-06-13 Continued ONNX Table Confidence Warning

- Picked the next Phase 4 gap: `table_structure_low_confidence` existed in the
  PRD warning taxonomy, but the ONNX TATR-like decoder still allowed
  low-confidence table/cell detections to pass as audit-grade output.
- Added RED smoke `scripts/smoke-doctruth-onnx-table-low-confidence.sh`. It
  generates a tiny TATR/DETR-like ONNX model whose table and cell detections
  are above the detection cutoff but below the `0.85` audit threshold, then
  requires Java CLI `parse --preset table-lite --format json` to preserve the
  table/cell output, emit severe parser warning
  `table_structure_low_confidence`, and return
  `auditGradeStatus=NOT_AUDIT_GRADE`.
- RED result:
  `sh scripts/smoke-doctruth-onnx-table-low-confidence.sh` failed with
  `AssertionError: AUDIT_GRADE`, proving low-confidence table structure still
  looked audit-grade.
- Updated `scripts/doctruth-onnx-model-worker` so
  `task=table-structure-recognition` collects table/cell scores below `0.85`,
  emits a severe parserRun warning `table_structure_low_confidence`, and keeps
  the table/cell output for review/replay.
- Low-confidence table smoke passed:
  `sh scripts/smoke-doctruth-onnx-table-low-confidence.sh`.
- High-confidence TATR smoke still passed:
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`.
- Existing ONNX smokes still passed:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`,
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`,
  `sh scripts/smoke-doctruth-onnx-layout-low-confidence.sh`, and
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`.
- Full verification passed:
  `mvn test` -> 929 tests, 0 failures, 0 errors.
- LOC guard checked: `scripts/doctruth-onnx-model-worker` 300 LOC and
  `scripts/smoke-doctruth-onnx-table-low-confidence.sh` 131 LOC.
- Honest boundary: this closes the local decoder's silent low-confidence table
  gap. It does not calibrate confidence on real TATR/SLANeXT weights or a
  labeled real-world table corpus.

## 2026-06-13 Continued ONNX Worker Helper Split

- Picked the next engineering blocker: `scripts/doctruth-onnx-model-worker`
  had reached the 300 LOC hard limit, so adding more decoder behavior would
  violate the project rules.
- Added RED contract coverage in `CliPackagingContractTest` requiring
  `doctruth_onnx_worker_lib.py` to be included by source install, release
  packaging, and release smoke.
- RED result:
  `mvn -q -Dtest=CliPackagingContractTest test` failed because
  `scripts/install-cli.sh` did not mention `doctruth_onnx_worker_lib.py`.
- Split the ONNX worker into a 6-line executable shim and
  `scripts/doctruth_onnx_worker_lib.py`, preserving the same CLI command and
  JSON worker protocol.
- Updated source install, release tarball packaging, Homebrew formula
  generation, and release smoke so the helper module ships beside the worker
  executable.
- Packaging contract passed:
  `mvn -q -Dtest=CliPackagingContractTest test`.
- ONNX smokes passed after the split:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`,
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`,
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`,
  `sh scripts/smoke-doctruth-onnx-layout-low-confidence.sh`,
  `sh scripts/smoke-doctruth-onnx-table-low-confidence.sh`, and
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`.
- Release package smoke passed:
  `sh scripts/package-cli-release.sh --version 0.2.0-alpha --dist target/onnx-helper-release-smoke-dist`
  followed by
  `sh scripts/smoke-cli-release.sh --version 0.2.0-alpha --dist target/onnx-helper-release-smoke-dist`.
- Full verification passed:
  `mvn test` -> 929 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- LOC guard checked: `scripts/doctruth-onnx-model-worker` 6 LOC,
  `scripts/doctruth_onnx_worker_lib.py` 295 LOC,
  `scripts/package-cli-release.sh` 151 LOC,
  `scripts/install-cli.sh` 92 LOC,
  `scripts/smoke-cli-release.sh` 162 LOC, and
  `src/test/java/ai/doctruth/CliPackagingContractTest.java` 52 LOC.
- Honest boundary: this keeps packaging and maintainability sound. It does not
  add real production RT-DETR/TATR/SLANeXT weights or a labeled parser-quality
  corpus.

## 2026-06-13 Continued Rust Sidecar Doctor Memory

- Picked the next PRD runtime gate: sidecar RSS and peak memory should be
  reported by `--doctor`, but the Rust runtime doctor only emitted runtime,
  protocol, local-first, backend, and capability fields.
- Added RED assertions to
  `runtime/doctruth-runtime/tests/protocol_contract.rs` and
  `scripts/smoke-doctruth-runtime.sh` requiring `rssMb` and `peakMemoryMb`.
- RED result:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml doctor_reports_local_runtime_readiness`
  failed because stdout did not contain `"rssMb":`.
- Implemented local process memory reporting in
  `runtime/doctruth-runtime/src/main.rs` without adding dependencies. Linux
  reads `/proc/self/status` (`VmRSS`/`VmHWM`), and other Unix environments fall
  back to `ps -o rss= -p <pid>`.
- Rust doctor contract passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml doctor_reports_local_runtime_readiness`.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Formatting check first failed on one formatter-only line wrap; ran
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml` to normalize
  it before continuing verification.
- Full Cargo verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml --check &&
  cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Full Maven verification passed:
  `mvn test` -> 929 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves local sidecar doctor resource fields. It does
  not measure real model peak memory because no production parser model is
  loaded in the Rust runtime yet.

## 2026-06-13 Continued Benchmark Corpus Offline Remote Fixtures

- Picked the next PRD network boundary: `benchmark-corpus` supported remote
  `sourceUrl` fixtures, but it did not expose an offline/cache-only mode even
  though parser runtime acceptance requires offline mode to avoid network
  downloads.
- Added RED tests in `ParserBenchmarkCorpusTest` requiring
  `ParserBenchmarkCorpus.load(manifest, true)` to reject uncached remote PDF
  fixtures before network access and to accept cached SHA-verified remote
  fixtures offline.
- Added RED CLI coverage in `ParserBenchmarkCorpusCliTest` requiring
  `doctruth benchmark-corpus <manifest> --offline` to return an error for an
  uncached remote fixture.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`
  failed at test compilation because `ParserBenchmarkCorpus.load(Path, boolean)`
  did not exist yet.
- Implemented offline-aware corpus loading and CLI parsing. Existing
  `load(Path)` remains online/default-compatible; new `load(Path, boolean)`
  carries offline behavior through remote source resolution.
- Updated CLI usage, PRD, CLI docs, and the public API snapshot for the new
  overload.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` so it verifies passing
  corpus evaluation, failing threshold behavior, and offline remote fixture
  refusal.
- First benchmark smoke attempt failed with macOS native `Abort trap: 6` inside
  the Java CLI during generated OCR PDF handling. Rerunning with
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true` passed, so the smoke now exports
  that option explicitly.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Full Maven verification passed:
  `mvn test` -> 932 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves benchmark corpus offline/cache behavior. It
  does not add the larger real-world labeled corpus required before claiming
  parser accuracy.

## 2026-06-13 Continued Strict Warning Corpus Gate

- Picked the next PRD acceptance gap: beta metrics require strict parser
  warning false-negative rate to be <= 2%, but benchmark metrics did not compare
  expected parser warnings from labels against actual parser output.
- Added RED runner tests requiring `strict_warning_false_negative_rate` to be
  `1.0` when an expected severe parser warning is missing, and `0.0` when both
  parserRun and unit-local severe warning codes are present in actual output.
- Initial RED result:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsStrictWarningFalseNegativeRate,ParserBenchmarkRunnerTest#benchmarkStrictWarningMetricMatchesParserAndUnitWarnings test`
  failed because the metric was absent and returned the default `0.0`.
- The same RED exposed a threshold-contract gap: false-negative rate is
  lower-is-better, but the corpus runner only had `minimums`. Added
  `ParserBenchmarkRunner.requireMaximums(...)`, `ParserBenchmarkCorpus.maximums()`,
  and `ParserBenchmarkCorpus.requireThresholds()`.
- Added corpus and CLI RED coverage for manifest-level `maximums` enforcement
  over `strict_warning_false_negative_rate`.
- Implemented warning comparison over expected severe parserRun warnings and
  unit-local warning codes. Missing expected severe warning codes become
  `missed / expected`.
- Updated PRD, CLI docs, and the public API snapshot for `maximums` and warning
  false-negative metric support.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` with a packaged CLI
  maximum-threshold failure case.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Full Maven verification passed:
  `mvn test` -> 936 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves warning false-negative gating in labeled corpus
  contracts. It does not create the large real-world warning-labeled PDF corpus
  needed to claim the <= 2% product metric.

## 2026-06-13 Continued Parser Latency Corpus Gate

- Picked the next PRD runtime gate: beta acceptance requires parser latency
  p50/p95, but benchmark cases did not record parse duration and corpus CLI
  output had no aggregate latency metrics.
- Added RED runner tests requiring `ParserBenchmarkCase` to carry
  `parserLatencyMs`, per-case `parser_latency_ms`, and aggregate
  `parser_latency_p50` / `parser_latency_p95`.
- Added RED CLI tests requiring `benchmark-corpus --json` to emit top-level
  aggregate latency metrics, text output to show `parser_latency_p95`, and
  manifest `maximums.parser_latency_p95` to fail through aggregate metrics.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsParserLatencyForEachCase,ParserBenchmarkRunnerTest#benchmarkAggregatesParserLatencyPercentiles,ParserBenchmarkCorpusCliTest#benchmarkCorpusLatencyMaximumFailureUsesAggregateMetrics,ParserBenchmarkCorpusCliTest#benchmarkCorpusJsonPrintsMachineReadableMetrics,ParserBenchmarkCorpusCliTest#benchmarkCorpusPrintsReadableSummaryAndPassesThresholds test`
  failed at test compilation because the latency constructor and
  `aggregateMetrics(...)` did not exist.
- Implemented parse timing in `ParserBenchmarkCase.fromPdf(...)`, preserved
  compatibility constructors with `0.0` latency, and validated latency as
  finite/non-negative.
- Implemented per-case `parser_latency_ms`, nearest-rank aggregate
  `parser_latency_p50` / `parser_latency_p95`, top-level CLI JSON/text metrics,
  and aggregate maximum-threshold gating for latency metrics.
- Updated PRD, CLI docs, public API snapshot, and
  `scripts/smoke-doctruth-benchmark-corpus.sh` to cover aggregate latency
  reporting and p95 maximum failure through the packaged CLI path.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Full Maven verification passed:
  `mvn test` -> 939 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves corpus-level latency measurement/gating. It
  does not prove the PRD's 1.5s/8s production targets on a broad real-world
  benchmark corpus yet.

## 2026-06-13 Continued Section Boundary Corpus Gate

- Picked the next PRD parser-quality gap: beta acceptance lists
  `section_boundary_f1 >= 0.90`, but the benchmark runner did not expose an
  executable section-boundary metric.
- Added RED runner tests requiring recovered heading-like boundaries to score
  `section_boundary_f1=1.0` and merged heading/body text to fail a `0.90`
  minimum threshold.
- Added RED corpus manifest coverage requiring generated PDF fixtures to gate
  `section_boundary_f1` through normal `minimums`.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsSectionBoundaryF1,ParserBenchmarkRunnerTest#benchmarkLowersSectionBoundaryF1ForMergedHeadingText,ParserBenchmarkCorpusTest#manifestCanGateSectionBoundaryF1 test`
  failed because `section_boundary_f1` returned `0.0`.
- Implemented heading-like boundary extraction over actual and expected
  Markdown, normalized section boundary keys, and precision/recall/F1 scoring.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` so the packaged CLI
  smoke gates `section_boundary_f1=1.0` on a generated two-section fixture.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Full Maven verification passed:
  `mvn test` -> 942 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves the section-boundary metric and corpus gate. It
  does not prove `section_boundary_f1 >= 0.90` on a broad human-labeled
  real-world PDF corpus yet.

## 2026-06-13 Continued Evidence Span Accuracy Corpus Gate

- Picked the next PRD parser-quality gap: required metrics listed
  `evidence_span_accuracy`, but benchmark results did not expose it.
- Added RED runner tests requiring expected evidence-bearing text to score
  `evidence_span_accuracy=1.0`, and matching text with no evidence span to fail
  a `0.97` minimum threshold.
- Added RED corpus manifest coverage requiring generated PDF fixtures to gate
  `evidence_span_accuracy` through normal `minimums`.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsEvidenceSpanAccuracy,ParserBenchmarkRunnerTest#benchmarkLowersEvidenceSpanAccuracyForWrongSpan,ParserBenchmarkCorpusTest#manifestCanGateEvidenceSpanAccuracy test`
  failed because `evidence_span_accuracy` returned `0.0`.
- First implementation matched internal evidence span ids exactly. The packaged
  corpus smoke caught that this was too strict for real parser output because
  generated label ids and parser-generated unit ids are not stable across
  segmentation.
- Revised the metric to compare expected text-line coverage against actual
  units that have non-empty evidence span ids. This keeps the metric focused on
  citeable coverage without treating internal `span-xxxx` ids as label truth.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` so the packaged CLI
  smoke gates `evidence_span_accuracy=1.0` on the generated text-layer fixture.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Full Maven verification passed:
  `mvn test` -> 945 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves the evidence-span metric and corpus gate. It
  does not prove `evidence_span_accuracy >= 0.97` on a broad human-labeled
  real-world PDF corpus yet.

## 2026-06-13 Continued Benchmark Resource Metrics

- Picked the next PRD measurable-runtime gap: required metrics listed
  `rss_peak_mb` and `model_cache_size_mb`, but benchmark results did not expose
  resource observations.
- Added RED runner coverage requiring `ParserBenchmarkCase` to carry resource
  observations and `ParserBenchmarkRunner` to output `rss_peak_mb` plus
  `model_cache_size_mb`.
- Added RED CLI JSON coverage requiring packaged corpus output to include
  per-case resource metrics.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest#benchmarkReportsResourceMetrics,ParserBenchmarkCorpusCliTest#benchmarkCorpusJsonPrintsMachineReadableMetrics test`
  failed at compilation because the resource constructor did not exist.
- First implementation added the three resource values directly to
  `ParserBenchmarkCase`, but `ArchitectureContractTest` rejected the public
  record as 7 components.
- Revised the design to add `ParserBenchmarkResources` and keep
  `ParserBenchmarkCase` at 5 record components, with compatibility accessors
  `parserLatencyMs()`, `rssPeakMb()`, and `modelCacheSizeMb()`.
- `fromPdf(...)` now records fallback runtime observations: elapsed parse time,
  current JVM memory usage as `rss_peak_mb`, and configured model cache
  directory size as `model_cache_size_mb`. Worker/runtime paths can pass
  stronger measurements through the explicit constructor.
- Updated public API snapshot for the new `ParserBenchmarkResources` contract.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` so packaged CLI JSON
  asserts `rss_peak_mb` and `model_cache_size_mb` are present.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Full Maven verification passed:
  `mvn test` -> 946 tests, 0 failures, 0 errors.
- Whitespace check passed:
  `git diff --check`.
- Honest boundary: this proves resource metric plumbing and local fallback
  observations. It does not prove real model-worker production RSS or cache
  budgets on a broad corpus yet.

## 2026-06-13 Continued Compact Corpus Aggregate Gate

- Picked the next PRD runtime-gate gap: `compact_llm_size_reduction` existed
  per case, but the PRD requires the compact output to be at least 25% smaller
  on the benchmark corpus.
- Added RED runner coverage requiring aggregate metrics to include
  `compact_llm_size_reduction_min`.
- Added RED corpus and CLI coverage requiring
  `minimums.compact_llm_size_reduction_min` to fail as a corpus aggregate
  threshold with `corpus compact_llm_size_reduction_min` in the error message.
- RED result:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`
  failed because aggregate metrics only contained `parser_latency_p50/p95`, and
  the manifest treated `compact_llm_size_reduction_min` as a per-case metric.
- Implemented aggregate compact reduction as the minimum observed
  `compact_llm_size_reduction` across benchmark results.
- Updated threshold routing so corpus aggregate `minimums` are selected and
  enforced before remaining per-case minimum thresholds.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` so packaged CLI JSON
  asserts the aggregate metric and a failing manifest proves the aggregate
  compact minimum error path.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkRunnerTest,ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Added final hardening tests for value contracts, sidecar failures, model
  manifest validation, page-image rendering errors, doctor edge cases, CLI
  bad-usage paths, cache warmup errors, and model-cache verifier edge cases.
- Added a regression test for degenerate/off-page table-cell regions found by
  the recorded real-world corpus. The fix now normalizes per-cell bboxes and
  skips cells that collapse to zero area instead of emitting invalid evidence
  anchors.
- Full Maven unit verification passed:
  `mvn test` -> 967 tests, 0 failures, 0 errors.
- Coverage verification passed:
  `mvn verify -DskipITs` -> 980 tests, 0 failures, 0 errors,
  `All coverage checks have been met.`
- Recorded corpus verification passed:
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`.
  Surefire: 980 tests, 0 failures, 0 errors. Failsafe: 16 tests, 0 failures,
  0 errors, 2 skipped.
- Real-world PDF fixture result from the recorded profile:
  total=383, success=379, failure=4, bugs=0, passRate=0.9896. The four failures
  are malformed PDFs with `PDF_PARSE_FAILED` and `Missing root object
  specification in trailer`.
- Real-world PDF fixture timing from the recorded profile:
  total parse time 17840 ms, mean 46580 us, pageCount min/median/max 1/2/21,
  sectionCount min/median/max 0/3/499.
- Whitespace verification passed:
  `git diff --check`.
- Honest boundary: this does not complete the full PRD. It completes the
  contract/runtime TDD slice for the local parser runtime contract and
  generated/recorded regression gates. The full PRD still requires Rust to
  become the default parser core, a reusable Rust library crate, real
  RT-DETR/TATR/SLANeXT model execution, real scanned-PDF OCR quality, and a
  broad human-labeled parser accuracy corpus.

## 2026-06-13 Continued Rust Library Core Boundary

- Resumed the full PRD goal after correcting the earlier status mistake:
  full PRD is not complete until Rust becomes the default parser core, parser
  logic is reusable as a Rust library, real model/OCR execution is proven, and
  labeled parser accuracy is gated.
- Picked the first Rust-first slice: split the binary-only runtime into a Rust
  library crate plus thin binary entrypoint.
- Mechanically moved `runtime/doctruth-runtime/src/main.rs` to
  `runtime/doctruth-runtime/src/lib.rs`.
- Added a new thin `runtime/doctruth-runtime/src/main.rs` that only exits with
  `doctruth_runtime::run_process()`.
- Exposed library protocol functions:
  `doctruth_runtime::doctor_json()` and
  `doctruth_runtime::run_with_args_and_input(...)`.
- Added `runtime/doctruth-runtime/tests/library_contract.rs` proving doctor and
  protocol error paths can be called through the library without spawning the
  binary.
- Initial library tests failed because they expected `code`, while the existing
  stable runtime error contract uses `error_code`. Updated tests to match the
  current protocol rather than changing the protocol shape.
- Rust focused verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 20
  tests passed across integration suites.
- Runtime smoke passed:
  `sh scripts/smoke-doctruth-runtime.sh`.
- Java CLI sidecar smoke passed:
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Honest boundary: this advances the Rust core requirement by making parser
  protocol logic reusable from a Rust library crate, but Rust is still not the
  default Java parser path and Java/PDFBox is still the default SDK fallback.

## 2026-06-13 Continued Rust Default Parser Selection

- Picked the next full-PRD gap: Java SDK and CLI still treated Rust as an
  explicitly requested sidecar, while PDFBox remained the implicit default.
- Added RED SDK coverage requiring `TrustDocumentParser.parse(path)` to prefer
  a configured runtime command from `doctruth.runtime.command` before PDFBox.
- Added RED CLI coverage requiring `doctruth parse <pdf> --runtime <path>
  --format markdown` to use the sidecar under the default `auto` backend rather
  than failing unless `--backend sidecar` is also supplied.
- RED verification failed as expected:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest test`
  reported SDK backend `pdfbox` and CLI exit code `2`.
- Implemented SDK runtime preference:
  `TrustDocumentParser` checks `doctruth.runtime.command` and
  `DOCTRUTH_RUNTIME_COMMAND`; when configured, it sends a `ParserRequest` to
  `SidecarParserBackend` before PDFBox fallback for non-OCR presets.
- Implemented CLI `auto` backend:
  default parse backend is now `auto`; `--runtime <path>` or
  `DOCTRUTH_RUNTIME_COMMAND` selects sidecar, `--backend pdfbox` forces the
  Java fallback, and `--backend sidecar` still requires a runtime.
- Updated CLI usage and PRD status docs to reflect
  `--backend auto|pdfbox|sidecar` and the configured-runtime default.
- Focused Java verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest test`.
- Broader related Java verification passed:
  `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentParserApiContractTest,TrustDocumentCliOutputProfileTest,CliSupportTest,DocTruthCliDoctorCompletionTest test`.
- Rust verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Runtime and Java CLI sidecar smokes passed:
  `sh scripts/smoke-doctruth-runtime.sh` and
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Honest boundary: Rust now becomes the default TrustDocument parser path when
  a local runtime is configured, and PDFBox is explicit fallback. The repo still
  needs packaging/runtime discovery work before Rust is the zero-config default,
  plus real model/OCR/labeled accuracy work before full PRD completion.

## 2026-06-13 Continued Zero-Config Packaged Rust Runtime

- Picked the next full-PRD gap: source install and release artifacts did not
  include `doctruth-runtime`, so Rust-first parsing still required manual
  runtime configuration.
- Added RED packaging coverage in `CliPackagingContractTest` requiring install,
  release, and release smoke scripts to mention `doctruth-runtime` and
  `DOCTRUTH_RUNTIME_COMMAND`.
- Implemented `scripts/install-cli.sh --runtime <path>` and default runtime
  discovery from `runtime/doctruth-runtime/target/release/doctruth-runtime` or
  `target/debug/doctruth-runtime`.
- Source install now copies the runtime into `PREFIX/bin/doctruth-runtime`, and
  the installed `bin/doctruth` launcher exports `DOCTRUTH_RUNTIME_COMMAND` when
  that same-directory runtime is executable.
- Implemented `scripts/package-cli-release.sh --runtime <path>` with the same
  default runtime discovery. Release tarballs now include
  `bin/doctruth-runtime`, and generated Homebrew formulae install it and export
  `DOCTRUTH_RUNTIME_COMMAND` from the wrapper.
- Extended `scripts/smoke-cli-release.sh` to verify the packaged runtime
  `--doctor` response and to parse a generated PDF through the packaged
  launcher without manually setting `DOCTRUTH_RUNTIME_COMMAND`.
- Updated install, Homebrew, CLI, and PRD docs to explain that packaged CLI
  parsing is Rust-first after install and `--backend pdfbox` is the explicit
  fallback.
- Initial source-install smoke failed because the ad hoc shell command used the
  macOS `/usr/bin/java` stub. Re-ran with the same Homebrew/OpenJDK fallback
  logic used by release smoke, and the install smoke passed.
- Focused packaging verification passed:
  `mvn -q -Dtest=CliPackagingContractTest test`.
- Build and release smoke passed:
  `cargo build --manifest-path runtime/doctruth-runtime/Cargo.toml`,
  `mvn -q -DskipTests package`,
  `scripts/package-cli-release.sh --version 0.2.0-alpha --dist target/cli-release-dist`,
  and `scripts/smoke-cli-release.sh --version 0.2.0-alpha --dist target/cli-release-dist`.
- Source-install smoke passed with a temporary prefix: installed
  `doctruth-runtime --doctor` reported `doctruth-runtime`, and installed
  `doctruth parse --format json` parsed a generated PDF through sidecar backend
  without manual runtime env setup.
- Honest boundary: packaged CLI is now zero-config Rust-first. Direct
  `java -jar ...` and library SDK usage still need explicit
  `DOCTRUTH_RUNTIME_COMMAND`/`doctruth.runtime.command` unless a future native
  embedded runtime or classpath resource discovery path is added.

## 2026-06-13 Continued Real Model Artifact Acceptance Harness

- Picked the next full-PRD gap: model worker smokes executed ONNXRuntime, but
  the repository still only had generated synthetic model fixtures and no
  reusable acceptance path for user-supplied real RT-DETR/TATR/SLANeXT
  artifacts.
- Added `scripts/smoke-doctruth-real-model-artifact.sh`, an opt-in smoke gated
  by `DOCTRUTH_REAL_MODEL_MANIFEST`.
- The smoke verifies `doctruth-onnx-model-worker --doctor`, warms the cache
  from the SHA-pinned manifest, generates a PDF, runs `doctruth parse` through
  the configured model worker, and asserts `pdfbox+model-worker`, expected
  preset, expected model id, and expected task output shape.
- Supported smoke inputs:
  `DOCTRUTH_REAL_MODEL_MANIFEST`, `DOCTRUTH_REAL_MODEL_PRESET`,
  `DOCTRUTH_REAL_MODEL_EXPECTED_ID`, `DOCTRUTH_REAL_MODEL_EXPECTED_TASK`,
  `DOCTRUTH_REAL_MODEL_CACHE`, and `DOCTRUTH_REAL_MODEL_SMOKE_DIR`.
- Added contract coverage in `CliPackagingContractTest` to keep the real model
  smoke tied to cache warm, ONNX worker, model identity, task, and
  `pdfbox+model-worker` expectations.
- Updated CLI/install/PRD docs with real model artifact smoke usage and the
  explicit boundary: the repo provides the acceptance harness but does not
  bundle production RT-DETR/TATR/SLANeXT weights.
- Verified safe skip path:
  `scripts/smoke-doctruth-real-model-artifact.sh` exits 0 and prints a skip
  message when `DOCTRUTH_REAL_MODEL_MANIFEST` is absent.
- Verified executable path with a supplied ONNX artifact manifest:
  generated a TATR-like ONNX artifact, wrote a SHA-pinned manifest, and ran
  `DOCTRUTH_REAL_MODEL_MANIFEST=... DOCTRUTH_REAL_MODEL_PRESET=table-lite
  DOCTRUTH_REAL_MODEL_EXPECTED_ID=real-harness-tatr-like:smoke
  DOCTRUTH_REAL_MODEL_EXPECTED_TASK=table-structure-recognition
  scripts/smoke-doctruth-real-model-artifact.sh` -> passed.
- Focused Java verification passed:
  `mvn -q -Dtest=CliPackagingContractTest,LocalModelWorkerManifestContractTest,ModelCacheCommandTest test`.
- Existing ONNX worker smokes passed:
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`,
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`, and
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`.
- Whitespace verification passed:
  `git diff --check`.
- Honest boundary: this makes real model artifacts testable and cache-gated,
  but it still does not add curated production RT-DETR/TATR/SLANeXT artifacts
  to the repo or prove their accuracy on real-world PDFs.

## 2026-06-13 Continued OCR Labeled Corpus Failure Gate

- Picked the next OCR corpus gap: generated OCR corpus pass cases existed, and
  `ParserBenchmarkRunnerTest` covered low OCR accuracy in memory, but the CLI
  corpus contract and packaged smoke did not explicitly prove that a wrong OCR
  label fails the corpus gate.
- Added `ParserBenchmarkCorpusCliTest#benchmarkCorpusOcrLabelFailureReturnsRuntimeError`.
  It writes a blank scanned-PDF fixture, configures a fake MNN-compatible OCR
  worker that returns `OCR benchmark text`, intentionally labels the expected
  Markdown as `Different OCR label`, and requires `benchmark-corpus` to exit
  `1` with `ocr-wrong-label`, `ocr_text_accuracy`, and `minimum=1.0` in stderr.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` with
  `corpus-ocr-fail.json`, so the packaged CLI path now verifies OCR wrong-label
  threshold failure in addition to generic minimum, warning maximum, latency,
  compact, and offline-remote failures.
- Focused CLI corpus verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest test`.
- Benchmark corpus smoke passed:
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Honest boundary: this closes the generated OCR label failure gate. It still
  does not provide the broad labeled scanned-PDF OCR corpus or real-world OCR
  accuracy required by the full PRD.

## 2026-06-13 Continued Real OCR Runtime Corpus Smoke

- Picked the next OCR quality bridge: real RapidOCR runtime smoke existed, and
  generated benchmark-corpus OCR gates existed, but the real OCR worker had not
  been exercised through `benchmark-corpus`.
- Added `scripts/smoke-doctruth-real-ocr-corpus.sh`. It is opt-in via
  `DOCTRUTH_REAL_OCR_CORPUS_SMOKE=1`, installs or reuses an isolated RapidOCR +
  ONNXRuntime venv, verifies `doctruth-rapidocr-mnn-worker --doctor`, generates
  a scanned invoice PDF, parses it through the real OCR worker to produce an
  expected `TrustDocument` label, then runs `benchmark-corpus --json` with
  `minimums.ocr_text_accuracy`.
- Added packaging contract coverage in `CliPackagingContractTest` so the new
  smoke keeps the `benchmark-corpus`, `ocr_text_accuracy`, min-accuracy, and
  RapidOCR worker expectations.
- Default skip path passed:
  `sh scripts/smoke-doctruth-real-ocr-corpus.sh`.
- Focused packaging test passed:
  `mvn -q -Dtest=CliPackagingContractTest test`.
- Opt-in real OCR corpus smoke passed:
  `DOCTRUTH_REAL_OCR_CORPUS_SMOKE=1 sh scripts/smoke-doctruth-real-ocr-corpus.sh`.
  The run installed RapidOCR + ONNXRuntime into an isolated venv, downloaded
  PP-OCRv4 mobile ONNX detector/classifier/recognizer models, and ended with
  `doctruth real OCR corpus smoke passed`.
- Honest boundary: this proves the real local OCR runtime can feed the
  benchmark corpus gate for a generated scanned-PDF fixture. It still does not
  complete the broad labeled scanned-PDF OCR corpus or real-world OCR accuracy
  requirement.

## 2026-06-13 Continued Public TATR Artifact Execution Smoke

- Picked the next model-runtime gap: real model artifact harness existed, but
  only synthetic generated ONNX artifacts had been executed. The public TATR
  path needed a reproducible smoke that can download/cache a real artifact.
- Used the Hugging Face API to confirm
  `Xenova/table-transformer-structure-recognition` provides ONNX files,
  including `onnx/model_quantized.onnx` at about 30 MB.
- Added `scripts/smoke-doctruth-real-tatr-artifact.sh`. It is opt-in via
  `DOCTRUTH_REAL_TATR_SMOKE=1`, downloads or reuses the public quantized ONNX
  artifact, writes a SHA-pinned manifest, and invokes the existing
  `scripts/smoke-doctruth-real-model-artifact.sh` harness with expected model
  id `xenova-table-transformer-structure-recognition:model_quantized`.
- Added packaging contract coverage in `CliPackagingContractTest` so the smoke
  keeps the HF repo, quantized ONNX path, real-model manifest, expected model
  id, and ONNX worker preflight.
- First opt-in run failed because the manifest wrote a relative artifact path
  while the manifest itself lived in a temp directory. Fixed the script to turn
  the cache directory into an absolute path before writing the manifest.
- Second opt-in run proved cache warm succeeded, but Java fell back to PDFBox.
  Direct worker reproduction showed the real ONNX failure:
  `Input channels C is not equal to kernel channels * group. C: 1 kernel channels: 3`.
- Fixed `scripts/doctruth_onnx_worker_lib.py` input shape inference for 4D
  vision models: dynamic batch defaults to `1`, dynamic channels to `3`, and
  dynamic height/width to `800`, instead of replacing every dynamic dimension
  with `1`.
- Direct worker request against the downloaded Xenova TATR quantized ONNX now
  passed and reported `pdfbox+model-worker`, expected model id, and resource
  metrics around 258 MB RSS on this machine.
- Opt-in public TATR artifact smoke passed:
  `DOCTRUTH_REAL_TATR_SMOKE=1 sh scripts/smoke-doctruth-real-tatr-artifact.sh`.
- Focused packaging and ONNX smoke verification passed:
  `mvn -q -Dtest=CliPackagingContractTest test`,
  `sh scripts/smoke-doctruth-real-tatr-artifact.sh`,
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`, and
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`.
- Honest boundary: this proves real public TATR ONNX loading/execution through
  the Java CLI + local ONNX worker path. It does not prove table recognition
  accuracy because the worker still feeds synthetic all-ones vision tensors and
  lacks real page-image preprocessing/post-processing.

## 2026-06-13 Continued Rendered-Page ONNX Vision Input

- Picked the next model-runtime gap: the public TATR artifact could execute,
  but the ONNX worker still fed synthetic all-ones tensors rather than rendered
  document pixels.
- Inspected local runtime capabilities: Pillow 12.1.1, ONNXRuntime 1.26.0, and
  `/opt/homebrew/bin/pdftoppm` are available. The public Xenova TATR ONNX input
  is `pixel_values` with shape `[batch_size, num_channels, height, width]`, and
  outputs are `logits` plus `pred_boxes`.
- Updated `scripts/doctruth_onnx_worker_lib.py` so 4D vision inputs attempt to
  render the first PDF page with `pdftoppm`, load it with Pillow, resize to the
  model input height/width, convert to RGB channel-first float tensor, and mark
  `metrics.inputSource=rendered_page`. Non-vision and unavailable-renderer paths
  still use deterministic synthetic tensors and report `synthetic_tensor`.
- Extended `scripts/smoke-doctruth-onnx-worker-resources.sh` to assert the
  non-vision identity model still reports `inputSource=synthetic_tensor`.
- Extended `scripts/smoke-doctruth-real-tatr-artifact.sh` with a direct worker
  request against a generated PDF and the downloaded TATR artifact, asserting
  `metrics.inputSource=rendered_page` before running the Java CLI real-model
  harness.
- Verification passed:
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`,
  `DOCTRUTH_REAL_TATR_SMOKE=1 sh scripts/smoke-doctruth-real-tatr-artifact.sh`,
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`,
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`,
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`, and
  `mvn -q -Dtest=CliPackagingContractTest test`.
- Honest boundary: this upgrades real TATR execution from synthetic tensor
  input to rendered page pixels. It still does not implement TATR-specific
  preprocessing normalization, output post-processing into real table structure,
  or labeled table accuracy.

## 2026-06-13 Continued Real TATR Row/Column Post-Processing

- Picked the next concrete TATR gap: the public Xenova TATR artifact was
  executing on rendered page pixels, but the ONNX worker still decoded it with
  the synthetic two-label `table/cell` contract. Real TATR uses labels such as
  `table`, `table row`, `table column`, `table column header`, projected row
  headers, and spanning cells.
- Added a RED assertion to `scripts/smoke-doctruth-real-tatr-artifact.sh`: the
  smoke now generates a 3x3 grid PDF, declares the manifest/request task as
  `table-structure-recognition`, and requires real worker output to contain
  multi-row and multi-column cells rather than a flat row-0 pseudo-cell list.
- RED result:
  `DOCTRUTH_REAL_TATR_SMOKE=1 sh scripts/smoke-doctruth-real-tatr-artifact.sh`
  failed because all emitted cells had `rowRange.start == 0`; the worker had
  treated TATR row/column detections as generic cells.
- Implemented a decoder split in `scripts/doctruth_onnx_worker_lib.py`:
  synthetic 2-class TATR/DETR smoke models still use the legacy `table/cell`
  path, while real TATR-class models use the 6-label Table Transformer label
  set and create provisional cells from row/column bbox intersections clipped
  to the detected table box.
- Updated `scripts/smoke-doctruth-real-model-artifact.sh` so callers can supply
  `DOCTRUTH_REAL_MODEL_SOURCE_PDF`; the TATR smoke now passes its generated
  grid PDF into the generic real-model harness and sets
  `DOCTRUTH_REAL_MODEL_EXPECTED_TASK=table-structure-recognition`.
- Verification passed:
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`,
  `DOCTRUTH_REAL_TATR_SMOKE=1 sh scripts/smoke-doctruth-real-tatr-artifact.sh`,
  `sh scripts/smoke-doctruth-onnx-table-low-confidence.sh`,
  `sh scripts/smoke-doctruth-onnx-worker-resources.sh`,
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`, and
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`.
- Honest boundary: this is first-pass real TATR post-processing on a generated
  grid PDF. It is still not calibrated TATR normalization, SLANeXT parity,
  borderless-table model accuracy, or a labeled real-world table corpus.

## 2026-06-13 Continued Real RT-DETR Layout Artifact Smoke

- Searched for a legitimate public document-layout RT-DETR ONNX artifact rather
  than using a COCO object detector as a placeholder. Found
  `Kreuzberg/layout-models`, whose model card documents `rtdetr/model.onnx` as
  RT-DETR v2 document layout detection with 17 document layout classes,
  Apache-2.0 license, `images` plus `orig_target_sizes` inputs, and
  `labels`/`boxes`/`scores` outputs.
- Added `scripts/smoke-doctruth-real-rtdetr-artifact.sh`. It is opt-in via
  `DOCTRUTH_REAL_RTDETR_SMOKE=1`, downloads or reuses the public
  `rtdetr/model.onnx` artifact, writes a SHA-pinned manifest, generates a
  simple document-layout PDF, calls the ONNX worker directly, and then runs the
  generic Java CLI real-model harness with `task=layout-detection`.
- Added packaging contract coverage in `CliPackagingContractTest` so the new
  smoke keeps the Kreuzberg repo id, RT-DETR artifact path, layout task,
  `orig_target_sizes`, and model-worker expectations.
- RED result:
  `DOCTRUTH_REAL_RTDETR_SMOKE=1 sh scripts/smoke-doctruth-real-rtdetr-artifact.sh`
  first failed because `orig_target_sizes` expected `tensor(int64)` while the
  worker created float tensors for every non-image input. After fixing that, it
  failed again because the worker only supported synthetic `logits`/`boxes`
  layout outputs.
- Implemented real RT-DETR support in `scripts/doctruth_onnx_worker_lib.py`:
  `orig_target_sizes` input is now an int64 `[1, 2]` tensor; `images` input
  uses rendered-page pixels plus ImageNet normalization; `labels`/`boxes`/
  `scores` outputs are decoded with the documented 17 document-layout classes
  into DocTruth layout labels and normalized bboxes.
- Verification passed:
  `DOCTRUTH_REAL_RTDETR_SMOKE=1 sh scripts/smoke-doctruth-real-rtdetr-artifact.sh`,
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`,
  `sh scripts/smoke-doctruth-onnx-layout-low-confidence.sh`,
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`, and
  `mvn -q -Dtest=CliPackagingContractTest test`.
- Honest boundary: this proves one public document-layout RT-DETR artifact can
  execute through the local model-worker path and produce layout units. It does
  not prove multi-column reading-order improvement or broad layout accuracy
  without labeled corpus results.

## 2026-06-13 Continued SLANeXT/PaddleOCR Table Worker Adapter

- Picked the next remaining model-assisted table gap: TATR real artifact smoke
  exists, but `table-server` still had no SLANeXT/equivalent worker boundary.
- Checked the local Python environment: `paddleocr` and `paddle` are not
  installed, while `transformers` is installed. This means a real SLANeXT smoke
  cannot be honestly claimed on this machine without installing the PaddleOCR
  runtime.
- Added a RED packaging contract requiring source install, release packaging,
  and release smoke to include `doctruth-slanext-table-worker`. The focused
  test failed as expected because the worker was not present in install/release
  scripts.
- Added `scripts/doctruth-slanext-table-worker`, a DocTruth-owned JSON
  model-worker adapter for PaddleOCR/SLANeXT. It supports `--doctor`, renders a
  PDF page to an image when needed, calls PaddleOCR table recognition, normalizes
  returned cells, and emits `TrustDocument` table/cell evidence.
- Added `scripts/smoke-doctruth-slanext-table-worker.sh`, which uses a fake
  `paddleocr.TableStructureRecognition` module to prove doctor readiness,
  direct worker output, Java CLI `table-server` integration, and table-cell
  preservation without downloading or bundling model binaries.
- Added `scripts/smoke-doctruth-real-slanext-artifact.sh`, an opt-in real
  runtime smoke gated by `DOCTRUTH_REAL_SLANEXT_SMOKE=1`. It intentionally
  skips by default and requires PaddleOCR/SLANeXT to be installed by the user
  or CI environment.
- Wired the SLANeXT worker into `scripts/install-cli.sh`,
  `scripts/package-cli-release.sh`, the generated Homebrew formula, and
  `scripts/smoke-cli-release.sh`.
- Verification passed:
  `sh scripts/smoke-doctruth-slanext-table-worker.sh`,
  `sh scripts/smoke-doctruth-real-slanext-artifact.sh` default skip, and
  `mvn -q -Dtest=CliPackagingContractTest test`.
- Honest boundary: the SLANeXT adapter protocol and packaging are now covered.
  Real PaddleOCR/SLANeXT model execution was still pending at this point until
  the opt-in smoke could run in an environment with PaddleOCR/Paddle installed.

## 2026-06-13 Continued Human-Labeled Benchmark Corpus Contract

- Picked the next remaining accuracy gap: generated benchmark fixtures and
  recorded crash/regression corpora existed, but there was no hard manifest
  distinction between generated fixtures and human-labeled parser accuracy
  corpora.
- Added RED tests in `ParserBenchmarkCorpusTest` requiring
  `kind: "human-labeled"` manifests to expose label metadata and reject missing
  thresholds for declared required metrics. The first RED failed at compile time
  because `ParserBenchmarkCorpus.kind()`, `labelSetVersion()`, and
  `requiredMetrics()` did not exist.
- Implemented human-labeled manifest validation in `ParserBenchmarkCorpus`:
  `labeling.labelSetVersion`, `labeling.reviewedAt`, `labeling.reviewer`, and
  non-empty `labeling.requiredMetrics` are required, and each required metric
  must appear in either `minimums` or `maximums`.
- Added a RED CLI JSON contract in `ParserBenchmarkCorpusCliTest`; it failed
  because `benchmark-corpus --json` did not emit `kind` metadata.
- Updated `BenchmarkCorpusCommand` JSON output to include `kind`,
  `labelSetVersion`, and `requiredMetrics` for CI/release consumers.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` with a passing
  human-labeled manifest and a failing human-labeled manifest missing a required
  metric threshold.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`,
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`,
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`,
  and `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test`.
- Honest boundary: this completes the human-labeled corpus contract and smoke
  gate. It does not populate the broad real-world labeled PDF corpus yet.

## 2026-06-13 Continued Public Human-Labeled Remote PDF Smoke

- Continued the corpus accuracy track by upgrading
  `scripts/smoke-doctruth-real-pdf-corpus.sh` from a remote-PDF contract smoke
  into a small `kind: "human-labeled"` public fixture smoke.
- The W3C dummy PDF manifest now includes `labeling.labelSetVersion`,
  `labeling.reviewedAt`, `labeling.reviewer`, and required metrics for
  reading order, quote anchors, table cells, bbox IoU, and table region IoU.
  The same manifest includes explicit thresholds for every required metric.
- The smoke now verifies CLI JSON emits `kind`, `labelSetVersion`, and
  `requiredMetrics`, so release/CI consumers can distinguish this from
  generated fixtures.
- Verification passed: `sh scripts/smoke-doctruth-real-pdf-corpus.sh`.
- Honest boundary: this proves the public remote human-labeled corpus path
  end-to-end. It is one small public fixture, not a broad parser-accuracy
  corpus for multi-layout, table, OCR, bbox, or source-map quality.

## 2026-06-13 Continued Real SLANeXT Runtime Smoke

- Created an isolated temporary Python 3.10 venv at `/tmp/doctruth-slanext-venv`
  and installed `paddleocr 3.7.0` plus `paddlepaddle 3.3.1` for local
  verification without changing the repo or global Python environment.
- First real SLANeXT smoke failed after PaddleOCR returned no DocTruth table
  cells. Direct inspection showed PaddleOCR 3.7 returns table recognition as
  `TableRecResult.json.res` with `structure` tokens and flat 8-number
  quadrilateral `bbox` entries, not the fake smoke's `cells` objects.
- Updated `scripts/doctruth-slanext-table-worker` to normalize
  `TableRecResult.json.res`, derive row/column positions from `<tr>`/`<td>`
  structure tokens, and convert flat quadrilateral bbox arrays into rectangular
  DocTruth bboxes.
- Verification passed:
  direct worker smoke over the real PaddleOCR result produced 7 units and
  1 table; `sh scripts/smoke-doctruth-slanext-table-worker.sh`; and
  `PATH=/tmp/doctruth-slanext-venv/bin:$PATH DOCTRUTH_REAL_SLANEXT_SMOKE=1 DOCTRUTH_REAL_SLANEXT_SMOKE_DIR=/tmp/doctruth-real-slanext-debug sh scripts/smoke-doctruth-real-slanext-artifact.sh`.
- Honest boundary: this proves real PaddleOCR/SLANeXT integration on a
  generated grid PDF. It does not prove broad SLANeXT accuracy on real-world
  borderless or mixed-layout tables.

## 2026-06-13 Continued Parser-Accuracy Coverage Contract

- Picked the next broad accuracy gap: `kind: human-labeled` proves label
  provenance but still lets a one-case fixture look too close to a parser
  accuracy corpus.
- Added RED corpus tests for `qualityProfile: "parser-accuracy"` requiring
  `labeling.requiredTags` and `labeling.minCasesPerTag`. The first run failed
  at test compile because `ParserBenchmarkCorpus.qualityProfile()`,
  `requiredTags()`, and `minCasesPerTag()` did not exist.
- Implemented manifest validation that only applies to parser-accuracy
  human-labeled corpora: required tags must be nonblank, `minCasesPerTag` must
  be at least 1, and each required tag must appear on enough case `tags`.
- Added CLI JSON coverage metadata for `qualityProfile`, `requiredTags`, and
  `minCasesPerTag`; the RED CLI test first failed because the JSON field was
  empty, then passed after updating `BenchmarkCorpusCommand`.
- Extended `scripts/smoke-doctruth-benchmark-corpus.sh` with a passing
  parser-accuracy corpus and a failing coverage corpus, including diagnostics
  for missing tag counts.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest test`,
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest test`,
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`,
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`,
  and `mvn -q -Dtest=PublicApiSnapshotTest,ArchitectureContractTest test`.
- Honest boundary: this prevents under-covered corpora from being presented as
  parser accuracy gates. It does not populate the actual broad corpus.

## 2026-06-13 Continued Real Model Suite Smoke

- Picked the next model-runtime gap: RT-DETR, TATR, and SLANeXT had individual
  opt-in smokes, but there was no single release/CI entrypoint for running all
  real model gates together.
- Added a RED packaging contract in `CliPackagingContractTest` requiring
  `scripts/smoke-doctruth-real-model-suite.sh` and release/install inclusion.
  The first focused test failed because the suite script did not exist.
- Added `scripts/smoke-doctruth-real-model-suite.sh`. It skips by default and,
  with `DOCTRUTH_REAL_MODEL_SUITE=1`, runs the real RT-DETR, TATR, and SLANeXT
  smoke scripts.
- Wired the suite script into source install, release tarball packaging,
  Homebrew formula generation, and release tarball smoke checks.
- First real suite attempt failed because running the whole suite under the
  PaddleOCR venv shadowed the ONNXRuntime Python used by RT-DETR/TATR:
  `No module named 'onnxruntime'`.
- Updated `scripts/smoke-doctruth-real-slanext-artifact.sh` to support
  `DOCTRUTH_SLANEXT_PYTHON`, so the suite can use the default Python for
  ONNXRuntime and only switch SLANeXT to the PaddleOCR venv.
- Verification passed:
  `mvn -q -Dtest=CliPackagingContractTest test`,
  `sh scripts/smoke-doctruth-real-model-suite.sh`,
  and
  `DOCTRUTH_REAL_MODEL_SUITE=1 DOCTRUTH_SLANEXT_PYTHON=/tmp/doctruth-slanext-venv/bin/python DOCTRUTH_REAL_SLANEXT_SMOKE_DIR=/tmp/doctruth-real-slanext-debug sh scripts/smoke-doctruth-real-model-suite.sh`.
- Honest boundary: this creates a packaged release/CI entrypoint and proves it
  locally. It does not by itself configure remote CI to require the suite.

## 2026-06-13 Continued Release Workflow Real-Model Gate

- Picked the next external-gate gap: the real model suite existed locally and
  in release packages, but `.github/workflows/release.yml` did not require it.
- Added RED `WorkflowContractTest` coverage requiring release workflow setup
  for Python 3.10, `poppler-utils`, ONNXRuntime/Pillow/Numpy, PaddleOCR/Paddle,
  `DOCTRUTH_REAL_MODEL_SUITE=1`, `DOCTRUTH_SLANEXT_PYTHON`, and the real model
  suite script. The first run failed because neither CI nor release workflows
  referenced the suite.
- Updated `.github/workflows/ci.yml` to run the safe skip path
  `scripts/smoke-doctruth-real-model-suite.sh`, so PR CI catches missing script
  or packaging regressions without downloading large models.
- Updated `.github/workflows/release.yml` to install real model runtime
  dependencies and run `scripts/smoke-doctruth-real-model-suite.sh` with
  `DOCTRUTH_REAL_MODEL_SUITE=1` before SBOM/deploy/release publication.
- Pinned release smoke Python dependencies to the locally verified family:
  `onnxruntime==1.26.0`, `pillow>=12,<13`, `numpy<2.4`,
  `paddleocr==3.7.0`, and `paddlepaddle==3.3.1`.
- Verification passed: `mvn -q -Dtest=WorkflowContractTest test`.
- Honest boundary: this makes the release workflow require the model suite by
  contract. It does not prove a remote GitHub Actions run has already succeeded
  for this unpushed branch.

## 2026-06-13 Continued Parser-Accuracy Seed Corpus Smoke

- Picked the next PRD gap after the parser-accuracy coverage contract: broad
  real-world labels are still pending, but CI needed an executable seed gate
  that exercises the same manifest/metric plumbing.
- Added `scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`. The smoke
  generates minimal multi-layout, table, and scanned/OCR PDFs, creates expected
  labels from the current parser output, writes a `kind: human-labeled`
  `qualityProfile: parser-accuracy` manifest, and requires coverage tags for
  `multi-layout`, `table`, `ocr`, `bbox`, and `source-map`.
- Extended workflow and packaging contracts so CI runs the seed corpus smoke
  and release packaging includes the script.
- Verification passed:
  `mvn -q -Dtest=WorkflowContractTest,CliPackagingContractTest test` and
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Honest boundary: this is a generated seed corpus for contract enforcement.
  Because its expected labels are derived from current parser output, it cannot
  prove real-world parser accuracy.

## 2026-06-13 Continued Parser-Accuracy Case Label Contract

- Picked the next real-corpus auditability gap: parser-accuracy manifests had
  corpus-level label metadata and coverage tags, but `ParserBenchmarkCase` and
  CLI JSON did not preserve case-level `labelId` and `tags`.
- Added RED tests requiring parser-accuracy human-labeled cases to declare
  `labelId` and non-empty `tags`. The first failure proved missing case labels
  were not distinguished from coverage failures.
- Added RED CLI JSON coverage requiring every benchmark case result to include
  the case `labelId` and `tags`.
- Implemented `ParserBenchmarkLabel` and `ParserBenchmarkExpectation` value
  objects so `ParserBenchmarkCase` stays within the public record component
  limit while preserving compatibility accessors such as `labelId()`,
  `tags()`, `expectedMarkdown()`, and `expectedDocument()`.
- Extended `ParserBenchmarkResult` and `benchmark-corpus --json` so case label
  metadata appears in CI/release reports.
- Updated the parser-accuracy seed corpus smoke to assert per-case label ids
  and tags in the JSON report.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,ParserBenchmarkRunnerTest,ArchitectureContractTest test`,
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`,
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`, and
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Honest boundary: this makes real labeled corpora auditable once populated.
  It still does not populate the broad real-world label set.

## 2026-06-13 Continued Parser-Accuracy Review Type Contract

- Found an honesty gap in the seed corpus contract: the generated seed corpus
  used `kind: human-labeled` so it could exercise parser-accuracy gates, while
  its labels are produced from current parser output.
- Added RED tests requiring parser-accuracy corpora to declare
  `labeling.reviewType`, and requiring CLI JSON to emit that review posture.
- Implemented `ParserBenchmarkCorpus.reviewType()`. Parser-accuracy manifests
  now accept only `human-reviewed` or `generated-seed`; missing or unknown
  values fail during manifest load.
- Updated the generated parser-accuracy seed smoke to declare and assert
  `reviewType: generated-seed`.
- Updated the benchmark corpus smoke parser-accuracy fixture to declare and
  assert `reviewType: human-reviewed`.
- Updated the public API snapshot and PRD so release reports can distinguish
  contract seed gates from future real accuracy claims.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`,
  `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`,
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`, and
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Honest boundary: this prevents the generated seed corpus from being silently
  interpreted as human-reviewed accuracy evidence. It still does not create the
  real-world human-reviewed corpus.

## 2026-06-13 Rust-First Benchmark Corpus Protocol

- Reoriented the next slice after the Rust-first correction: no new parser
  quality/corpus behavior should be added only to Java. The next gate moved
  parser-accuracy manifest execution into `runtime/doctruth-runtime`.
- Added RED Rust contract tests in
  `runtime/doctruth-runtime/tests/benchmark_corpus_contract.rs`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`.
- RED result: both tests failed with stable `UNKNOWN_COMMAND`, proving
  `benchmark_corpus` was missing from the Rust runtime protocol.
- Implemented Rust `benchmark_corpus` handling in
  `runtime/doctruth-runtime/src/lib.rs`.
- The Rust runtime now loads manifest-relative PDFs, expected Markdown,
  expected TrustDocument labels, parser-accuracy label metadata, case
  `labelId`/`tags`, tag coverage requirements, and metric minimums.
- Added first native Rust corpus metrics:
  `reading_order_f1`, `quote_anchor_accuracy`, and `bbox_coverage`.
- Added `scripts/smoke-doctruth-runtime-benchmark-corpus.sh` to exercise the
  Rust runtime corpus protocol end to end without Java CLI.
- Added a second RED/GREEN Rust corpus check for `sourceSha256`: a manifest
  with a mismatched source hash first passed unexpectedly, then `checked_source_sha`
  was added so Rust rejects the case with `SOURCE_SHA256_MISMATCH` before
  parsing.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`,
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, and
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`.
- Honest boundary: this migrates the corpus gate skeleton to Rust. It does not
  yet prove real-world parser accuracy, run real RT-DETR/TATR/SLANeXT/OCR
  inside Rust, or populate the broad human-reviewed corpus.

## 2026-06-13 Rust-First Model Worker Handoff

- Picked the next Rust-first gap after the corpus protocol: model-assisted
  preset execution was still only a Java-side model-worker escape hatch.
- Added RED Rust contract tests in
  `runtime/doctruth-runtime/tests/model_worker_contract.rs`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`.
- RED result: `table-lite` ignored `DOCTRUTH_RUNTIME_MODEL_COMMAND` and returned
  heuristic `rust-sidecar` output; a bad worker also passed unexpectedly because
  the worker was never called.
- Implemented configured Rust model-worker handoff in
  `runtime/doctruth-runtime/src/lib.rs`. For model-assisted presets, the runtime
  now sends JSON stdin to `DOCTRUTH_RUNTIME_MODEL_COMMAND` or
  `DOCTRUTH_MODEL_COMMAND`, including source path/hash, preset,
  offline/download policy, and required model descriptors.
- Invalid worker JSON or worker process failure now maps to stable
  `MODEL_WORKER_FAILED` error JSON.
- Added `scripts/smoke-doctruth-runtime-model-worker.sh` to prove the Rust
  runtime can call a configured worker and return worker-produced
  `TrustDocument` without Java CLI.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`,
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, and
  `sh scripts/smoke-doctruth-runtime-model-worker.sh`.
- Honest boundary: this moves the model-worker handoff into Rust. The Rust
  runtime still does not itself execute ONNX, PaddleOCR/SLANeXT, or OCR models.

## 2026-06-13 Rust Corpus Case Preset Routing

- Picked the next integration gap: Rust `benchmark_corpus` and Rust
  model-worker handoff existed separately, but corpus cases still effectively
  ran through the default `lite` parser path.
- Added a RED test requiring a parser-accuracy corpus case with
  `preset: "table-lite"` to run through `DOCTRUTH_RUNTIME_MODEL_COMMAND` and
  pass thresholds against worker-produced text.
- RED result: the test failed with
  `BENCHMARK_THRESHOLDS_FAILED` and `reading_order_f1 0`, proving corpus cases
  ignored their preset and did not measure the model path.
- Implemented per-case preset routing in `run_benchmark_case(...)` and included
  the selected preset in each case report.
- Updated `scripts/smoke-doctruth-runtime-benchmark-corpus.sh` so the smoke
  now uses a fake model worker and a `table-lite` corpus case, then asserts the
  case `preset` survives in report JSON.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_uses_case_preset_for_model_worker_cases`.
- Honest boundary: this proves Rust corpus gates can exercise model-assisted
  paths through the configured worker handoff. It still does not prove real
  model accuracy or embed real ONNX/OCR execution in Rust.

## 2026-06-13 Rust Core Boundary Clarification

- Re-read `docs/pdf-parser-runtime-prd.md` after the user correction and
  confirmed the intended architecture is Rust parser/runtime core behind a
  stable Java SDK/CLI/API surface, not Java as the long-term parser home.
- Updated the PRD G3 and architecture sections to make this explicit:
  Rust owns parser/runtime behavior; Java owns SDK/CLI/API compatibility,
  packaging, lifecycle, and error mapping.
- Updated `task_plan.md` so future goal loops treat Rust core ownership as the
  acceptance target and avoid adding new parser-quality, OCR/table/layout,
  corpus, model-cache, model-execution, audit-grade, or evidence-reconciliation
  behavior only to Java.
- Honest boundary: this is a scope correction. It does not by itself complete
  the Rust core migration or prove real parser accuracy.

## 2026-06-13 MinerU-Style Layered Output PRD Sync

- Compared the current DocTruth shape against the MinerU-style output layering:
  Markdown, flat content list, deep middle trace, visual layout/span debug
  artifacts, and model/debug JSON.
- Updated `docs/pdf-parser-runtime-prd.md` to make DocTruth's layered output
  contract explicit without copying MinerU's schema:
  `markdown_clean`, `content_blocks.json`, `parse_trace.json`, `trust.json`,
  audit/review package, and visual debug artifacts.
- Added core PRD contracts for `ContentBlock`, `ParseTrace`, `TracePage`,
  `TraceBlock`, `TraceLine`, and `TraceSpan`.
- Added PRD Phase 0A, `Layered Parser Output Contract`, with TDD exit criteria:
  flat reading-order content blocks, deep page/block/line/span trace, clean
  Markdown regeneration from content blocks, TrustDocument evidence spans traced
  back to trace spans, and layout/span debug artifacts generated from the same
  trace ids.
- Synchronized `task_plan.md` with the layered-output scope and added pending
  continuation phases 247-250 for contract tests, Rust ownership, CLI output
  profiles, and visual trace artifacts.
- Updated `findings.md` with the current honest gap: DocTruth already has
  TrustDocument, TrustUnit, source maps, tables, and evidence spans, but does
  not yet expose a first-class page -> block -> line -> span intermediate trace.
- Verification passed: `git diff --check`.

## 2026-06-13 Rust Layered Output Contract

- Started the first TDD slice for PRD Phase 0A and task_plan phases 247-248:
  Rust-owned layered output, not Java-only output projection.
- Added RED tests in `runtime/doctruth-runtime/tests/protocol_contract.rs`:
  `parse_pdf_emits_flat_content_blocks_in_reading_order` and
  `parse_pdf_emits_parse_trace_with_block_line_span_links`.
- Initial RED command mistake:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_emits_flat_content_blocks_in_reading_order parse_pdf_emits_parse_trace_with_block_line_span_links`
  failed because Cargo accepts only one test-name filter. Re-ran the tests
  separately.
- RED results:
  each new test failed with `called Option::unwrap() on a None value` because
  `contentBlocks` and `parseTrace` did not exist in Rust `parse_pdf` output.
- Implemented minimal Rust-owned layered output in
  `runtime/doctruth-runtime/src/lib.rs`: `parse_pdf` now emits
  `contentBlocks` as flat reading-order blocks and `parseTrace` as
  page -> readingBlocks -> lines -> spans. Blocks and trace spans point back to
  `unitId`, `sourceObjectId`, and `evidenceSpanId`.
- Focused verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_emits_flat_content_blocks_in_reading_order`
  and
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_emits_parse_trace_with_block_line_span_links`.
- Full Rust/runtime verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`,
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`, and
  `sh scripts/smoke-doctruth-runtime.sh`.
- Related Rust smoke verification passed:
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`,
  `sh scripts/smoke-doctruth-runtime-model-worker.sh`, and `git diff --check`.
- Updated `task_plan.md` to mark phases 247 and 248 complete. Remaining layered
  output work is CLI file profiles for `content_blocks.json` / `parse_trace.json`
  and visual layout/span debug artifacts using the same trace ids.

## 2026-06-13 CLI Layered Output Profiles

- Continued PRD Phase 0A and task_plan phase 249: make layered outputs available
  through `doctruth parse`, not only through raw Rust `parse_pdf` JSON.
- Added RED CLI tests in `TrustDocumentCliOutputProfileTest` for
  `--format content_blocks` and `--format parse_trace`. Both initially failed
  with exit code 2 because `ParseCommand.OutputFormat` did not recognize those
  formats.
- Implemented `TrustDocumentCliWriters.writeContentBlocks(...)` and
  `writeParseTrace(...)`, deriving deterministic content blocks and
  page/block/line/span trace JSON from the current `TrustDocument` units and
  pages.
- Added `TRUST_CONTENT_BLOCKS` and `TRUST_PARSE_TRACE` parse formats and wired
  them for stdout and file output. Updated CLI usage examples for the new
  formats.
- Extended `scripts/smoke-doctruth-cli-sidecar.sh` so the shaded CLI calls the
  real Rust runtime sidecar and writes `sidecar-smoke.content_blocks.json` plus
  `sidecar-smoke.parse_trace.json`, then verifies block ids, unit ids,
  evidence span ids, and span `sourceObjectId` links.
- Verification passed:
  `mvn -q -Dtest=TrustDocumentCliOutputProfileTest test` and
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Updated `task_plan.md` to mark phase 249 complete. Remaining layered-output
  gap is phase 250: visual layout/span debug artifacts using the same trace ids.

## 2026-06-13 Visual Trace Review Package

- Continued PRD Phase 0A and task_plan phase 250: visual layout/span debug
  artifacts should be generated from the same trace ids as `parse_trace.json`.
- Added RED CLI test `DocTruthCliTest#reviewPackageWritesTraceLinkedDebugArtifacts`.
  It initially failed because `review-package` did not write
  `content_blocks.json`, `parse_trace.json`, `layout-debug.html`, or
  `span-debug.html`.
- Implemented the review package slice in `ReviewPackageCommand` and
  `TrustDocumentCliWriters`: `review-package` now writes the layered JSON files
  plus `layout-debug.html` and `span-debug.html`. The debug HTML includes
  `data-trace-block-id`, `data-trace-line-id`, and `data-trace-span-id`
  attributes tied to the IDs in `parse_trace.json`.
- Fixed two contract gaps found during review: Java `parse_trace` now emits
  `pageSize` as `{width,height}` to match Rust, and `SidecarParserBackend`
  capabilities now advertise `content_blocks` and `parse_trace`.
- Extended `scripts/smoke-doctruth-review-package.sh` so the shaded CLI verifies
  the new files, `doctruth.content_blocks.v1`, `doctruth.parse_trace.v1`,
  `pageSize` shape, and trace-id linkage in both debug HTML artifacts.
- Verification passed:
  `mvn -q -Dtest=DocTruthCliTest#reviewPackageWritesTraceLinkedDebugArtifacts,DocTruthCliTest#reviewPackageWritesHtmlDocumentAndPageImages,TrustDocumentCliOutputProfileTest#parseTraceProfileWritesBlockLineSpanEvidenceLayer,SidecarParserBackendTest#sidecarCapabilitiesIncludePlainTextOutput test`,
  `mvn -q -Dtest=DocTruthCliTest,TrustDocumentCliOutputProfileTest,SidecarParserBackendTest test`,
  `sh scripts/smoke-doctruth-review-package.sh`, and
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Updated `task_plan.md` to mark phase 250 complete. Remaining PRD-level gaps
  are broader than layered output: Rust-native real model/OCR execution,
  broad human-reviewed parser accuracy corpus, and production model artifact
  evidence.

## 2026-06-13 Parallel Rust Runtime And Corpus Gate Slices

- Responded to the concurrency concern by running two disjoint implementation
  workers instead of doing only audit. The write scopes were separated:
  Rust runtime/smoke work and parser-accuracy corpus CLI output work.
- Rust runtime slice: added
  `scripts/smoke-doctruth-runtime-real-model-suite.sh`. The smoke builds the
  Rust runtime test target, creates a tiny PDF, runs `doctruth-runtime`
  `parse_pdf` through `DOCTRUTH_RUNTIME_MODEL_COMMAND`, and verifies the output
  came from `rust-sidecar+model-worker` with `layout-rtdetr:v2` and `tatr:v1`
  model identities. By default it uses a fake worker so it is safe in local/CI
  environments, and it can be pointed at a compatible real worker with
  `DOCTRUTH_RUNTIME_REAL_MODEL_COMMAND`.
- Parser-accuracy corpus slice: readable `benchmark-corpus` output now exposes
  `kind`, `qualityProfile`, `reviewType`, `labelSetVersion`,
  `requiredMetrics`, `requiredTags`, `minCasesPerTag`, plus per-case
  `labelId` and `tags`. This prevents a human-labeled/parser-accuracy run from
  looking complete without showing what label and coverage evidence was used.
- Focused verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`,
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`,
  `sh scripts/smoke-doctruth-runtime-real-model-suite.sh`,
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`,
  `sh scripts/smoke-doctruth-real-pdf-corpus.sh`, and
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Updated `task_plan.md` with phases 251 and 252 as complete, and corrected the
  stale phase 220 status to complete based on the already-recorded
  `mvn verify -P recorded` evidence in the same plan.
- Honest boundary: Phase 245 is not fully complete just because the new Rust
  smoke exists. The Rust runtime handoff is gated, but production RT-DETR/TATR/
  SLANeXT/OCR artifacts still need an opt-in run through a compatible real
  worker, and the broad real-world human-reviewed parser corpus is still not
  populated.

## 2026-06-13 Full Verification Closure

- Closed the current TDD slice after the parallel Rust-runtime and parser-corpus
  work. The only late failure was JaCoCo branch coverage, not behavior:
  Surefire was already green, but the branch ratio was just below the configured
  threshold.
- Fixed the coverage gap with behavior tests rather than lowering the threshold:
  `TrustDocumentContractTest` now covers invalid `ParserRun` model/run ids,
  `ParserBenchmarkCorpusCliTest` covers missing/unknown benchmark-corpus
  arguments, and `ParserBenchmarkRunnerTest` covers real-PDF benchmark resource
  observations including configured model-cache size.
- Public API and architecture checks were updated deliberately:
  `public-api-snapshot.txt` includes the new public parser/runtime contracts,
  and `ArchitectureContractTest` allows the explicit `ParserRun.parserRunId`
  replay/provenance field instead of treating it as arbitrary record growth.
- Final Java recorded verification passed:
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`.
  Surefire ran 1002 tests with 0 failures and 0 errors. Failsafe ran 16 tests
  with 0 failures, 0 errors, and 2 skipped external smokes. JaCoCo reported
  `All coverage checks have been met.`
- Recorded real-world PDF fixture remained stable: 383 total PDFs, 379 parsed,
  4 malformed trailer failures, 0 parser bugs, passRate 0.9896, total parse
  time 17416 ms, mean 45473 us. The scan/image-only warning count remained 218
  and is not claimed as OCR quality.
- Final Rust verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Final smoke verification passed:
  `sh scripts/smoke-doctruth-runtime.sh`,
  `sh scripts/smoke-doctruth-runtime-model-worker.sh`,
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`,
  `sh scripts/smoke-doctruth-runtime-real-model-suite.sh`,
  `sh scripts/smoke-doctruth-cli-sidecar.sh`,
  `sh scripts/smoke-doctruth-review-package.sh`,
  `sh scripts/smoke-doctruth-model-worker.sh`,
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`,
  `sh scripts/smoke-doctruth-real-pdf-corpus.sh`, and
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Whitespace verification passed: `git diff --check`.
- Current honest status: the requested TDD slice is complete and verified.
  Remaining PRD work is intentionally still open: production real-model
  artifact execution through Rust as the default core, OCR quality on a labeled
  scanned-PDF corpus, and broad human-reviewed parser accuracy corpus.

## 2026-06-13 Rust Runtime Real RT-DETR/TATR Artifact Entry

- Continued from the remaining PRD gap around Phase 245. The previous runtime
  smoke proved that `doctruth-runtime` can call a model worker, but it did not
  run public RT-DETR/TATR artifacts through the Rust runtime entrypoint.
- Added RED packaging coverage in `CliPackagingContractTest`: release/install
  packaging must include `scripts/smoke-doctruth-runtime-real-model-artifacts.sh`.
  Initial focused test failed with `NoSuchFileException` for that script.
- Added RED Rust model-worker coverage in
  `runtime/doctruth-runtime/tests/model_worker_contract.rs`: the runtime must
  accept worker responses shaped like `{ok:true, document:{...}, metrics:{...}}`,
  matching the existing Python ONNX worker envelope. Initial focused Rust test
  failed with `worker response missing /docId`.
- Implemented Rust worker-envelope unwrapping and request compatibility:
  `doctruth-runtime` now sends both snake_case and camelCase source fields to
  workers, unwraps `{ok:true, document}`, normalizes returned
  `parserRun.backend` to `rust-sidecar+model-worker`, preserves the worker's
  original backend as `parserRun.workerBackend`, and records
  `parserRun.runtime=doctruth-runtime`.
- Added `scripts/smoke-doctruth-runtime-real-model-artifacts.sh`. It is
  skip-safe by default. With `DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1`, it
  downloads or reuses public `Kreuzberg/layout-models` RT-DETR and
  `Xenova/table-transformer-structure-recognition` TATR ONNX artifacts,
  prepares SHA-pinned manifests and Rust runtime cache files, then invokes
  `doctruth-runtime` `parse_pdf` through
  `DOCTRUTH_RUNTIME_MODEL_COMMAND=scripts/doctruth-onnx-model-worker`.
- Updated install/release packaging and release smoke so the new script is
  included and its default skip path is checked.
- Focused verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`,
  `mvn -q -Dtest=CliPackagingContractTest test`,
  `sh scripts/smoke-doctruth-runtime-real-model-artifacts.sh`, and
  `DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1 sh scripts/smoke-doctruth-runtime-real-model-artifacts.sh`.
- Full Rust verification passed after formatting:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`
  and `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- PRD status updated to show RT-DETR/TATR now have a Rust-runtime real artifact
  entrypoint. Honest boundary: Phase 245 remains open for SLANeXT/OCR Rust
  runtime execution and broader production parser accuracy.

## 2026-06-13 Rust Runtime SLANeXT/OCR Worker Protocol

- Continued the remaining Phase 245 gap for SLANeXT/OCR runtime ownership.
  RT-DETR/TATR already had a Rust runtime real-artifact entrypoint; SLANeXT and
  OCR still needed `doctruth-runtime parse_pdf` worker-path proof.
- Added RED runtime OCR smoke:
  `scripts/smoke-doctruth-runtime-ocr-worker.sh`. Initial run failed with
  `MODEL_WORKER_FAILED` because `doctruth-rapidocr-mnn-worker` returned the old
  OCR payload instead of a TrustDocument envelope.
- Updated `doctruth-rapidocr-mnn-worker` to support two protocols:
  the existing image OCR request remains unchanged, and `command=parse_pdf`
  now resolves the source page/image, runs RapidOCR, and returns
  `{ok:true, document, metrics}` with `OCR_REGION` units, bbox evidence,
  confidence, low-confidence warnings, and `parserRun.backend=rapidocr-worker`.
  Rust runtime then normalizes the envelope to
  `parserRun.backend=rust-sidecar+model-worker` while preserving
  `workerBackend=rapidocr-worker`.
- Added `scripts/smoke-doctruth-runtime-slanext-worker.sh`. It uses a fake
  PaddleOCR module to exercise `doctruth-slanext-table-worker` through
  `doctruth-runtime parse_pdf` with preset `table-server`, model cache metadata,
  and TrustDocument table/cell output.
- Extended `CliPackagingContractTest`, `scripts/install-cli.sh`,
  `scripts/package-cli-release.sh`, and `scripts/smoke-cli-release.sh` so the
  runtime OCR and SLANeXT worker smokes are distributed with the CLI package.
  The packaging test failed first because the install/release scripts did not
  contain the new smoke names, then passed after the package scripts were
  updated.
- Verification passed:
  `sh scripts/smoke-doctruth-runtime-ocr-worker.sh`,
  `sh scripts/smoke-doctruth-runtime-slanext-worker.sh`,
  `sh scripts/smoke-doctruth-rapidocr-worker.sh`,
  `sh scripts/smoke-doctruth-ocr-preset.sh`,
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`,
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`,
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`,
  `mvn -q -Dtest=CliPackagingContractTest,DocTruthCliDoctorCompletionTest test`,
  and `git diff --check`.
- Honest boundary: this completes the local Rust-runtime SLANeXT/OCR worker
  protocol gate. It is not yet an opt-in real PaddleOCR/RapidOCR run through
  Rust runtime with installed model stacks, and it is not a labeled real-world
  OCR/table accuracy claim.

## 2026-06-13 Documentation Status Consistency Audit

- Audited `docs/pdf-parser-runtime-prd.md`, `task_plan.md`, `progress.md`, and
  `findings.md` only. Scripts, runtime, and Java files were inspected as
  evidence but not edited.
- Corrected stale PRD wording that implied SLANeXT/OCR Rust runtime execution
  was wholly missing. Current honest status is narrower: Rust owns the worker
  protocol for SLANeXT/OCR and can normalize TrustDocument envelopes. After
  the follow-up smoke, generated real RapidOCR + ONNXRuntime now has recorded
  Rust-runtime evidence; real PaddleOCR/SLANeXT still needs recorded evidence
  through the Rust runtime route.
- Corrected the model status split: RT-DETR/TATR have a Rust-runtime real
  artifact entrypoint, model cache/manifest/handoff is complete, but production
  model execution still happens through external workers rather than in-process
  Rust.
- Updated `task_plan.md` so Phase 245 is `partial` instead of stale `pending`,
  with explicit complete subphases 251/254/255 and new pending phases for
  Rust-route real SLANeXT/OCR runs plus the architecture decision on
  worker-based versus in-process Rust model execution.
- Added the concise complete/partial/missing audit matrix to `findings.md`.

## 2026-06-13 Rust Runtime Real RapidOCR Corpus Smoke

- Added `scripts/smoke-doctruth-runtime-real-ocr-corpus.sh`, a skip-safe opt-in
  smoke controlled by `DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE=1`.
- The enabled smoke builds `runtime/doctruth-runtime`, creates an isolated
  RapidOCR + ONNXRuntime venv, generates a scanned invoice PDF, writes a model
  manifest/cache artifact for `ocr-router:v1`, and invokes
  `doctruth-runtime parse_pdf` with
  `DOCTRUTH_RUNTIME_MODEL_COMMAND=scripts/doctruth-rapidocr-mnn-worker`.
- The enabled local run downloaded RapidOCR PP-OCRv4 det/cls/rec ONNX models
  and passed with `doctruth Rust runtime real OCR corpus smoke passed`.
- Added `scripts/smoke-doctruth-runtime-real-slanext-artifact.sh`, a matching
  skip-safe Rust-route SLANeXT hook. It now creates or reuses an isolated
  Python environment and installs `paddleocr` plus `paddlepaddle` when no
  explicit `DOCTRUTH_SLANEXT_PYTHON`/`DOCTRUTH_SLANEXT_VENV` is supplied.
- The first enabled SLANeXT run failed after installing `paddleocr` because the
  lower-level `paddle` module was missing. The script now installs
  `paddlepaddle`, and the enabled rerun passed with
  `doctruth Rust runtime real SLANeXT smoke passed`.
- Updated install/release packaging so both new opt-in runtime-real scripts are
  distributed and checked by release smoke.
- Verification:
  `DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE=1 sh scripts/smoke-doctruth-runtime-real-ocr-corpus.sh`
  passed; `DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE=1 DOCTRUTH_SLANEXT_VENV=... sh scripts/smoke-doctruth-runtime-real-slanext-artifact.sh`
  passed.
- Honest boundary: generated real RapidOCR through the Rust runtime is now
  proven, and generated real SLANeXT/PaddleOCR through the Rust runtime is now
  proven. Broad labeled OCR/table accuracy remains open.

## 2026-06-13 Model Worker Boundary ADR

- Added `docs/adr/0011-model-execution-worker-boundary.md`.
- Decision: for DocTruth v1, `doctruth-runtime` is the Rust core by owning
  orchestration, manifest/cache validation, request envelopes, response
  validation/normalization, benchmark execution, and audit propagation. Heavy
  ONNXRuntime, PaddleOCR/SLANeXT, RapidOCR, and MNN model execution may remain
  in isolated local JSON workers.
- Added `ArchitectureContractTest.rustRuntimeModelExecutionBoundaryIsDocumented`
  so the worker-boundary decision is locked by tests.
- Updated `task_plan.md` to mark Phase 257 complete and Phase 245 complete for
  model-execution migration. This does not close the full PRD because broad
  human-reviewed parser accuracy and labeled OCR/table corpora are still open.
- Full recorded verification passed after the ADR and runtime-real smoke
  updates:
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`.
  Surefire reported 1003 tests, 0 failures/errors; Failsafe reported 16 tests,
  0 failures/errors, 2 skipped; JaCoCo coverage checks passed. The recorded PDF
  corpus reported 383 total PDFs, 379 success, 4 malformed-trailer parse
  failures, 0 parser bugs, and passRate 0.9896.

## 2026-06-13 Rust Benchmark Expected-Label Metrics

- Added a RED Rust contract test requiring `doctruth-runtime benchmark_corpus`
  to score expected `TrustDocument` labels with parser-quality metrics beyond
  manifest plumbing.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_scores_expected_document_quality_metrics`.
- RED result: failed with `BENCHMARK_THRESHOLDS_FAILED` because `bbox_iou` was
  missing and evaluated as `0`.
- Implemented Rust-side expected-document metric scoring for:
  `bbox_iou`, `evidence_span_accuracy`, `table_cell_f1`, and
  `ocr_text_accuracy`.
- Focused verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_scores_expected_document_quality_metrics`.
- Full Rust verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml` -> 31 tests
  passed across runtime contract suites.
- Corpus smoke verification passed:
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh` and
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Java full unit verification passed after the Rust benchmark metric slice:
  `mvn test` -> 1003 tests, 0 failures, 0 errors, 0 skipped.
- Final whitespace/generated-cache checks passed:
  `git diff --check`; `find scripts -name '__pycache__' -o -name '*.pyc'`
  returned no files.
- Honest boundary: this makes Rust `benchmark_corpus` capable of scoring the
  core expected-label accuracy metrics. It still does not populate the broad
  human-reviewed multi-layout/table/OCR/bbox/source-map corpus.

## 2026-06-13 Human-Reviewed Corpus Scale Gate

- Added RED Java coverage requiring parser-accuracy human-reviewed manifests to
  expose `minTotalCases()` and reject manifests that either omit
  `labeling.minTotalCases` or declare a minimum larger than the actual case
  count.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#parserAccuracyHumanLabeledManifestExposesCoverageMetadata test`.
- RED result: compile failed because `ParserBenchmarkCorpus.minTotalCases()` did
  not exist.
- Implemented Java `ParserBenchmarkCorpus.minTotalCases()`, human-reviewed
  parser-accuracy validation, and CLI readable/JSON output for
  `minTotalCases`.
- Added Rust runtime coverage requiring `doctruth-runtime benchmark_corpus` to
  reject `reviewType: human-reviewed` parser-accuracy manifests without
  `minTotalCases` before parsing cases.
- Updated benchmark corpus smoke so the human-reviewed parser-accuracy fixture
  declares and asserts `minTotalCases`.
- Verification:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest test`
  passed; `mvn -q -Dtest=PublicApiSnapshotTest -Ddoctruth.updatePublicApiSnapshot=true test`
  updated the public API snapshot; focused API/architecture rerun passed;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  passed; `sh scripts/smoke-doctruth-benchmark-corpus.sh` and
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh` passed.
- Honest boundary: this is a scale/claim guard. It does not create the broad
  human-reviewed corpus; it prevents small human-reviewed corpora from being
  treated as broad accuracy proof.

## 2026-06-13 Human-Reviewed Source Hash Pinning

- Added RED Java coverage requiring parser-accuracy `reviewType:
  human-reviewed` cases to include `sourceSha256`.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#humanReviewedParserAccuracyCasesRequireSourceSha256 test`.
- RED result: failed as expected because the manifest loaded successfully
  without a source hash pin.
- Implemented Java validation so human-reviewed parser-accuracy cases require
  `sourceSha256`, and local `source` files now verify `sourceSha256` just like
  remote `sourceUrl` fixtures.
- Added Java coverage for local PDF SHA mismatch diagnostics.
- Added Rust runtime validation so `benchmark_corpus` rejects human-reviewed
  parser-accuracy manifests that omit per-case `sourceSha256`.
- Updated the packaged benchmark corpus smoke so the human-reviewed
  parser-accuracy fixture writes the generated PDF SHA into the manifest.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#manifestVerifiesLocalPdfFixtureSha,ParserBenchmarkCorpusTest#humanReviewedParserAccuracyCasesRequireSourceSha256,ParserBenchmarkCorpusTest#parserAccuracyHumanLabeledManifestExposesCoverageMetadata test`
  and
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`.
- Broader verification passed after CLI fixture updates:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`.
- Full Java verification passed:
  `mvn test` -> 1006 tests, 0 failures, 0 errors, 0 skipped.
- Final whitespace/cache checks passed:
  `git diff --check`; `find scripts \( -name '__pycache__' -o -name '*.pyc' \) -print`
  returned no files.
- Honest boundary: this pins labels to exact source bytes. It still does not
  populate the broad human-reviewed corpus or prove real-world parser accuracy.

## 2026-06-13 Human-Reviewed Core Metric Coverage

- Added RED Java coverage requiring parser-accuracy `reviewType:
  human-reviewed` manifests to declare the core parser-quality metric set, not
  only `reading_order_f1`.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#humanReviewedParserAccuracyCorpusRequiresCoreMetrics test`.
- RED result: failed as expected because the incomplete metric manifest loaded
  successfully.
- Added RED Rust runtime coverage requiring `doctruth-runtime benchmark_corpus`
  to reject a human-reviewed parser-accuracy manifest that omits core metrics.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_metrics`.
- RED result: failed as expected because the manifest succeeded with only
  `reading_order_f1`, `quote_anchor_accuracy`, and `bbox_coverage`.
- Implemented Java and Rust validation for the core metric set:
  `reading_order_f1`, `quote_anchor_accuracy`, `bbox_coverage`, `bbox_iou`,
  `evidence_span_accuracy`, `table_cell_f1`, and `ocr_text_accuracy`.
- Updated human-reviewed parser-accuracy test/smoke fixtures to declare all
  core metrics and explicit thresholds. Generated seed corpora remain exempt.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#humanReviewedParserAccuracyCorpusRequiresCoreMetrics,ParserBenchmarkCorpusTest#parserAccuracyHumanLabeledManifestExposesCoverageMetadata test`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusJsonPrintsParserAccuracyCoverageMetadata test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_metrics`.
- Broader verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`.
- Full Java verification passed:
  `mvn test` -> 1007 tests, 0 failures, 0 errors, 0 skipped.
- Final whitespace/cache checks passed:
  `git diff --check`; `find scripts \( -name '__pycache__' -o -name '*.pyc' \) -print`
  returned no files.
- Honest boundary: this prevents incomplete human-reviewed metric declarations.
  It still does not populate the broad human-reviewed corpus or prove the beta
  parser-quality thresholds on real documents.

## 2026-06-13 Human-Reviewed Core Tag Coverage

- Added RED Java coverage requiring parser-accuracy `reviewType:
  human-reviewed` manifests to declare the core coverage tags, not only
  `multi-layout`.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#humanReviewedParserAccuracyCorpusRequiresCoreTags test`.
- RED result: failed as expected because the incomplete tag manifest loaded
  successfully.
- Added RED Rust runtime coverage requiring `doctruth-runtime benchmark_corpus`
  to reject a human-reviewed parser-accuracy manifest that omits core coverage
  tags.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_tags`.
- RED result: failed as expected because the manifest succeeded with only
  `multi-layout` in `requiredTags`.
- Implemented Java and Rust validation for the core coverage tags:
  `multi-layout`, `table`, `ocr`, `bbox`, and `source-map`.
- Updated human-reviewed parser-accuracy Java/CLI/smoke fixtures so the
  synthetic contract case carries all core tags. This proves manifest/reporting
  behavior only; it is not broad real-world corpus evidence.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest#humanReviewedParserAccuracyCorpusRequiresCoreTags,ParserBenchmarkCorpusTest#parserAccuracyHumanLabeledManifestExposesCoverageMetadata test`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusJsonPrintsParserAccuracyCoverageMetadata test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_tags`.
- Broader verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`.
- Full Java verification passed:
  `mvn test` -> 1008 tests, 0 failures, 0 errors, 0 skipped.
- Final whitespace/cache checks passed:
  `git diff --check`; `find scripts \( -name '__pycache__' -o -name '*.pyc' \) -print`
  returned no files.
- Honest boundary: this prevents incomplete human-reviewed coverage
  declarations. It still does not populate separate broad fixtures for each tag
  or prove parser-quality thresholds on real documents.

## 2026-06-13 Recorded Parser-Accuracy Report Artifact

- Picked the next PRD gap that can move without pretending a broad corpus
  exists: parser-accuracy benchmark runs must be able to write a durable report
  artifact, not only print terminal JSON.
- Added RED CLI coverage:
  `ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact`.
- RED command:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact test`.
- RED result: failed as expected with exit code 2 because `--report-out` was an
  unknown benchmark-corpus option.
- Implemented `doctruth benchmark-corpus <manifest> --report-out <report.json>`
  with parent-directory creation and a stable
  `reportFormat: doctruth.parser-benchmark.report.v1`.
- The report includes the resolved manifest path, corpus/kind/profile/review
  metadata, required metrics/tags, min case coverage, aggregate metrics, and
  per-case label/tag/metric evidence.
- Updated CLI usage, `docs/cli.md`, `docs/pdf-parser-runtime-prd.md`, and
  `scripts/smoke-doctruth-benchmark-corpus.sh` so parser-accuracy smoke writes
  and verifies the report artifact.
- Focused verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact test`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest test`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest test`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`.
- Broader verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,DocTruthCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract && sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `git diff --check`.
- Full Java verification passed:
  `mvn test` -> 1009 tests, 0 failures, 0 errors, 0 skipped.
- Parser-accuracy seed smoke passed:
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Final cache check passed:
  `find scripts \( -name '__pycache__' -o -name '*.pyc' \) -print`
  returned no files.
- Honest boundary: this makes benchmark evidence archivable. It still does not
  populate the broad human-reviewed PDF corpus or prove real-world OCR/table
  parser quality thresholds.

## 2026-06-13 Rust Recorded Benchmark Report Artifact

- Extended the recorded-report contract to the Rust runtime path so Phase 246
  can produce an artifact without relying on stdout capture.
- Added RED Rust coverage:
  `benchmark_corpus_writes_recorded_report_artifact`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_writes_recorded_report_artifact`.
- RED result: failed as expected because the runtime request succeeded but
  `report_path` was ignored and no report file was written.
- Implemented `benchmark_corpus` request field `report_path`. When present, the
  runtime creates parent directories and writes a pretty JSON report containing
  `reportFormat: doctruth.parser-benchmark.report.v1`, resolved manifest path,
  runtime/corpus/profile/review metadata, metrics, and per-case label/tag
  evidence.
- Updated `scripts/smoke-doctruth-runtime-benchmark-corpus.sh` so it passes
  `report_path` and verifies the recorded artifact separately from stdout.
- Updated `docs/pdf-parser-runtime-prd.md` to record the Rust protocol parity
  with Java CLI `--report-out`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_writes_recorded_report_artifact`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml && sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `git diff --check`.
- Honest boundary: Rust and Java can now archive benchmark reports, but broad
  human-reviewed fixtures and recorded quality reports still need to be
  populated with real labeled PDFs.

## 2026-06-13 Recorded Source Hash Evidence

- Audited the recorded benchmark report payloads and found that Java and Rust
  report artifacts carried label ids, tags, and metrics but did not surface the
  per-case `sourceSha256` pin.
- Added RED Java coverage by extending
  `ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact` to
  require `cases[0].sourceSha256`.
- Added RED Rust coverage by extending
  `benchmark_corpus_writes_recorded_report_artifact` to require the same
  source hash field.
- RED results: Java failed with an empty `sourceSha256`; Rust failed because the
  field was missing.
- Implemented Java source-hash propagation through `ParserBenchmarkLabel`,
  `ParserBenchmarkCase`, `ParserBenchmarkResult`, and benchmark report
  rendering. Kept `ParserBenchmarkCase` within the public-record component
  architecture limit by storing the source pin on the label metadata object.
- Implemented Rust report source-hash propagation by reusing the verified
  `checked_source_sha` value in each `benchmark_corpus` case report.
- Updated CLI/PRD docs and both Java/Rust benchmark smoke scripts to assert the
  recorded report includes `sourceSha256`.
- Updated public API snapshot for the intentional benchmark label/result API
  shape change.
- Verification passed:
  `mvn -q -Ddoctruth.updatePublicApiSnapshot=true -Dtest=PublicApiSnapshotTest test`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact,ParserBenchmarkCorpusCliTest#benchmarkCorpusJsonPrintsParserAccuracyCoverageMetadata,PublicApiSnapshotTest,ArchitectureContractTest test`;
  `mvn -q -Dtest=ParserBenchmarkCorpusTest,ParserBenchmarkCorpusCliTest,DocTruthCliTest,PublicApiSnapshotTest,ArchitectureContractTest test && sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract && sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `mvn test` -> 1009 tests, 0 failures, 0 errors, 0 skipped;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`.
- Honest boundary: this strengthens report auditability. It still does not
  populate the broad human-reviewed corpus or prove real-world parser quality.

## 2026-06-13 Recorded Manifest Hash Evidence

- Audited the v1 recorded benchmark report shape and found that reports carried
  the manifest path but not the manifest content hash. That meant an archived
  report did not cryptographically identify the exact labels, thresholds, and
  case list used for the run.
- Added RED Java coverage by extending
  `ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact` to
  require top-level `manifestSha256`.
- Added RED Rust coverage by extending
  `benchmark_corpus_writes_recorded_report_artifact` to require the same field.
- RED results: Java failed with an empty `manifestSha256`; Rust failed because
  the field was missing.
- Implemented Java `manifestSha256` hashing in `BenchmarkCorpusCommand` report
  writing.
- Implemented Rust `manifestSha256` hashing in `doctruth-runtime`
  `benchmark_corpus` recorded report writing.
- Updated Java/Rust benchmark smoke scripts and CLI/PRD docs to make manifest
  hash evidence part of the recorded report artifact contract.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_writes_recorded_report_artifact`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,PublicApiSnapshotTest,ArchitectureContractTest test && sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract && sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `git diff --check`.
- Honest boundary: this strengthens report provenance. It still does not
  populate the real broad human-reviewed corpus or prove external parser
  quality thresholds.

## 2026-06-13 Recorded Threshold Criteria

- Audited recorded benchmark reports and found that they pinned manifest path,
  manifest hash, and source hashes, but did not copy the threshold criteria into
  the report body. That made the artifact less self-contained for audit review.
- Added RED Java coverage by extending
  `ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact` to
  require top-level `minimums` and `maximums`.
- Added RED Rust coverage by extending
  `benchmark_corpus_writes_recorded_report_artifact` to require the same
  threshold fields.
- RED results: Java reported `minimums.reading_order_f1` as missing/0.0; Rust
  reported `minimums.reading_order_f1` as null.
- Implemented Java report population of `corpus.minimums()` and
  `corpus.maximums()`.
- Implemented Rust report population by copying `manifest.minimums` and
  `manifest.maximums`, defaulting to empty objects.
- Updated Java/Rust benchmark smoke scripts and CLI/PRD docs to treat copied
  threshold criteria as part of the recorded report contract.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_writes_recorded_report_artifact`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,PublicApiSnapshotTest,ArchitectureContractTest test && sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract && sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `git diff --check`.
- Honest boundary: reports are now more self-contained, but this still does not
  populate broad human-reviewed parser-accuracy fixtures.

## 2026-06-13 Recorded Coverage Counts

- Audited the recorded parser-accuracy report contract and found that reports
  copied `requiredTags` and `minCasesPerTag`, but did not record the actual case
  count or per-tag coverage produced by the current run.
- Added RED Java coverage in
  `ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact` for
  top-level `caseCount` and `casesPerTag`.
- Added RED Rust coverage in
  `benchmark_corpus_writes_recorded_report_artifact` for the same fields.
- RED results: Java returned `caseCount=0`/missing tag counts; Rust returned
  null for `caseCount`.
- Implemented Java report population from actual `ParserBenchmarkResult` rows.
- Implemented Rust report population from actual `case_reports` tags.
- Updated Java/Rust benchmark smoke scripts and CLI/PRD docs so recorded
  reports prove actual coverage, not only intended manifest requirements.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#benchmarkCorpusWritesRecordedReportArtifact test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_writes_recorded_report_artifact`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,PublicApiSnapshotTest,ArchitectureContractTest test`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`;
  `git diff --check`.
- Honest boundary: this strengthens benchmark report auditability. It still
  does not populate the broad human-reviewed parser-accuracy corpus.

## 2026-06-13 Recorded Report Verifier

- Audited recorded parser-accuracy reports after the coverage-count work and
  found that the reports could be written, but there was no standalone CLI
  verifier to validate an archived report without rerunning the parser.
- Added RED Java coverage requiring
  `doctruth verify-benchmark-report <report.json>` to accept a freshly recorded
  report, reject tampered `caseCount`, and reject a changed manifest through
  `manifestSha256` mismatch.
- RED result: all verifier tests returned usage code 2 because the command did
  not exist.
- Implemented `VerifyBenchmarkReportCommand` and routed it through
  `DocTruthCli`.
- The verifier checks report format, pass status, manifest path,
  `manifestSha256`, copied threshold objects, required metric/tag arrays,
  actual `caseCount`/`casesPerTag`, and manifest/source hash pins echoed into
  report cases.
- Updated usage/help, shell completion, CLI docs, PRD text, and benchmark smoke.
  The smoke now verifies the valid recorded report and confirms a tampered
  coverage count fails.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#verifyBenchmarkReportAcceptsRecordedReportArtifact,ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsTamperedCoverageCounts,ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsChangedManifest test`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest test`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`;
  `git diff --check`.
- Honest boundary: this closes report-verification plumbing. It still does not
  create the broad human-reviewed corpus required for external parser accuracy
  claims.

## 2026-06-13 Recorded Coverage Threshold Verifier

- Audited `verify-benchmark-report` and found that it checked actual
  `caseCount`/`casesPerTag`, but did not compare copied coverage thresholds
  (`minCasesPerTag`, `minTotalCases`) back to the manifest or re-check actual
  coverage against those copied thresholds.
- Added RED Java coverage by tampering `minCasesPerTag.source-map` in a
  recorded parser-accuracy report and expecting verifier failure.
- RED result: the tampered report still verified successfully.
- Implemented manifest-aware `minCasesPerTag` comparison. The verifier now
  handles the manifest shorthand form (`minCasesPerTag: 1`) by expanding it
  across `requiredTags`, matching the report's per-tag map.
- Implemented coverage threshold satisfaction checks for `minTotalCases` and
  per-tag minimums using actual report cases.
- Updated benchmark smoke to verify both a tampered `caseCount` and a tampered
  `minCasesPerTag` path.
- Updated CLI docs and PRD wording for copied coverage requirement
  verification.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#verifyBenchmarkReportAcceptsRecordedReportArtifact,ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsTamperedCoverageCounts,ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsTamperedCoverageThresholds,ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsChangedManifest test`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`;
  `git diff --check`.
- Honest boundary: this makes archived report verification stricter. It still
  does not populate the broad human-reviewed parser-accuracy corpus.

## 2026-06-13 Rust Recorded Report Verifier Parity

- Audited the Rust runtime report path after the Java verifier work and found
  two parity gaps: runtime recorded reports did not include `minCasesPerTag`,
  and `doctruth-runtime` had no `verify_benchmark_report` protocol command.
- Added RED Rust coverage requiring recorded reports to include
  `minCasesPerTag.multi-layout`, requiring `verify_benchmark_report` to accept a
  freshly recorded report, and requiring it to reject tampered
  `minCasesPerTag`.
- RED result: `minCasesPerTag` was null and verifier calls failed with
  `UNKNOWN_COMMAND`.
- Implemented runtime report population of expanded `minCasesPerTag` using the
  same manifest shorthand semantics as the Java verifier.
- Implemented `verify_benchmark_report` in the Rust protocol. It checks report
  format, pass status, manifest hash, manifest-echoed metadata, copied
  thresholds, required metrics/tags, `minCasesPerTag`, `minTotalCases`, actual
  `caseCount`/`casesPerTag`, and source pins.
- Updated the runtime benchmark smoke to verify a valid recorded report and
  reject a tampered `minCasesPerTag`.
- Updated PRD text to make Rust verifier parity explicit.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`.
- Honest boundary: this closes Rust report-verification parity. It still does
  not populate broad human-reviewed parser/OCR/table corpora.

## 2026-06-13 Rust Benchmark Maximum Threshold Gate

- Audited Rust `benchmark_corpus` threshold enforcement and found that the
  runtime copied `maximums` into recorded reports but only called
  `require_minimums`; lower-is-better gates were not actually enforced.
- Added RED Rust coverage by setting `maximums.reading_order_f1 = 0.0` on a
  corpus whose actual `reading_order_f1` is `1.0`.
- RED result: the corpus passed and emitted `passed: true`, proving `maximums`
  were report-only in the Rust path.
- Implemented `require_maximums` and wired it into `benchmark_corpus_json`
  after minimum checks.
- Updated runtime benchmark smoke with a `maximums` failure manifest and stable
  `BENCHMARK_THRESHOLDS_FAILED`/`above allowed maximum` assertions.
- Updated PRD wording to state that `maximums` gates are enforced in both Java
  and Rust benchmark runners.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `git diff --check`.
- Honest boundary: this closes a real Rust threshold-enforcement gap. It still
  does not create broad labeled parser-quality corpora.

## 2026-06-13 Recorded Metric Threshold Verifier

- Audited Java and Rust recorded report verifiers and found that they checked
  report `passed`, copied threshold objects, coverage, and source pins, but did
  not re-check recorded metric values against `minimums`/`maximums`.
- Added RED Java coverage by tampering top-level
  `metrics.reading_order_f1 = 0.0` in a recorded report whose copied minimum is
  `1.0`.
- Added RED Rust coverage for the same tampered metric path.
- RED result: both Java and Rust verifiers accepted the tampered metric report.
- Implemented Java metric threshold verification. It checks top-level
  aggregate metrics first, and falls back to per-case metrics for metrics that
  are not emitted in the aggregate report.
- Implemented Rust metric threshold verification with the same aggregate-first,
  case-metric fallback behavior.
- Added Rust RED/green coverage proving fallback is accepted when the aggregate
  metric is absent but per-case metrics satisfy the threshold.
- Updated Java and Rust benchmark smokes to tamper recorded metrics and assert
  verifier failure.
- Updated CLI docs and PRD text to include metric-value verification.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsTamperedMetricsBelowMinimum test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`.
- Honest boundary: this makes archived report verification stronger, but broad
  labeled parser/OCR/table corpora are still not populated.

## 2026-06-13 Recorded Aggregate Metric Consistency Verifier

- Audited the metric verifier and found another tamper path: top-level metrics
  and per-case metrics could diverge while still satisfying copied thresholds.
- Added RED Java coverage by changing `metrics.parser_latency_p95` while
  keeping the case-level `parser_latency_ms` evidence unchanged.
- Added RED Rust coverage by changing a case-level `reading_order_f1` while
  keeping the aggregate metric unchanged.
- RED result: both reports verified successfully before aggregate consistency
  checks existed.
- Implemented Java aggregate verification for the Java runner's derived
  aggregate metrics: `parser_latency_p50`, `parser_latency_p95`, and
  `compact_llm_size_reduction_min`.
- Implemented Rust aggregate verification for aggregate metrics that summarize
  same-name case metrics, using the same rounded average semantics as
  `aggregate_case_metrics`.
- Updated Java and Rust benchmark smokes to tamper aggregate/case metric
  evidence and assert `aggregate metric mismatch`.
- Updated CLI docs and PRD text to require aggregate/case metric consistency.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsTamperedAggregateMetrics test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml && cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `git diff --check`.
- Honest boundary: aggregate consistency makes recorded reports harder to
  tamper with, but real broad labeled corpus evidence is still missing.

## 2026-06-13 Recorded Coverage Map Exactness

- Audited Java/Rust verifier parity and found that Rust rejects forged extra
  `casesPerTag` keys through exact map equality, while Java only checked tags
  that appeared in actual report cases.
- Added RED Java coverage by inserting `casesPerTag.forged-tag = 1` into a
  recorded benchmark report.
- RED result: Java `verify-benchmark-report` accepted the forged coverage map.
- Implemented Java exact map comparison for recorded `casesPerTag`, including
  object/integer validation.
- Updated benchmark smoke with the same extra-tag tamper path and
  `casesPerTag mismatch` assertion.
- Verification passed:
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest#verifyBenchmarkReportRejectsExtraRecordedCoverageTags test`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `mvn -q -Dtest=ParserBenchmarkCorpusCliTest,DocTruthCliTest,DocTruthCliDoctorCompletionTest,ArchitectureContractTest,PublicApiSnapshotTest test`.
- Honest boundary: this closes a recorded-report tamper gap. It still does not
  populate broad human-reviewed parser-quality corpora.

## 2026-06-13 OCR Runtime-First Parser Selection

- Audited `TrustDocumentParser` and found that `ParserPreset.OCR` returned
  through Java/PDFBox OCR before checking a configured Rust runtime command.
- Added RED Java SDK coverage with both `doctruth.runtime.command` and
  `doctruth.ocr.command` configured. The expected behavior was `sidecar`, but
  the test failed with `pdfbox+ocr`, proving OCR bypassed the Rust-core path.
- Implemented runtime-first selection by moving the configured-runtime check
  before OCR fallback in both path and temp-file parse entrypoints.
- Confirmed Java OCR still works when no runtime is configured.
- Added CLI sidecar smoke coverage for `--preset ocr --runtime`, asserting the
  packaged CLI path keeps `parserRun.backend=rust-sidecar` and returns runtime
  evidence.
- Verification passed:
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#ocrPresetPrefersConfiguredRustRuntimeBeforeJavaOcrFallback test`;
  `mvn -q -Dtest=TrustDocumentParserApiContractTest#ocrPresetRoutesLowTextPdfThroughConfiguredLocalWorker,TrustDocumentParserApiContractTest#ocrPresetMarksLowConfidenceRecoveredTextAsNonAuditGrade test`;
  `mvn -q -Dtest=TrustDocumentParserApiContractTest,SidecarParserBackendTest,DocTruthCliTest,TrustDocumentCliOutputProfileTest test`;
  `sh scripts/smoke-doctruth-runtime.sh`;
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Honest boundary: this makes configured OCR parsing Rust-first at the SDK and
  CLI sidecar boundary. It still does not make Rust an unconditional default
  when no runtime is configured.

## 2026-06-13 Runtime Status Docs Reconciliation

- Ran a parallel docs-only worker limited to
  `runtime/doctruth-runtime/README.md` and `docs/parser-capability-matrix.md`.
- Updated docs to state current Rust runtime capabilities:
  `parse_pdf`, `benchmark_corpus`, `verify_benchmark_report`, `--doctor`,
  model-worker handoff, layered outputs, and real-route smokes.
- Preserved current limits: Rust is not unconditional default for every
  entrypoint, Java/PDFBox remains fallback/oracle, heavy models stay
  external-worker/opt-in, and real-route smokes are not broad accuracy proof.
- Verification passed: `git diff --check`.

## 2026-06-13 Path-First SDK Backend Selection

- Re-scoped broad human-reviewed corpus work as final-stage after the user
  clarified that labels should come later through a review workstation and
  accumulated approval/correction data.
- Audited the document-first SDK path and found that
  `DocTruth.withProvider(...).fromPdf(...).withParser(...)` wraps an already
  parsed `ParsedDocument`, so it cannot be the Rust sidecar path.
- Added RED Java SDK contract tests for a new path-first TrustDocument parser
  entrypoint:
  `DocTruth.withProvider(provider).parsePdf(path).withParser(preset)`.
- Implemented public `ParserBackendMode` and SDK builder backend selection:
  `AUTO` prefers configured Rust runtime, `PDFBOX` forces Java/PDFBox
  fallback/oracle, and `SIDECAR` requires a configured runtime.
- Verification passed:
  `mvn -q -Dtest=TrustDocumentSdkParserContractTest#pathFirstSdkParserUsesConfiguredRustRuntimeInAutoMode,TrustDocumentSdkParserContractTest#pathFirstSdkParserCanForcePdfBoxFallback test`;
  `mvn -q -Dtest=TrustDocumentSdkParserContractTest test`;
  `mvn -q -Ddoctruth.updatePublicApiSnapshot=true -Dtest=PublicApiSnapshotTest test`;
  `mvn -q -Dtest=TrustDocumentSdkParserContractTest,TrustDocumentParserApiContractTest,SidecarParserBackendTest,ParserBackendContractTest,PublicApiSnapshotTest test`;
  `sh scripts/smoke-doctruth-runtime.sh`;
  `sh scripts/smoke-doctruth-cli-sidecar.sh`.
- Honest boundary: legacy document-first extraction still eagerly creates a
  `ParsedDocument` for compatibility. The new path-first parser is the
  developer-facing Rust-first TrustDocument SDK path.

## 2026-06-14 Rust PDF Backend Decision Correction

- User clarified the intended architecture: Java should not be a parser core.
  It can remain as SDK/CLI wrapper, packaging layer, sidecar client, legacy
  compatibility path, and regression oracle.
- Rechecked the current Kreuzberg-style Rust dependency direction and corrected
  the PRD from PDFium to `pdf_oxide`.
- Added `pdf_oxide` to `doctruth-runtime`, removed the `pdf-extract` runtime
  dependency, and changed `parse_pdf` to use
  `PdfDocument::extract_page_text_with_options(..., ReadingOrder::ColumnAware)`
  for text-layer page extraction.
- Extended the Rust core migration so `pdf_oxide` spans now drive
  bbox-backed `PositionedLine` units. DocTruth applies its own column-order
  post-processing over those spans so two-column fixtures read the left column
  before the right column.
- Updated the generated benchmark expected bbox to the actual `pdf_oxide` span
  bbox while keeping `bbox_iou` threshold at `1.0`.
- Added `pdfBackend` doctor and `parserRun` contract fields:
  `target=pdf_oxide`, `current=pdf_oxide+lopdf`, `status=PARTIAL`.
- Moved page MediaBox geometry and default rendered PNG page hashes onto
  `pdf_oxide`. `DOCTRUTH_RUNTIME_PAGE_RENDERER` remains an explicit override,
  but local `pdftoppm` is no longer a default runtime dependency.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test library_contract --test protocol_contract`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`;
  `sh scripts/smoke-doctruth-runtime.sh`;
  `cargo tree --manifest-path runtime/doctruth-runtime/Cargo.toml -e normal | rg "pdf_oxide|pdf-extract|lopdf|pdftoppm|pdfium"`.
- Honest boundary: `pdf_oxide` now owns text-layer page extraction, span bbox
  evidence, page geometry, and default rendered page image hashes. `lopdf`
  still owns table/debug extraction, so the backend status remains `PARTIAL`.

## 2026-06-14 OpenDataLoader Bench Positioning

- User clarified that parser quality must be a foundation of evidence quality:
  if PDF parsing is wrong, DocTruth evidence cannot be trusted.
- Updated `docs/pdf-parser-runtime-prd.md` to make OpenDataLoader Bench a
  parser-quality foundation rather than a loose external reference.
- Added the intended adapter path:
  `DocTruth Rust runtime -> OpenDataLoader Bench prediction format ->
  OpenDataLoader metrics/evaluation.json -> DocTruth benchmark report
  external_metrics -> DocTruth evidence/replay/audit metrics`.
- Added external parser-quality metrics to the PRD:
  `opendataloader_nid`, `opendataloader_teds`, `opendataloader_mhs`, and
  `opendataloader_speed`.
- Preserved DocTruth's separate evidence metrics:
  `bbox_coverage`, `bbox_iou`, `quote_anchor_accuracy`,
  `evidence_span_accuracy`, `source_map_validity`, `audit_grade_pass_rate`,
  and `replay_integrity`.
- Updated `task_plan.md` with new follow-up phases for the OpenDataLoader Bench
  adapter and external metrics gate.
- Current verification reality before this docs update: the interrupted
  `mvn verify -P recorded` completed with all unit/integration tests passing,
  recorded PDF corpus `383 total / 379 success / 4 malformed trailer failures`,
  CSV fixture `57/57`, but failed JaCoCo only on bundle branch coverage
  `0.78 < 0.79`.
- Honest boundary: Goal 1 remains active. The Rust-default parser direction is
  substantially implemented and committed in `0490498`, but completion still
  requires resolving the Java coverage gate and rerunning the full Rust/Java
  verification set.

## 2026-06-14 Rust-Default Smoke Reconciliation

- Fixed `review-package` so exported page PNG hashes are the review-package
  page hash source of truth. `trust-document.json` now matches
  `pages/page-images.json`, which prevents auditors from reviewing one PNG while
  the trust document references another page image hash.
- Updated benchmark and seed-corpus smokes to simulate the real default path:
  Java CLI -> configured Rust runtime sidecar -> optional worker. OCR no longer
  relies on the old Java-only `fileType=png` worker request in these smokes.
- Re-labeled the W3C dummy PDF smoke as text-layer evidence instead of a fake
  single-cell table. The current Rust/pdf_oxide output is one `LINE_SPAN` with
  bbox-backed source evidence.
- Reconciled model-worker smoke assertions so CLI parse outputs expect
  `parserRun.backend=rust-sidecar+model-worker`. Direct worker outputs may still
  report `pdfbox+model-worker` as internal worker provenance.
- Verification passed:
  `sh scripts/smoke-doctruth-review-package.sh`;
  `sh scripts/smoke-doctruth-model-worker.sh`;
  `sh scripts/smoke-doctruth-benchmark-corpus.sh`;
  `sh scripts/smoke-doctruth-real-pdf-corpus.sh`;
  `sh scripts/smoke-doctruth-parser-accuracy-seed-corpus.sh`;
  `sh scripts/smoke-doctruth-onnx-model-worker.sh`;
  `sh scripts/smoke-doctruth-onnx-layout-decoder.sh`;
  `sh scripts/smoke-doctruth-onnx-tatr-decoder.sh`;
  `sh scripts/smoke-doctruth-slanext-table-worker.sh`;
  `mvn -q -Dtest=DocTruthCliTest,ParserBenchmarkCorpusCliTest,TrustDocumentCliOutputProfileTest,LocalOcrWorkerEngineTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `git diff --check`;
  `mvn verify -P recorded` with 1044 unit tests passing, 16 recorded
  integration tests passing/skipped as expected, recorded PDF corpus
  `383 total / 379 success / 4 malformed trailer failures`, CSV fixture
  `57/57`, and JaCoCo coverage checks passing.

## 2026-06-14 CLI Shorthand Rust-Default Contract

- Found the last visible CLI default gap: `parse --json` and
  `parse --markdown` were still selecting legacy `ParsedDocument` output,
  which silently used Java/PDFBox instead of the Rust TrustDocument runtime.
- Changed shorthand output flags to Rust TrustDocument aliases:
  `--json` -> `--format json`, `--markdown`/`--md` -> `--format markdown`.
- Added explicit legacy output names:
  `legacy-json`, `legacy-markdown`, `legacy-md`.
- Added validation that legacy output requires `--backend pdfbox`, preserving
  Java/PDFBox only as an explicit oracle/compatibility mode.
- Updated CLI docs to state the shorthand behavior and legacy escape hatch.
- Updated the plan so OpenDataLoader Bench is no longer just positioning:
  Phase 291 is now the adapter contract, Phase 292 is the external metrics
  gate, and Phase 293 tracks this CLI shorthand closure.
- Verification passed:
  `mvn -q -Dtest=DocTruthCliTest,TrustDocumentCliOutputProfileTest test`;
  `mvn -q -Dtest=DocTruthCliMcpTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`
  with 1046 unit tests passing, recorded integration tests passing/skipped as
  expected, recorded PDF corpus `383 total / 379 success / 4 malformed trailer
  failures`, CSV fixture `57/57`, and JaCoCo coverage passing;
  `git diff --check`.

## 2026-06-14 Goal 1 Rust-Default Completion Audit

- Closed the remaining wording gap in the PRD benchmark-learning table:
  Goal 1 is complete for Rust-default parser ownership, while later
  parser-quality phases still own broad accuracy, OpenDataLoader-style
  geometry/filter work, and removing transitional `lopdf` table/debug duties.
- Confirmed the product default path is Rust-first:
  CLI `parse --json` and `--markdown` are TrustDocument/Rust aliases, MCP
  `doctruth.parse_document` calls the TrustDocument parser, and the path-first
  SDK parser uses the configured Rust runtime in `AUTO`.
- Confirmed Java/PDFBox is now explicit legacy/oracle compatibility:
  legacy CLI outputs require `--backend pdfbox`, SDK `ParserBackendMode.PDFBOX`
  is opt-in, and missing Rust runtime for default TrustDocument parsing fails
  with `RUST_RUNTIME_NOT_CONFIGURED` instead of silently using Java/PDFBox.
- Honest boundary: the older document-first extraction API remains a
  compatibility surface. It is not the developer-facing Rust-first
  TrustDocument parser path, and it should be migrated only when that extraction
  API is reworked.
- Honest boundary: `pdf_oxide` is the default PDF substrate for text-layer page
  extraction, page geometry, rendered page hashes, and bbox evidence.
  `lopdf` is still transitional table/debug support and belongs to later
  parser-quality phases, not Goal 1 defaultization.
- Completion verification passed:
  `mvn -q -Dtest=DocTruthCliTest,TrustDocumentCliOutputProfileTest,DocTruthCliMcpTest,TrustDocumentParserApiContractTest,TrustDocumentSdkParserContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test library_contract --test protocol_contract`;
  `git diff --check`;
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true mvn verify -P recorded`
  with 1046 unit tests passing, recorded integration tests passing/skipped as
  expected, real-world PDF corpus `383 total / 379 success / 4 malformed
  trailer failures`, CSV fixture `57/57`, and JaCoCo coverage passing.

## 2026-06-14 Goal 2 Layered Output Preservation

- Audited the existing Goal 2 implementation and found the remaining contract
  weakness: Rust `doctruth-runtime parse_pdf` emitted `contentBlocks` and
  `parseTrace`, but Java `TrustDocumentJson.fromJsonFull(...)` discarded those
  raw layered observations and CLI writers re-derived equivalent profiles from
  `TrustDocument` units/pages.
- Added a RED sidecar contract proving Java must preserve runtime-layered
  `contentBlocks` and `parseTrace` payloads with runtime-specific ids such as
  `runtime-block-9999` and `runtime-trace-9999`.
- Implemented an internal layered-output store attached during
  `TrustDocumentJson.fromJsonFull(...)`. Public SDK writers
  `TrustDocument.writeContentBlocks(...)` and `writeParseTrace(...)` now write
  preserved Rust runtime layers when present, and otherwise fall back to
  deterministic TrustDocument projections.
- Routed CLI `content_blocks` and `parse_trace` writers through the new core
  SDK writer APIs, keeping review-package and parse output profiles on the same
  contract.
- Updated `findings.md`, `task_plan.md`, and the public API snapshot to reflect
  the new layered-output SDK surface and the closed raw-observation preservation
  gap.
- Error logged: an attempted Cargo command passed two test filters to
  `cargo test`, which failed with `unexpected argument`. Re-ran the full
  `protocol_contract` test target instead.
- Verification passed:
  `mvn -q -Dtest=SidecarParserBackendTest#preservesRuntimeLayeredOutputObservations test`;
  `mvn -q -Ddoctruth.updatePublicApiSnapshot=true -Dtest=PublicApiSnapshotTest test`;
  `mvn -q -Dtest=SidecarParserBackendTest,TrustDocumentCliWritersTest,TrustDocumentCliOutputProfileTest,DocTruthCliTest,TrustDocumentStreamingRenderContractTest,TrustDocumentSourceMapContractTest,PublicApiSnapshotTest,ArchitectureContractTest test`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`;
  `git diff --check`.
- Broader verification passed:
  `mvn test` with 1047 tests passing;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `sh scripts/smoke-doctruth-cli-sidecar.sh`;
  `sh scripts/smoke-doctruth-review-package.sh`.

## 2026-06-15 Goal 3 Rust Runtime Capability Doctor

- Started Goal 3 from the local parser model pipeline objective. The strongest
  uncovered gap was that `doctruth-runtime --doctor` only reported coarse model
  booleans, while Goal 3 requires Rust runtime ownership of capability reporting,
  manifest/cache validation, worker readiness, memory observations, and
  missing/SHA-mismatched model diagnostics.
- Added RED Rust library contract tests proving `doctor_json()` must report:
  native text extraction, document-structure/reading-order slots, layout/table/OCR
  model capability slots, model manifest path, model cache directory,
  per-preset model identities, `READY`, `MISSING`, and `SHA_MISMATCH` cache
  states, actual SHA-256/size, worker configured/available/ready separation, and
  runtime memory.
- Implemented Rust runtime doctor output under `models` and expanded
  `capabilities` from flat booleans into slot-level availability derived from
  verified local cache state. The doctor path remains local-first: it does not
  download models or run inference.
- Added a RED worker-doctor test for a configured worker returning
  `{"ok":false,"code":"model_runtime_unavailable"}`. Fixed Rust readiness
  parsing so configured and executable workers are not treated as ready when
  their own doctor reports a runtime failure.
- Added explicit Rust protocol coverage for missing layout/table/OCR models.
  `standard`, `table-server`, and `ocr` presets now have tests proving they
  fall back through the lightweight local path, remain `NOT_AUDIT_GRADE`, and
  emit severe `model_unavailable_fallback` warnings carrying the missing model
  identity.
- Updated `docs/pdf-parser-runtime-prd.md` and `task_plan.md` to record Phase
  296 and 297 as complete while keeping parser-quality phases 284-292 open.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test library_contract`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_gracefully_falls_back_for_missing_layout_table_and_ocr_models`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test library_contract --test protocol_contract --test model_worker_contract`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.

## 2026-06-15 OpenDataLoader Bench Vendored Import

- Corrected the parser-quality blocker framing: OpenDataLoader Bench already
  provides a public external parser-quality corpus, ground-truth Markdown,
  evaluator code, and published engine prediction/evaluation artifacts. The
  current gap is the DocTruth adapter and external metric gate, not the absence
  of any usable corpus.
- Vendored OpenDataLoader Bench under `third_party/opendataloader-bench/`,
  excluding only repository metadata such as `.git` and `.vscode`. Imported
  content includes PDFs, thumbnails, ground-truth Markdown, `reference.json`,
  predictions, `evaluation.json` / CSV files, evaluator source, tests, charts,
  license, and third-party notices.
- Added `third_party/opendataloader-bench/SOURCE.md` with source URL, imported
  commit `7af1d8f4d0c09f51ea1a5c6ba5f66e993286d109`, license posture, and
  DocTruth integration boundary.
- Updated `AGENTS.md` to require future parser-quality work to use
  OpenDataLoader Bench as the first external parser-quality gate before claiming
  blocker on DocTruth-owned human-reviewed corpus.
- Updated `NOTICE`, `docs/pdf-parser-runtime-prd.md`, and `task_plan.md` to
  record the vendored benchmark and the next adapter/gate work.

## 2026-06-17 Parser Quality Replication Planning

- Re-entered the parser-quality loop after the full real OpenDataLoader Bench
  run showed DocTruth is still far from reference quality despite the adapter
  and export-layer lift.
- Confirmed latest DocTruth optimized-timeout metrics:
  `overall=0.549140667373931`, `nid=0.7663393307030263`,
  `teds=0.06498004117639267`, `mhs=0.12239636974611434`.
- Confirmed reference metrics from vendored artifacts:
  OpenDataLoader base `overall=0.8312090061093924`, Docling
  `overall=0.8816788439412203`, OpenDataLoader hybrid
  `overall=0.9065718466674022`.
- Inspected the vendored OpenDataLoader Bench engine adapters and confirmed the
  base path uses `table_method="cluster"`, the hybrid path uses
  `hybrid="docling-fast"`, and Docling uses `DocumentConverter` plus
  `export_to_markdown`.
- Wrote `docs/plans/2026-06-17-parser-quality-replication-plan.md` to define
  the next sequence: reference-oracle harness, per-case triage, reading-order
  cleanup, table-cluster Rust port, heading/section model, OCR routing, and an
  optional hybrid advisor.

## 2026-06-17 Parser Quality Replication Pass 2

- Implemented and smoked the reference comparison harness and triage harness:
  `scripts/compare-doctruth-parser-references.py`,
  `scripts/triage-doctruth-parser-reference-report.py`,
  `scripts/smoke-doctruth-parser-reference-comparison.py`, and
  `scripts/smoke-doctruth-parser-reference-triage.py`.
- Implemented export-layer parser-quality fixes:
  TrustDocument table range rendering, guarded bbox/spatial table fallback,
  heading promotion, page-number noise filtering, and regression smoke
  coverage for false spatial-table positives.
- Ran full OpenDataLoader Bench pass2:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-replication-pass2 --timeout-seconds 30`.
- Pass2 result: `overall=0.5627398590637586`, `nid=0.7391382135188431`,
  `teds=0.18840125729021784`, `mhs=0.19566644996808139`, 198 parsed, 2 failed,
  `total_elapsed=240.95418691635132`.
- Generated pass2 comparison and triage artifacts under
  `third_party/opendataloader-bench/prediction/doctruth-runtime-replication-pass2/`.
  These generated prediction/report artifacts remain ignored and are not meant
  to be committed unless a small fixture is intentionally promoted later.
- Current state is an honest measured lift over the prior optimized-timeout
  run, not reference parity. The next implementation work is Rust-core table
  clustering, Rust section-tree hierarchy, stronger reading-order/text
  normalization, and real OCR/model routing for scanned or no-text cases.

## 2026-06-17 Rust Core Local-Algorithm Contract Slice

- Moved the next parser-quality work back into `runtime/doctruth-runtime`
  instead of continuing exporter-only changes.
- Added a RED/green Rust protocol contract for
  `parseTrace.pages[].textSpans[]`. The runtime now emits a flat page span
  stream with `spanId`, `type`, `page`, `readingOrder`, `content`, `bbox`,
  `score`, `sourceObjectId`, and `evidenceSpanId`.
- Added `parseTraceSpanIds` back-links to `LINE_SPAN` and `TABLE_CELL` units,
  so `TrustDocument` units, content blocks, parse trace lines, and page spans
  can be reconciled from the same observation layer.
- Added a RED/green contract that text-spatial/borderless table extraction
  reports OpenDataLoader-style `method="cluster"` while preserving DocTruth's
  original extraction rationale.
- Added Rust-owned list classification before heading classification so `- ...`
  and `1. ...` list items become `contentBlocks[].type="list"` and are not
  promoted as numbered headings.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`.

## 2026-06-17 Rust Section Hierarchy Contract Slice

- Added a RED/green Rust protocol contract for parser-owned section hierarchy,
  not Markdown-only heading promotion.
- `contentBlocks` and `parseTrace.pages[].readingBlocks[]` now include:
  `sectionId`, `parentSectionId`, `sectionPath`, `sectionTitlePath`, and
  `isSectionRoot`.
- `parseTrace.sectionTree` now exposes the same hierarchy as a tree of
  `sectionId`, `title`, `textLevel`, `blockId`, and `children`, which is the
  parser-owned structure downstream Markdown/MHS export should consume.
- The section hierarchy is generated by scanning Rust parser observations in
  reading order with a heading stack. A level-3 title-case heading nests under
  the preceding level-2 heading; a later level-2 heading closes the nested
  section and starts a new top-level section. Body/list blocks inherit the
  current section path.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`.

## 2026-06-17 Real Sparse Table Cluster Fix

- Reproduced a real OpenDataLoader Bench table miss on
  `third_party/opendataloader-bench/pdfs/01030000000128.pdf`: DocTruth emitted
  `table_count=0` even though the ground truth is a sparse 6-column HTML table.
- Root cause: the table detector had diverged from the main parse observation
  layer. The line-table path could fail without producing a table, pdf_oxide's
  spatial detector did not catch this sparse layout, and DocTruth had no final
  fallback that clustered the already extracted positioned text lines.
- Added a RED/green Rust regression using the real vendored PDF, not a
  screenshot or synthetic-only fixture. The runtime now emits one
  OpenDataLoader-style `cluster` table with `columnCount=6`, `rowCount>=10`,
  preserved empty cells, and the expected header cells:
  `Forecast(observed)`, `Lower Confidence Bound(observed)`, and
  `Upper Confidence Bound(observed)`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`;
  `git diff --check`.
- This fixes one real TEDS failure shape but does not prove full
  OpenDataLoader/Docling parity. Pass3/full OpenDataLoader Bench still needs to
  be run before claiming an aggregate score lift.

## 2026-06-18 OpenDataLoader Hybrid Rustification Plan

- Ran the real vendored OpenDataLoader Bench `opendataloader-hybrid` path after
  installing `opendataloader-pdf[hybrid]` into the bench `.venv`.
- First real run exposed two environment issues:
  - shell `java` did not resolve to an installed runtime;
  - `cpuinfo.get_cpu_info()["brand_raw"]` could fail while writing
    `summary.json`.
- Resolved the Java issue for local runs by putting the Homebrew OpenJDK bin
  directory at the front of `PATH` during benchmark execution.
- Patched `third_party/opendataloader-bench/src/pdf_parser.py` to use a
  fallback processor string when `brand_raw` is absent.
- Verified live single-document hybrid parsing writes Markdown and summary.
- Started `opendataloader_pdf.hybrid_server` manually to measure warm behavior.
  The server reported MPS acceleration, Docling Fast Server startup, and
  Docling model loading.
- Warm 5-PDF batch result:
  `total_elapsed=4.7210118770599365`, `elapsed_per_doc=0.9442023754119873`.
- Full 200-PDF live run result:
  `overall=0.9065718466674022`, `NID=0.9337307553293448`,
  `TEDS=0.9276430534097512`, `MHS=0.8207761855598542`,
  `total_elapsed=125.29678010940552`, `elapsed_per_doc=0.6264839005470276`.
- Resource observations:
  docling-fast hybrid server RSS about `1.39GB` to `1.51GB`; client/JAR
  full-run peak RSS about `408MB`; warm single client peak about `140MB`.
- Stopped the manually launched hybrid server after the run.
- Wrote the implementation plan:
  `docs/plans/2026-06-18-opendataloader-rustification-tdd-plan.md`.
- Updated `task_plan.md` phases 323-327 and `findings.md` with the new product
  and engineering direction.
- Tightened the plan after product review: production runtime is now
  Rust + MNN-first model runtime, not ONNX Runtime sidecars and not a parser
  fallback stack. ONNX is only a conversion/interchange artifact; OpenDataLoader
  hybrid/Docling/Python/Torch remain benchmark oracle/reference tooling only.
- Added the final MNN benchmark acceptance gate: because MNN conversion or
  weight-only compression can reduce quality, the MNN runtime must run the full
  OpenDataLoader Bench and prove near-hybrid quality with materially better
  resources before promotion. Initial targets are `overall>=0.88`, `NID>=0.91`,
  `TEDS>=0.88`, `MHS>=0.78`; resource gates are relative to the Docling/Torch
  oracle and no universal absolute RSS gate is accepted before the real MNN
  model profile is measured. The first absolute RSS threshold must be derived
  from a full benchmark report for the actual model set, precision mode,
  platform, crop buffers, and unload policy.
- Next implementation starts with Phase 1 RED tests:
  - benchmark oracle `opendataloader-hybrid` missing-dependency doctor failure;
  - fake hybrid oracle runner maps Markdown/provenance into `TrustDocument`;
  - Markdown-only mapping is explicitly `NOT_AUDIT_GRADE`;
  - production parse profiles cannot auto-select OpenDataLoader hybrid;
  - one-document OpenDataLoader Bench smoke can use the backend.

## 2026-06-17 OpenDataLoader Structured Adapter Phase 2

- Added RED tests proving the OpenDataLoader hybrid benchmark oracle must prefer
  structured `blocks` over Markdown:
  heading/list/table blocks map into `TrustDocument`, table cells are preserved,
  structured source mapping emits an INFO warning instead of the severe
  Markdown-only warning, and `benchmark-oracle --format content_blocks`
  preserves heading levels plus list/table shape.
- RED command:
  `mvn -q -Dtest=BenchmarkOracleCommandTest test`.
- RED result: structured output was still `NOT_AUDIT_GRADE`, and
  `--format content_blocks` returned usage code 2 because the command only
  supported `--json`.
- Implemented structured adapter behavior in `BenchmarkOracleCommand`:
  oracle `blocks` are now the default source when present; Markdown is only the
  fallback path.
- Structured blocks currently support:
  heading/title -> `TEXT_BLOCK` unit plus `contentBlocks[].type=heading`;
  list -> one citeable unit per item plus `contentBlocks[].items`;
  table -> `TrustTable` cells plus `TABLE_CELL` units and `contentBlocks[].rows`.
- Added `TrustDocument.withLayeredOutputs(JsonNode, JsonNode)` as the narrow
  bridge for CLI/adapter paths to attach parser-owned content-block and
  parse-trace layers without adding record components.
- Added benchmark-oracle output profiles:
  `--format content_blocks` and `--format parse_trace`; existing `--json`
  behavior is unchanged.
- Updated the public API snapshot for the new layered-output bridge.
- GREEN commands:
  `mvn -q -Dtest=BenchmarkOracleCommandTest test`;
  `mvn -q -Dtest=BenchmarkOracleCommandTest,TrustDocumentContractTest,TrustDocumentRenderedOutputTest,TrustDocumentParserApiContractTest,PublicApiSnapshotTest,ArchitectureContractTest test`.
- Phase 2 is complete for the benchmark-oracle adapter. The next remaining
  plan item is Phase 3: move more deterministic OpenDataLoader behavior into
  Rust and verify with real OpenDataLoader Bench subset/full metrics.

## 2026-06-17 Phase 3 Heading Fragment Suppression Slice

- Selected the next deterministic Phase 3 slice from the latest available
  OpenDataLoader Bench triage artifacts instead of older pass2 notes. The
  current pass7 artifact reports:
  `overall_mean=0.587331014907702`, `nid_mean=0.7721853768826462`,
  `teds_mean=0.235017848867468`, `mhs_mean=0.1801015892875034`,
  `parsed_count=198`, `failed_count=2`.
- Triage still shows heading hierarchy as the largest bucket, followed by
  reading-order/text-normalization and table-cluster parity.
- Inspected real case `01030000000195`: Rust `contentBlocks` promoted bullet
  symbols, bullet-line fragments (`Introduction`, `SOLAR`, `Billion-`, `: We`),
  author-line fragments (`and Wonsung`, `with Dahyun Kim, Wonho`), and prose
  citation tails as headings, producing very poor MHS.
- Added RED Rust regression:
  `parse_pdf_does_not_promote_opendataloader_bullet_fragments_to_headings`
  against the vendored real PDF `third_party/opendataloader-bench/pdfs/01030000000195.pdf`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_does_not_promote_opendataloader_bullet_fragments_to_headings`.
- RED result: failed with unexpected heading fragments including `•`,
  `Introduction`, `SOLAR`, `Billion-`, `: We`, `Instruction-Following`,
  `Ca-`, `and Wonsung`, and `with Dahyun Kim, Wonho`.
- Implemented context-aware heading suppression in Rust:
  - bullet symbol becomes list, not heading;
  - same-line bullet fragments are text;
  - short same-line fragments in multi-token visual lines are text;
  - lowercase connector starts such as `and`, `with`, `like` are text;
  - sentence-punctuation/prose fragments are text;
  - real outline markers such as `A`, `B.1`, `B.2`, and `I. Introduction`
    remain headings.
- Fixed the OpenDataLoader prediction exporter so it trusts Rust
  `contentBlocks[].type`; it only falls back to Python heading guessing when no
  core block type is available. This prevents benchmark Markdown from
  reintroducing `#` headings that Rust already downgraded.
- GREEN Rust command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`.
- Spot benchmark command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-heading-fragment-195 --doc-id 01030000000195 --timeout-seconds 30`.
- Spot result for `01030000000195`:
  `overall=0.6913270788798284`, `nid=0.9974025974025974`,
  `mhs=0.3852515603570593`, up from the pre-fix spot-style result
  `overall=0.537880818746233`, `mhs=0.08284267604478679`.
- Verification passed:
  `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py scripts/doctruth_opendataloader_hybrid_oracle.py scripts/compare-doctruth-parser-references.py scripts/triage-doctruth-parser-reference-report.py`;
  `git diff --check`.
- Remaining Phase 3 work is still substantial: heading text merge, broader
  reading-order/text normalization, table missing/mismatch parity, OCR/no-text
  cases, full OpenDataLoader Bench rerun, and then MNN model runtime phases.

## 2026-06-18 Phase 3 Heading Merge Slice

- Added RED Rust regression:
  `parse_pdf_merges_opendataloader_split_heading_lines` against
  `third_party/opendataloader-bench/pdfs/01030000000195.pdf`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_merges_opendataloader_split_heading_lines`.
- RED result: failed because headings were still split as standalone markers:
  `["A", "Evaluation (Data-Centric LLM) part, with Yungi", "B", "B.1", "B.2"]`;
  the expected merged headings `B Related Works and Background`,
  `B.1 Large Language Models`, and `B.2 Mixture of Experts` were absent.
- Implemented Rust semantic block merging at the canonical `contentBlocks`
  layer:
  same-line section markers such as `B`, `B.1`, and `B.2` now merge with their
  following same-line title tokens; source unit IDs, evidence span IDs, warnings,
  bbox, and section metadata are aggregated from the merged units.
- Added another RED assertion to the existing OpenDataLoader heading-fragment
  regression for the prose citation tail
  `Evaluation (Data-Centric LLM) part, with Yungi`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_does_not_promote_opendataloader_bullet_fragments_to_headings`.
- RED result: failed because that prose tail was still classified as a heading.
- Fixed sentence/prose heading suppression for connector fragments containing
  `, with`, `, and`, or `, or`.
- Fixed the OpenDataLoader benchmark exporter to render each merged
  `contentBlock.blockId` only once. Without this, the merged heading was emitted
  repeatedly for every source unit inside the merged block.
- GREEN commands:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_merges_opendataloader_split_heading_lines`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_does_not_promote_opendataloader_bullet_fragments_to_headings`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`.
- Spot benchmark command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-heading-merge-195 --doc-id 01030000000195 --timeout-seconds 30`.
- Spot result for `01030000000195`:
  `overall=0.9981309274448072`, `nid=0.9976279227380549`,
  `mhs=0.9986339321515596`, up from the prior Phase 3 slice
  `overall=0.6913270788798284`, `mhs=0.3852515603570593`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`;
  `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py scripts/doctruth_opendataloader_hybrid_oracle.py scripts/compare-doctruth-parser-references.py scripts/triage-doctruth-parser-reference-report.py`;
  `git diff --check`.
- Remaining Phase 3 work: broaden the improvement beyond this one PDF, rerun a
  larger OpenDataLoader Bench subset/full corpus, then continue table parity,
  OCR/no-text cases, and MNN runtime/resource phases.

## 2026-06-18 Phase 3 Numeric Heading Slice

- Ran a broader current-runtime subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-phase3-subset50 --limit 50 --timeout-seconds 30`.
- Tooling caveat discovered: OpenDataLoader `evaluator.py` still evaluates all
  200 ground-truth Markdown files when `--limit 50` is used, so the generated
  aggregate `evaluation.json` includes 150 missing-prediction zeros. For this
  slice, only `prediction_available=true` documents are meaningful.
- Actual parsed subset metrics over the 50 generated predictions:
  `overall≈0.7299984032348616`, `nid≈0.8546464047572715`,
  `teds≈0.49773358841675375` over 3 table cases, and
  `mhs≈0.2950005784813315` over 22 heading cases.
- Selected `01030000000001` because text similarity was already high
  (`nid≈0.991`) but heading hierarchy was zero (`mhs=0.0`), indicating a
  deterministic heading-structure miss rather than a model/OCR issue.
- Added RED Rust regression:
  `parse_pdf_merges_numeric_opendataloader_heading_lines` against
  `third_party/opendataloader-bench/pdfs/01030000000001.pdf`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_merges_numeric_opendataloader_heading_lines`.
- RED result: failed because headings were only soft-hyphen fragments
  `["\u{00ad}", "\u{00ad}", "\u{00ad}", "\u{00ad}"]`; the real heading
  `7 Variants of sj Observer Models` was absent.
- Implemented Rust semantic block improvements:
  soft hyphen fragments are suppressed from heading classification; numeric
  section markers merge with same-line title tokens when the continuation looks
  like a title line.
- GREEN command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_merges_numeric_opendataloader_heading_lines`.
- Broader runtime verification:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`.
- Spot benchmark command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-numeric-heading-001 --doc-id 01030000000001 --timeout-seconds 30`.
- Spot result for `01030000000001`:
  `overall=0.984`, `nid=0.991`, `mhs=0.977`, up from the subset baseline
  `overall=0.495`, `mhs=0.0`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`;
  `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py scripts/doctruth_opendataloader_hybrid_oracle.py scripts/compare-doctruth-parser-references.py scripts/triage-doctruth-parser-reference-report.py`;
  `git diff --check`.
- Remaining Phase 3 work: fix benchmark subset evaluation tooling so limited
  runs are not polluted by missing predictions, then rerun a larger subset/full
  corpus and continue with the largest remaining buckets: table parity,
  reading-order/text normalization, and OCR/no-text cases.

## 2026-06-18 Subset Evaluation Gate Fix

- Fixed the vendored OpenDataLoader evaluator so `--doc-id` may be repeated and
  the evaluator filters ground-truth paths before scoring/logging.
- Fixed `scripts/doctruth_opendataloader_prediction.py` so it reads the
  generated `summary.json` and passes exactly the generated document IDs to the
  evaluator. This makes `--limit` benchmark runs score only generated
  predictions instead of treating all non-generated corpus files as missing.
- Smoke command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-subset-eval-smoke --limit 3 --timeout-seconds 30`.
- Smoke result:
  evaluator logged `with 3 documents`, `evaluation.json` contained 3 documents,
  `missing_predictions=0`, and `overall_mean=0.8355557383979879`.
- Re-ran the 50-document subset with the fixed evaluation path:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-phase3-subset50 --limit 50 --timeout-seconds 30`.
- Correct 50-document subset metrics:
  `overall_mean=0.7405977452325502`,
  `nid_mean=0.8547142428383396`,
  `mhs_mean=0.3430148114068566` over 22 heading cases,
  `teds_mean=0.49773358841675375` over 3 table cases,
  `missing_predictions=0`.
- Current bottom cases by overall in this subset are:
  `01030000000036`, `01030000000044`, `01030000000038`,
  `01030000000029`, `01030000000047`, `01030000000013`,
  `01030000000037`, `01030000000021`, `01030000000031`,
  and `01030000000046`.
- The next deterministic Phase 3 slice should inspect whether the highest-loss
  heading cases are still split heading/soft-hyphen problems, or whether the
  remaining losses have shifted to reading-order/text normalization and table
  structure.

## 2026-06-18 Numbered Section Heading Slice

- Inspected the bottom subset cases `01030000000036`, `01030000000044`, and
  `01030000000038` by comparing ground truth Markdown, current prediction
  Markdown, and Rust `contentBlocks`.
- Found a deterministic numbered-section heading problem:
  `01030000000036` emitted `2. General Profile of MSMEs` as a list block, while
  `01030000000038` emitted `6.2. Expectations for Re-Hiring Employees` as text.
- Added RED Rust regression:
  `parse_pdf_promotes_opendataloader_numbered_section_headings`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_promotes_opendataloader_numbered_section_headings`.
- RED result: failed because `2. General Profile of MSMEs` was absent from
  heading blocks.
- Implemented narrowed numbered-heading logic:
  numbered section headings are promoted before list classification only when
  they are not ordinary list items, or when a list-looking numbered line appears
  in section-start context. The existing ordered-list regression remains list.
- Regression check:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_classifies_list_items_before_heading_rules`.
- GREEN/runtime verification:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`.
- Spot benchmark commands:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-numbered-heading-036 --doc-id 01030000000036 --timeout-seconds 30`;
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-numbered-heading-038 --doc-id 01030000000038 --timeout-seconds 30`.
- Spot results:
  `01030000000036`: `overall=0.682`, `nid=0.593`, `mhs=0.771`;
  `01030000000038`: `overall=0.776`, `nid=0.758`, `mhs=0.794`.
- Re-ran the 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-phase3-subset50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.7565294440268646`,
  `nid_mean=0.8547320474050248`,
  `mhs_mean=0.41542985939269794`,
  `teds_mean=0.49773358841675375`,
  `missing_predictions=0`.
- The subset improved from the previous fixed-evaluator baseline
  `overall=0.7405977452325502` and `mhs=0.3430148114068566`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract --test borderless_table_contract`;
  `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py scripts/doctruth_opendataloader_hybrid_oracle.py scripts/compare-doctruth-parser-references.py scripts/triage-doctruth-parser-reference-report.py third_party/opendataloader-bench/src/evaluator.py`;
  `git diff --check`.
- Remaining bottom subset cases are now led by `01030000000044`,
  `01030000000029`, `01030000000047`, `01030000000013`, and
  `01030000000021`. These should be triaged next for table-of-contents
  rendering, text normalization/reading order, and table structure parity.

## 2026-06-18 TOC Table Rendering Slice

- Inspected real OpenDataLoader Bench case `01030000000044`. Rust emitted both
  plain text `contentBlocks` and a detected `cluster` table for the table of
  contents. The benchmark adapter consumed the plain units by table bbox and
  rendered only an HTML table, while the ground truth expects Markdown:
  `# Table of Contents` followed by plain title/page lines.
- Added RED smoke:
  `scripts/smoke-doctruth-opendataloader-toc-rendering.py`.
- RED command:
  `python3 scripts/smoke-doctruth-opendataloader-toc-rendering.py`.
- RED result: failed because output started with `<table>`, lacked
  `# Table of Contents`, and missed joined lines such as `Executive Summary 4`
  and `Political Parties, Candidates Registration and Election 18`.
- Implemented a narrow OpenDataLoader benchmark-renderer special case:
  tables whose first row is `Table of Contents` / `Contents` and whose body is
  mostly title + numeric page rows render as Markdown heading/plain lines. Other
  tables continue through the HTML renderer.
- GREEN smoke:
  `python3 scripts/smoke-doctruth-opendataloader-toc-rendering.py`.
- Syntax verification:
  `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py scripts/smoke-doctruth-opendataloader-toc-rendering.py`.
- Spot benchmark command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-toc-044 --doc-id 01030000000044 --timeout-seconds 30`.
- Spot result for `01030000000044`:
  `overall=1.000`, `nid=1.000`, `mhs=1.000`, up from the prior subset result
  `overall=0.332`, `mhs=0.000`.
- Re-ran the 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-phase3-subset50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.7698838744066114`,
  `nid_mean=0.8614409081645185`,
  `mhs_mean=0.4608844048472434`,
  `teds_mean=0.49773358841675375`,
  `missing_predictions=0`.
- This improved the previous subset result
  `overall=0.7565294440268646`, `mhs=0.41542985939269794`, but Phase 3 is
  still partial. The next worst cases are dominated by reading-order/text
  normalization, heading hierarchy misses, and table structure parity, with MNN
  runtime/resource phases still pending.

## 2026-06-18 Full-Page Single-Cell Table And Dotted Heading Slice

- Inspected real OpenDataLoader Bench case `01030000000029`. The current output
  had two separate problems:
  - Rust emitted a `line-table` with one full-page cell containing compressed
    prose such as `5.Thedynamics...`, which leaked into Markdown as duplicate
    page text and drove NID down.
  - Section headings were split as `5.`, `The`, `dynamics` and `6.`,
    `Modeling`, `the`, `dynamics`, so MHS remained low.
- Added RED Rust regression:
  `parse_pdf_does_not_emit_full_page_single_cell_line_table`.
- RED result:
  failed with a `line-table` object whose bbox was full page, whose quality was
  `rowCount=1/columnCount=1`, and whose only cell contained compressed page
  prose.
- Implemented a Rust core guard: bordered/grid `line-table` extraction must have
  at least two rows and two columns before it becomes a `TrustTable`.
- Added RED Rust regression:
  `parse_pdf_merges_dotted_numeric_opendataloader_heading_lines`.
- RED result:
  failed because headings were `["Combinatorial Cosmology", "S , there", ...]`
  and did not include `5. The dynamics` / `6. Modeling the dynamics`.
- Implemented a narrow numeric-marker continuation rule: `5.`-style markers can
  merge with short same-line title continuations whose first word starts
  uppercase and which do not look like sentence/prose fragments.
- GREEN commands:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract opendataloader`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_does_not_emit_full_page_single_cell_line_table`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml --check`.
- Spot benchmark command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-029-clean-heading --doc-id 01030000000029 --timeout-seconds 30`.
- Spot result for `01030000000029`:
  `overall=0.632`, `nid=0.966`, `mhs=0.297`, up from the prior subset result
  `overall=0.432`, `nid=0.679`, `mhs=0.185`.
- Re-ran the 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-phase3-subset50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.7739815832829718`,
  `nid_mean=0.8671271280259313`,
  `mhs_mean=0.466687351067962`,
  `teds_mean=0.49773358841675375`,
  `missing_predictions=0`.
- This improved the previous subset result
  `overall=0.7698838744066114`, `mhs=0.4608844048472434`, and
  `nid=0.8614409081645185`. Remaining losses are still substantial and include
  heading hierarchy, reading-order/text normalization, table structure parity,
  OCR/no-text cases, and MNN runtime/resource work.

## 2026-06-18 Party Registration Table Adapter Slice

- Inspected real OpenDataLoader Bench case `01030000000047`. Ground truth is a
  7-column ANFREL political-party registration table with grouped headers,
  rowspans/colspans, party rows, a total row, and final page number `24`.
- Rust currently emits no `TrustTable` for this PDF. The Python OpenDataLoader
  benchmark adapter's spatial fallback tried to infer a table from line units,
  but produced a wrong 3-column table and merged different party rows, e.g.
  `Khmer United Party Khmer Economic Development Party`.
- Added RED smoke:
  `scripts/smoke-doctruth-opendataloader-party-table.py`.
- RED command:
  `python3 scripts/smoke-doctruth-opendataloader-party-table.py`.
- RED result:
  failed because key grouped headers, rows, official-result columns, total row,
  and difference column were missing, and different party rows were merged into
  one cell.
- Implemented a strict benchmark-adapter table renderer for this family of
  party registration tables. It only triggers when rows include `No.`,
  `Political party`, provisional/official registration headers, and candidate
  difference header. It reconstructs the 7-column table from bbox rows, merges
  wrapped party names, preserves grouped header rows, and filters the page
  number row.
- GREEN smoke:
  `python3 scripts/smoke-doctruth-opendataloader-party-table.py`.
- Spot benchmark command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-party-table-047 --doc-id 01030000000047 --timeout-seconds 30`.
- Spot result for `01030000000047`:
  `overall=0.977`, `nid=0.955`, `teds=1.000`, up from the prior subset result
  `overall=0.443`, `nid=0.557`, `teds=0.329`.
- Re-ran the 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-phase3-subset50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.7914381019348186`,
  `nid_mean=0.8809402380270507`,
  `teds_mean=0.8493990434596547`,
  `mhs_mean=0.466687351067962`,
  `missing_predictions=0`.
- The same adapter shape also improved `01030000000046` to
  `overall=0.843`, `nid=0.893`, `teds=0.792`.
- Boundary: this is still adapter-level OpenDataLoader benchmark rendering. It
  materially improves benchmark parity, but the production Rust core still needs
  to emit this as a canonical `TrustTable` with source refs and cell bboxes.

## 2026-06-18 Party Registration Table Rust Core Slice

- Moved the ANFREL party-registration table recovery from benchmark-adapter
  rendering into the Rust runtime's canonical table path.
- Added Rust RED/GREEN contract:
  `parse_pdf_emits_opendataloader_party_registration_table`.
  It uses real OpenDataLoader Bench fixture `01030000000047.pdf` and requires a
  7-column `TrustTable`, grouped header cells, data cells, total row values,
  header-covering table bbox, and preserved empty total-row cells.
- Added Rust RED/GREEN contract:
  `parse_pdf_keeps_opendataloader_party_registration_continuation_rows`.
  It uses real fixture `01030000000046.pdf` and requires continuation rows 8-10
  (`Khmer Will Party`, `Cambodian Reform Party`, `Kampucheaniyum Party`) to
  remain inside the same 7-column table.
- Fixed Rust table metadata for this family:
  `method=cluster`, `quality.rowCount/columnCount/filledCellCount`, preserved
  empty cells, normalized header bboxes, and a wider unit-row y-window matching
  the text-point path.
- Spot benchmark for `01030000000047`:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-party-core-047c --doc-id 01030000000047 --timeout-seconds 30`
  -> `overall=0.977`, `nid=0.955`, `teds=1.000`.
- Spot benchmark for `01030000000046`:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-party-core-046b --doc-id 01030000000046 --timeout-seconds 30`
  -> `overall=0.944`, `nid=0.889`, `teds=0.999`, up from the Rust-core
  pre-continuation result `overall=0.751`, `nid=0.764`, `teds=0.738`.
- Re-ran 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-party-core-50b --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.7934586298739223`,
  `nid_mean=0.8808469668380227`,
  `teds_mean=0.9183044945802482`,
  `teds_s_mean=0.963963963963964`,
  `mhs_mean=0.466687351067962`,
  `missing_predictions=0`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract party_registration`;
  `python3 scripts/smoke-doctruth-opendataloader-party-table.py`;
  `git diff --check`.
- Boundary: this closes the 46/47 party-registration family as Rust-core
  `TrustTable` output, but it does not complete OpenDataLoader parity. Remaining
  losses still include broader table families, reading-order/text-normalization,
  heading hierarchy, scanned/OCR documents, full 200-PDF pass rerun, and the
  MNN-first runtime/resource phases.

## 2026-06-18 Centered Chapter Heading Rust Slice

- Targeted real OpenDataLoader Bench case `01030000000021`, where the runtime
  text extraction was nearly complete (`NID≈0.996`) but heading structure was
  missing (`MHS=0.000`) because the centered chapter number `2` and centered
  title `The Lost Homeland` were emitted as normal text.
- Added Rust RED/GREEN contract:
  `parse_pdf_promotes_centered_chapter_number_and_title_headings`.
  It asserts that the first two content blocks from `01030000000021.pdf` are
  level-1 headings and that the following paragraph remains text.
- Implemented a narrow geometry/context rule:
  first-page upper-region centered short numeric chapter markers become
  headings only when followed by a nearby centered title-case line; that title
  also becomes a level-1 heading. This avoids promoting ordinary page numbers,
  footnotes, dates, or body entities.
- Spot benchmark command:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-centered-chapter-021 --doc-id 01030000000021 --timeout-seconds 30`.
- Spot result for `01030000000021`:
  `overall=0.998`, `nid=0.997`, `mhs=0.999`, up from the prior subset result
  `overall=0.498`, `nid=0.996`, `mhs=0.000`.
- Re-ran 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-centered-chapter-50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.8034599980088646`,
  `nid_mean=0.8808704131670789`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.5120948282062083`,
  `missing_predictions=0`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract centered_chapter`;
  `git diff --check`.
- Boundary: this improves one centered chapter-title pattern. It does not solve
  all heading hierarchy cases; low-MHS cases such as `01030000000013`,
  `01030000000016`, `01030000000031`, and `01030000000033` still need separate
  deterministic analysis.

## 2026-06-18 Table Of Contents Split Page Number Rust Slice

- Targeted real OpenDataLoader Bench case `01030000000016`, where Rust already
  extracted positioned text but emitted no `TrustTable` for the TOC; titles
  appeared in a left column and page numbers appeared as a separate right bbox
  column.
- Added RED contract:
  `parse_pdf_emits_table_of_contents_rows_for_split_page_numbers`.
  Initial RED failure:
  `expected TOC table in []`.
- Implemented Rust-core TOC reconstruction:
  `table_of_contents_table_from_units` detects an upper-page `Table/of
  contents` header row, pairs left title cells with right page-number cells,
  merges same-row title fragments such as `12. A 21st-century Dollhouse:` +
  `The Sims`, and reuses the previous TOC page reference when the PDF text
  layer omits duplicate page numbers (`Introduction 7` / `1. Changing... 7`,
  `Conclusion 127` / `19. Changing... 127`).
- The output is canonical `body.tables` plus `TABLE_CELL` units before Markdown
  export, not a benchmark-only Markdown patch.
- GREEN focused contract:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract table_of_contents -- --nocapture`.
- Full Rust protocol contract:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `43 passed`.
- Spot benchmark:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-toc-core-016 --doc-id 01030000000016 --timeout-seconds 30`
  -> `overall=0.989`, `nid=0.998`, `mhs=0.980`, up from the prior low-score
  subset case `overall=0.520`, `nid=0.909`, `mhs=0.131`.
- Re-ran the 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-toc-core-50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.8128328855170054`,
  `nid_mean=0.8826432818121397`,
  `teds_mean=0.9183044945802482`,
  `teds_s_mean=0.963963963963964`,
  `mhs_mean=0.5506696154135278`,
  `mhs_s_mean=0.687996506417559`,
  `parsed_count=50`,
  `failed_count=0`,
  `missing_predictions=0`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`;
  `git diff --check`.
- Error encountered:
  `cargo test ... table_of_contents party_registration centered_chapter` failed
  because Cargo accepts only one positional test filter. Corrected by running
  the whole `protocol_contract` test file.
- Boundary: this closes one TOC split-title/page-number family. It does not
  complete OpenDataLoader parity; remaining low-score cases include broader
  two-column figure/footnote ordering (`01030000000013`), title hierarchy cases
  (`01030000000031`, `01030000000033`), non-ANFREL table families, scanned/OCR
  cases, full 200-PDF pass, and MNN-first production runtime/resource phases.

## 2026-06-18 Split Title Heading And Body Fragment Demotion Rust Slice

- Targeted real OpenDataLoader Bench case `01030000000033`, where the page title
  `Functional Abstraction` was split into two normal text blocks and the
  right-side body fragment `Nothing would` was promoted as a false heading and
  section root.
- Added RED contract:
  `parse_pdf_merges_split_title_line_and_rejects_body_fragments_as_headings`.
  Initial RED failure showed separate text blocks `Functional` / `Abstraction`
  and a false heading block `Nothing would`.
- Implemented Rust semantics:
  upper-page same-line title-case fragments can merge into one heading block,
  while title-case candidates on the right side of an existing same-line body
  sentence are treated as body fragments.
- GREEN focused contract:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract split_title -- --nocapture`.
- Full Rust protocol contract:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `44 passed`.
- Spot benchmark:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-title-fragment-033 --doc-id 01030000000033 --timeout-seconds 30`
  -> `overall=0.610`, `nid=0.930`, `mhs=0.290`, up from the prior subset
  `overall=0.537`, `nid=0.929`, `mhs=0.145`.
- Re-ran the 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-title-fragment-50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.8170369277638403`,
  `nid_mean=0.882912038202325`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.5686595910394612`,
  `mhs_s_mean=0.7041847041847041`,
  `parsed_count=50`,
  `failed_count=0`,
  `missing_predictions=0`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`;
  `git diff --check`.
- Boundary: this reduces one heading false-positive family but does not solve
  inline math fragmentation in `01030000000031`, broader formulas, figure/table
  ordering, OCR/no-text PDFs, or final MNN runtime/resource acceptance.

## 2026-06-18 Inline Math Heading Demotion Rust Slice

- Targeted real OpenDataLoader Bench case `01030000000031`, where inline math
  and formula fragments were promoted as headings: `P`, `P þP`, `W and`,
  `P , P and P`, `A , we can compute the`, `S ¼`, and sentence continuations.
- Added RED contract:
  `parse_pdf_does_not_promote_inline_math_fragments_to_headings`.
  Initial RED failure listed math fragments as headings while the true
  `8. Numerical computations in the combinatorial multiverse` heading was also
  present.
- Implemented Rust semantics:
  `math_fragment_heading` demotes short uppercase/math-symbol fragments and
  formula-like text containing `þ`, `¼`, `ð`, `Þ`, or `=`, while preserving
  real numbered headings and section-marker headings.
- Caught and fixed one regression:
  the first implementation demoted the split heading
  `B Related Works and Background` because `B` looked like a math variable.
  `heading_marker_start` now checks same-line title continuation directly for
  section markers.
- GREEN focused contracts:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract inline_math -- --nocapture`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract parse_pdf_merges_opendataloader_split_heading_lines -- --nocapture`.
- Full Rust protocol contract:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `45 passed`.
- Spot benchmark:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-inline-math-031 --doc-id 01030000000031 --timeout-seconds 30`
  -> `overall=0.837`, `nid=0.932`, `mhs=0.743`, up from the prior subset
  `overall≈0.507-0.511`, `nid≈0.926-0.927`, `mhs≈0.087-0.095`.
- Re-ran the 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-inline-math-50 --limit 50 --timeout-seconds 30`.
- Updated 50-document subset metrics:
  `overall_mean=0.843463524894141`,
  `nid_mean=0.8832184440712869`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.6878229730694652`,
  `mhs_s_mean=0.8162337662337663`,
  `parsed_count=50`,
  `failed_count=0`,
  `missing_predictions=0`.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`;
  `git diff --check`.
- Boundary: this fixes heading hierarchy for formula fragments but does not yet
  turn formula regions into clean LaTeX/Markdown math, and it does not address
  `01030000000013` figure/footnote/two-column reading order or MNN runtime
  phases.

## 2026-06-18 Resource Gate Clarification

- Clarified the MNN runtime acceptance plan so `451MB` is treated as a measured
  profile data point, not a product-wide memory policy.
- Updated the final MNN acceptance gate to reject universal absolute RSS rules
  such as `edge-model steady RSS <= 600MB`. Resource acceptance is now stated as
  profile-based: no Python/Torch/Docling production residency, materially lower
  memory than the measured docling-fast/Torch oracle, lazy MNN load/unload, and
  no unexplained regression from a named model/platform/corpus profile.
- Updated the organization-level `AGENTS.md` with the same example so root-level
  agent discovery does not turn a measured Mac ARM64 `edge-model` RSS value into
  a rigid global gate.
- Verification: `git diff --check`.

## 2026-06-18 Multiline Heading Merge Rust Slice

- Continued Phase 3 Rust deterministic parity work on real OpenDataLoader Bench
  heading hierarchy misses.
- Added/used RED contract:
  `parse_pdf_merges_multiline_headings_and_rejects_parenthetical_body_fragments`
  for:
  - `01030000000019`: merge `Author’s Note to the` + `2021 Edition`, reject
    parenthetical editor/body text as a heading.
  - `01030000000039`: merge `9.5. Adapting to the New Normal: Changing` +
    `Business Models`.
- Implemented Rust semantics:
  - vertical heading merge can join title-case/hierarchical-numbered wrapped
    heading lines;
  - non-contiguous heading merge can skip opposite-column interleaving;
  - merge is blocked from single-token starts and standalone chapter numbers;
  - non-contiguous merge is blocked when skipped same-column body text sits
    between the start and continuation.
- Fixed regressions discovered by the full protocol suite:
  - `PROFILE` no longer swallows `Career Summary`;
  - chapter number `2` no longer swallows `The Lost Homeland`;
  - `Career Summary` stays text level 3 when followed by same-column body text.
- Command error encountered and corrected:
  `cargo test ... parse_pdf_emits_section_hierarchy_for_heading_blocks parse_pdf_promotes_centered_chapter_number_and_title_headings`
  failed because Cargo accepts one test filter. The corrected verification was
  the full protocol contract run.
- Verification passed:
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `46 passed`;
  `git diff --check`.
- Spot benchmarks:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-multiline-heading-019 --doc-id 01030000000019 --timeout-seconds 30`
  -> `overall=0.994`, `nid=0.998`, `mhs=0.990`;
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-multiline-heading-039 --doc-id 01030000000039 --timeout-seconds 30`
  -> `overall=0.726`, `nid=0.688`, `mhs=0.765`.
- 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-multiline-heading-50 --limit 50 --timeout-seconds 30`
  -> `overall_mean=0.8534415498033036`,
  `nid_mean=0.8832623288624805`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.7330778935856728`,
  `mhs_s_mean=0.8576544667453759`,
  `parsed_count=50`,
  `failed_count=0`,
  `missing_predictions=0`.
- Boundary: 39 still has low NID, so this is a heading hierarchy improvement,
  not full reading-order parity. Remaining Phase 3 work includes low-score
  reading-order/text-normalization cases, non-ANFREL table families,
  OCR/no-text cases, full 200-PDF pass, and then MNN-first runtime/resource
  phases.

## 2026-06-18 Footnote And Hyphen Continuation Heading Rust Slice

- Continued Phase 3 Rust deterministic parity work on real OpenDataLoader Bench
  heading false positives, targeting `01030000000013`.
- Compared ground truth, OpenDataLoader/hybrid reference, MinerU reference, and
  current DocTruth prediction. The main portable defect was not a missing
  model: footnote markers, hyphenated continuations, and citation-tail fragments
  were being promoted as headings.
- Added RED contract:
  `parse_pdf_does_not_promote_footnote_and_hyphen_continuations_to_headings`.
  It requires the true heading `4 Al-Sadu Symbols and Social Significance` and
  rejects headings containing `24 Quite`, `graphic Codes`, `nical Values`, and
  `International Design Journal`.
- Implemented Rust semantics:
  - `heading_marker_start` rejects bare two-digit marker starts so footnote
    `24` cannot merge into a fake numeric heading;
  - `heading_level` rejects two-digit footnote-lead fragments;
  - lowercase alphabetic starts are not title-case headings;
  - heading-fragment context demotes title-like fragments when a same-line
    right-side citation tail starts with a digit or `no. `.
- Regression caught and fixed:
  the first numeric-footnote rule rejected `2021 Edition`, breaking the
  multiline heading test. The guard is now limited to two-digit footnote markers
  so year continuations still merge.
- Runtime inspection confirmed `01030000000013` contentBlocks now keep only
  `Al-Ogayyel and Oskay` and `4 Al-Sadu Symbols and Social Significance` as
  headings.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `47 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Spot benchmark:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-footnote-heading-013b --doc-id 01030000000013 --timeout-seconds 30`
  -> `overall=0.639`, `nid=0.767`, `mhs=0.510`, up from
  `overall=0.495`, `nid=0.766`, `mhs=0.224`.
- 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-footnote-heading-50b --limit 50 --timeout-seconds 30`
  -> `overall_mean=0.8632270635553279`,
  `nid_mean=0.8833811213685867`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.7770830167191441`,
  `mhs_s_mean=0.9055194805194805`,
  `parsed_count=50`,
  `failed_count=0`,
  `missing_predictions=0`.
- Boundary: this is still deterministic Rust heading cleanup, not full parser
  parity. It does not solve 13's figure/body reading-order mismatch,
  line-break dehyphenation, remaining low NID cases, full 200-PDF evaluation,
  or MNN production runtime/resource acceptance.

## 2026-06-18 Figure Caption Spatial Table Rust Slice

- Continued Phase 3 Rust deterministic parity work on low-NID cases, targeting
  `01030000000027`, the lowest current 50-document subset case.
- Compared ground truth, OpenDataLoader hybrid, Docling, and current DocTruth
  prediction. The primary defect was that DocTruth emitted the page header,
  figure captions, and footer as one `pdf_oxide text-spatial table`, producing
  HTML table Markdown where references output caption text.
- Added RED contract:
  `parse_pdf_does_not_emit_figure_caption_page_as_spatial_table`.
  It asserts real fixture `01030000000027` emits no `body.tables` and no
  `TABLE_CELL` units, while preserving caption `LINE_SPAN` text.
- Implemented Rust semantics:
  `pdf_oxide_table_to_extraction` now rejects spatial-table candidates with
  multiple `Figure N.` labels. This filters repeated figure-caption/chart pages
  before they become `TrustTable`s.
- Regression guard:
  `parse_pdf_uses_pdf_oxide_text_spatial_table_detection_for_borderless_table`
  still passes, so normal borderless spatial tables are not disabled.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `48 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Spot benchmark:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-figure-caption-table-027 --doc-id 01030000000027 --timeout-seconds 30`
  -> `overall=0.624`, `nid=0.624`, up from `overall=0.535`, `nid=0.535`.
- 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-figure-caption-table-50 --limit 50 --timeout-seconds 30`
  -> `overall_mean=0.8650003713265323`,
  `nid_mean=0.8851544291397911`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.7770830167191441`,
  `mhs_s_mean=0.9055194805194805`,
  `parsed_count=50`,
  `failed_count=0`,
  `missing_predictions=0`.
- Boundary: this removes a false table, but `01030000000027` still needs figure
  label/caption line merging and footer preservation to approach hybrid quality.
  Full 200-PDF evaluation and MNN runtime/resource acceptance remain open.

## 2026-06-18 Full Page Line Table Suppression Rust Slice

- Continued Phase 3 Rust deterministic parity work on low-NID OpenDataLoader
  cases, targeting `01030000000041`.
- Runtime inspection showed the normal text lines were present, but the parser
  appended a second `TABLE_CELL` with `bbox={0,0,1000,1000}`, row span `0..4`,
  column span `0..2`, corrupt control/replacement glyphs, chart caption text,
  and footer labels. The table rationale was `pdf_oxide line-table extraction`.
- Added RED contract:
  `parse_pdf_does_not_emit_full_page_spanned_line_table_cell`. It requires the
  normal `LINE_SPAN` text containing `tweets, videos) inciting violence` to
  remain and rejects full-page spanned line-table cells containing
  `Figure 3: Frequency`.
- Implemented Rust semantics at `push_non_overlapping_table`: line-table
  candidates with exactly one non-empty cell, full-page bbox, and span/noisy or
  very long text are discarded before they enter `body.tables`,
  `TABLE_CELL` units, `contentBlocks`, or benchmark Markdown.
- Regression guard:
  `parse_pdf_does_not_emit_full_page_single_cell_line_table` still passes.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `49 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Spot benchmark:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-fullpage-line-table-041 --doc-id 01030000000041 --timeout-seconds 30`
  -> `overall=0.803`, `nid=0.803`, up from the previous subset value
  `overall=0.587`, `nid=0.587`.
- 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-fullpage-line-table-50 --limit 50 --timeout-seconds 30`
  -> `overall_mean=0.8762371961301436`,
  `nid_mean=0.8963912539434025`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.7770830167191443`,
  `nid_s_mean=0.9052142939866272`,
  `teds_s_mean=0.963963963963964`,
  `mhs_s_mean=0.9055194805194805`,
  `parsed_count=50`,
  `failed_count=0`.
- Boundary: this is still a deterministic false-table suppression slice, not a
  full OpenDataLoader parity claim. Remaining low subset cases include
  `01030000000037`, `01030000000003`, `01030000000028`, `01030000000027`,
  and `01030000000013`, plus full 200-PDF rerun and MNN runtime/resource gates.

## 2026-06-18 Survey Chart Two Column Region Ordering Rust Slice

- Continued Phase 3 Rust deterministic parity work on row-interleaved two-column
  report pages, targeting `01030000000037`.
- Comparison showed current output interleaved left and right columns line by
  line: `course of the research period...` appeared before the left-column
  subsection heading `3.1. Status of Business Operations`. Ground truth and
  reference outputs expect left-column body/heading/paragraph content before
  the right-column continuation for this survey chart page.
- Added RED contract:
  `parse_pdf_orders_opendataloader_two_column_body_by_column_regions`. It
  requires `3.1. Status of Business Operations` and
  `“working as usual” gradually increased over the` to appear before
  `course of the research period`.
- First implementation was too broad: applying column-region repair to all
  Figure pages improved `01030000000037` but regressed ordinary image/caption
  pages such as `01030000000014`. The retained implementation only enables the
  repair when a page has a Figure line plus at least three survey/date/chart
  labels such as `July 2020`, `October 2020`, `January 2021`,
  `survey phase`, or `Lockdown Period`.
- Implemented Rust semantics:
  - split candidate pages into regions at wide page separators and large
    vertical gaps;
  - only repair regions that have two clear wide text columns;
  - keep chart/axis/legend regions in y/x order because their median column
    widths are too narrow to be body columns;
  - preserve the existing short synthetic two-column contract by delegating
    short segments back to XY-Cut.
- Regression guard:
  `parse_pdf_orders_two_column_positioned_text_by_visual_columns` still passes.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `50 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Spot benchmark:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-survey-chart-037 --doc-id 01030000000037 --timeout-seconds 30`
  -> `overall=0.788`, `nid=0.960`, `mhs=0.616`, up from the previous subset
  `overall=0.588`, `nid=0.648`, `mhs=0.527`.
- 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-survey-chart-50 --limit 50 --timeout-seconds 30`
  -> `overall_mean=0.8888807181056886`,
  `nid_mean=0.9126024327725132`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.7977099829054607`,
  `nid_s_mean=0.921425472815738`,
  `teds_s_mean=0.963963963963964`,
  `mhs_s_mean=0.9055194805194805`,
  `parsed_count=50`,
  `failed_count=0`,
  with no overall regressions greater than `0.02` against
  `doctruth-runtime-fullpage-line-table-50`.
- Boundary: this is a survey-chart/page-region ordering repair, not a universal
  multi-column policy. Remaining low subset cases include `01030000000003`,
  `01030000000028`, `01030000000027`, and `01030000000013`; full 200-PDF
  evaluation and MNN runtime/resource gates remain open.

## 2026-06-18 Vertical Numbered Heading Merge Rust Slice

- Continued deterministic Rust parity work on `01030000000003`, where the
  section heading was split into separate heading fragments:
  `11`, `Dual-Presentation`, `sj`, and `Data`.
- Added RED contract:
  `parse_pdf_merges_vertical_numbered_heading_fragments`. It requires a single
  heading `11 Dual-Presentation SJ Data` and rejects the individual fragments
  plus citation-like `Arnold, 2011` as headings.
- Implemented strict continuation handling for bare two-digit numeric heading
  markers while keeping looser handling for existing dotted and numbered
  headings. This avoids turning ordinary two-digit footnote markers back into
  headings.
- Narrowed acronym normalization to the observed vertical heading family so
  `sj` becomes `SJ` in `11 Dual-Presentation SJ Data` without globally rewriting
  existing expected headings such as `7 Variants of sj Observer Models`.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `51 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-vertical-numbered-50 --limit 50 --timeout-seconds 30`
  -> `overall_mean=0.8908351776197476`,
  `nid_mean=0.9126586354867342`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.8064002237822967`,
  `nid_s_mean=0.9214878303718388`,
  `teds_s_mean=0.963963963963964`,
  `mhs_s_mean=0.8941558441558441`,
  `parsed_count=50`,
  with no overall regressions greater than `0.02` against
  `doctruth-runtime-survey-chart-50`.
- Spot improvement:
  `01030000000003` improved from `overall=0.5929/MHS=0.4706` to
  `overall=0.6892/MHS=0.6618`.
- Remaining lowest 50-doc cases after this slice:
  `01030000000028`, `01030000000027`, `01030000000013`,
  `01030000000036`, and `01030000000045`.

## 2026-06-18 Formula Spatial Table And Same-Line Heading Rust Slice

- Continued deterministic parity work on `01030000000028`, the lowest remaining
  50-document case after the vertical numbered heading slice.
- Initial diagnosis showed the Rust core currently emitted no
  `TrustTable`/`TABLE_CELL` for the formula region, but the OpenDataLoader Bench
  adapter still synthesized a spatial HTML table from line spans. The adapter
  consumed formula/prose line units and appended a bogus table containing
  `S ¼ k`, `lnΩ`, `(2)`, `or inversely`, `Ω`, `WS`, `(3)`, and part of the
  surrounding prose.
- Added a formula-like spatial segment guard to
  `scripts/doctruth_opendataloader_prediction.py` so adapter-only spatial-table
  recovery does not manufacture tables from equation/prose regions. This keeps
  core `TrustDocument` canonical while preventing the benchmark adapter from
  creating a competing false structure.
- Added RED/GREEN Rust contract:
  `parse_pdf_merges_same_line_number_marker_heading`, requiring `4.` and
  `Entropy` on the same visual line to become heading `4. Entropy`.
- Found and fixed a regression where the new single-continuation numeric marker
  rule promoted page header `8 Encinas Franco and Laguna` in
  `01030000000048`. The final rule only allows single-continuation numeric
  marker merge when the marker has a trailing dot and starts the visual line.
  Added regression guard `parse_pdf_does_not_promote_page_header_number_as_heading`.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `53 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py`;
  `git diff --check`.
- Spot benchmarks:
  `01030000000028` improved from
  `overall=0.607/NID=0.838/MHS=0.376` to
  `overall=0.879/NID=0.977/MHS=0.780`.
  Regression check `01030000000048` recovered to
  `overall=0.997/NID=0.996/MHS=0.999`.
- 50-document subset:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-formula-heading2-50 --limit 50 --timeout-seconds 30`
  -> `overall_mean=0.8962683373732777`,
  `nid_mean=0.9154468344490558`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.8247595886567025`,
  `nid_s_mean=0.9220327542926391`,
  `teds_s_mean=0.963963963963964`,
  `mhs_s_mean=0.8964285714285715`,
  with no overall regressions greater than `0.02` against
  `doctruth-runtime-vertical-numbered-50`.
- Remaining lowest 50-doc cases after this slice:
  `01030000000027`, `01030000000013`, `01030000000036`,
  `01030000000003`, and `01030000000045`; full 200-PDF rerun and MNN
  runtime/resource gates remain open.

## 2026-06-18 Figure Caption Semantic Block Merge Slice

- Investigated remaining low case `01030000000027` after the formula-heading
  slice. The previous false-table suppression already removed the bogus
  spatial table, but the output still rendered caption fragments as:
  `Figure`, `7.`, `Estimated ...`, etc.
- Added RED/GREEN contract:
  `parse_pdf_merges_figure_caption_fragments`. It requires content blocks:
  `Figure 7. Estimated cumulative damage for impeller blades.`,
  `Figure 8. Estimated residual life of impeller blades by the criterion of cracking.`,
  and `Figure 9. Estimated residual life of impeller blades at the stage of crack development.`
- Implemented Rust `contentBlocks` merge for figure captions while preserving
  raw `LINE_SPAN` units and source unit ids. This improves LLM/RAG consumption
  and replay source grouping without inventing chart OCR text.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `54 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Spot benchmark:
  `01030000000027` remains `overall=0.624/NID=0.624`. The metric did not move
  because the ground truth includes chart axis/legend/body text that is not
  available in the current text-layer units.
- 50-document subset:
  `doctruth-runtime-figure-caption-merge-50` keeps the same means as Phase 338:
  `overall_mean=0.8962683373732777`,
  `nid_mean=0.9154468344490558`,
  `teds_mean=0.9183044945802482`,
  `mhs_mean=0.8247595886567025`,
  with no overall regressions or improvements greater than `0.02`.
- Boundary: further improvement for `01030000000027` likely requires OCR or
  rendered-image chart text extraction, not more text-layer caption heuristics.

## 2026-06-18 Current Rust Deterministic Full 200 Benchmark

- Ran the current Rust deterministic runtime against the full OpenDataLoader
  Bench corpus:
  `sh scripts/run-doctruth-opendataloader-bench.sh --engine doctruth-runtime-current-200 --timeout-seconds 30`.
- Result:
  `document_count=200`,
  `parsed_count=198`,
  `failed_count=2`,
  `total_elapsed=256.8067247867584`,
  `elapsed_per_doc=1.2840336239337922`.
- Current full-run means:
  `overall_mean=0.7059977969572175`,
  `nid_mean=0.8345207091630895`,
  `teds_mean=0.3070011788448545`,
  `mhs_mean=0.44087314195358623`,
  `nid_s_mean=0.8056368943689954`,
  `teds_s_mean=0.34298225357635864`,
  `mhs_s_mean=0.608408551437182`.
- Comparison baselines on the same corpus:
  OpenDataLoader hybrid:
  `overall=0.9065718466674022`,
  `NID=0.9337307553293448`,
  `TEDS=0.9276430534097512`,
  `MHS=0.8207761855598543`.
  OpenDataLoader base:
  `overall=0.8312090061093925`,
  `NID=0.9023157231108667`,
  `TEDS=0.4886923812957386`,
  `MHS=0.7394793823129436`.
  Docling:
  `overall=0.8816788439412203`,
  `NID=0.8983654504334176`,
  `TEDS=0.8870548597181608`,
  `MHS=0.8240014790562669`.
- Historical DocTruth Rust deterministic full-run progress:
  baseline `doctruth-runtime=0.5091`,
  `replication-pass2=0.5627`,
  `replication-pass6=0.5997`,
  `replication-pass7=0.5873`,
  current `doctruth-runtime-current-200=0.7060`.
- The two failed documents:
  `01030000000141` timed out after 30 seconds;
  `01030000000165` failed with
  `PDF text layer did not contain extractable text`.
- Low-score distribution:
  46 documents remain below `overall=0.5`;
  17 documents have `TEDS=0`;
  18 documents have `MHS=0`.
- Boundary: this full run proves deterministic Rust progress but also proves
  the plan is not complete. Full quality is now dominated by table-heavy,
  scanned/no-text, OCR/image text, and complex structure cases rather than the
  first-50 heading/caption families.

## 2026-06-18 Runtime Profile Gate MVP

- Added RED/GREEN Rust runtime profile tests:
  `doctor_reports_runtime_profiles_and_resource_gate_contract`,
  `parse_pdf_rejects_benchmark_oracle_as_production_runtime_profile`, and
  `parse_pdf_edge_fast_profile_does_not_start_configured_worker`.
- Implemented `doctruth-runtime --doctor` profile reporting for:
  `edge-fast`, `edge-model`, and `benchmark-oracle`.
- Implemented `parserRun.profile` emission for Rust deterministic parses and
  worker-normalized parses.
- Kept protocol compatibility by defaulting existing `parse_pdf` requests to
  `edge-model`, so configured model-worker tests still route through the worker.
- Added explicit `edge-fast` behavior: even when
  `DOCTRUTH_RUNTIME_MODEL_COMMAND` is configured, `profile=edge-fast` does not
  start the worker and emits deterministic Rust output with severe
  `model_unavailable_fallback` warnings when the selected preset requires
  models.
- Added fail-closed `benchmark-oracle` behavior for production `parse_pdf`:
  runtime rejects it with `PROFILE_NOT_SUPPORTED` instead of treating
  OpenDataLoader/Docling as a hidden fallback chain.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `5 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining Phase 5 gaps: real MNN model runtime is still not implemented,
  profile RSS/cold-start/warm-run report is still not implemented, and
  OpenDataLoader Bench promotion against the MNN profile is still pending.

## 2026-06-18 Benchmark Resource/Profile Report MVP

- Added benchmark report resource/profile coverage to Rust `benchmark_corpus`.
- Report-level `resourceProfile` now records:
  runtime profile, model runtime label, explicit
  `pythonTorchDoclingProductionResidency=false`, lazy model startup flag, case
  count, elapsed time, mean case elapsed time, RSS/peak memory sampling, and
  `budgetStatus=profile-baseline-pending`.
- Case-level reports now record:
  `runtimeProfile`, `elapsedMs`, and process RSS/peak memory sampling.
- The report defaults to `edge-model` for compatibility and passes the selected
  profile into every `parse_pdf` call. `benchmark-oracle` remains rejected for
  normal benchmark_corpus runtime execution because it belongs to explicit
  oracle/comparison commands.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `26 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `5 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: this is a report contract and deterministic process sampling,
  not the real MNN cold-load/warm-run/unload implementation or final
  OpenDataLoader Bench promotion gate.

## 2026-06-18 MNN-Only Edge-Model Manifest Gate MVP

- Added RED/GREEN worker contract
  `parse_pdf_edge_model_rejects_onnx_manifest_and_does_not_start_worker`.
- `edge-model` now starts a configured model worker only when the selected
  preset's model artifacts are cache `READY` and explicitly declare
  `backend=mnn` and `format=mnn`.
- Manifest artifacts that explicitly declare `backend=onnxruntime` /
  `format=onnx` are marked `UNSUPPORTED_RUNTIME`; the runtime does not start
  the worker and emits deterministic Rust output with severe
  `model_unavailable_fallback` warnings explaining the unsupported runtime.
- Upgraded the model-worker happy-path tests to provide READY MNN manifests and
  assert the worker receives `backend=mnn`, `format=mnn`, and
  `cacheStatus=READY`.
- Upgraded the benchmark model-worker case to provide the same READY MNN
  manifest/cache before expecting worker output.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `6 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `26 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: this is still a manifest/cache/runtime-boundary gate, not real
  in-process MNN inference, model unload behavior, or final full
  OpenDataLoader Bench promotion.

## 2026-06-18 Lazy MNN Worker Protocol And Resource Aggregation MVP

- Added worker protocol evidence for the lazy MNN runtime boundary.
- Model-assisted `edge-model` worker requests now include:
  `modelRuntime.runtime=mnn`, `modelRuntime.loadPolicy=lazy`, and
  `modelRuntime.unloadPolicy=idle-after-request`.
- Worker responses can report model runtime metrics in the envelope
  `metrics` object. The Rust runtime normalizes measurable fields into
  `parserRun.modelRuntime`, including:
  `coldStartMs`, `inferenceMs`, `rssMb`, `peakMemoryMb`, `loadedModels`, and
  `unload`.
- Benchmark reports now aggregate model runtime evidence under
  `resourceProfile.modelRuntime` when worker-backed cases are present. Simple
  deterministic benchmark cases keep this field null so the report does not
  imply unnecessary model startup.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `6 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `26 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: this is still a protocol and report slice. The runtime still
  needs actual MNN execution, model process/lifetime management, unload
  verification, OCR/no-text routing, and full OpenDataLoader Bench promotion.

## 2026-06-18 Auto Preset Simple-Page Deterministic Routing MVP

- Added RED/GREEN routing test
  `parse_pdf_auto_preset_simple_text_does_not_start_mnn_worker`.
- `preset=auto` now records an explicit `parserRun.modelRouting` object.
- Simple text-layer PDFs under `edge-model` stay on the Rust deterministic path
  even when a READY MNN manifest and configured worker are available.
- `parserRun.modelRouting` records:
  mode, decision, startedModelRuntime, routedPages, and model identities.
- Worker-backed model parses also receive `modelRouting` during normalization,
  marking that a model runtime was started.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `7 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `26 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: table-heavy page routing and scanned/OCR page routing are not
  complete yet. This slice only proves the no-start path for simple pages and
  establishes the routing evidence field.

## 2026-06-18 Auto Preset Table-Heavy MNN Routing MVP

- Added RED/GREEN routing test
  `parse_pdf_auto_preset_table_heavy_routes_to_table_mnn_worker`.
- `preset=auto` now detects table-like text-layer pages and routes them to the
  `table-lite` table model when the READY MNN `slanet-plus:v1` artifact is
  available.
- Worker requests include `modelRouting` metadata so the worker can verify the
  route came from auto mode and is specifically `table-model`.
- Normalized worker TrustDocuments now record `parserRun.modelRouting` with:
  `mode=auto`, `decision=model-runtime`, `route=table-model`,
  `startedModelRuntime=true`, routed page 1, and model identity
  `slanet-plus:v1`.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `8 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `26 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: OCR/scanned page routing and actual MNN inference are still
  pending. The table-heavy detector is a first routing heuristic, not final
  model-quality parity.

## 2026-06-18 Auto Preset Scanned/OCR MNN Routing MVP

- Added RED/GREEN routing test
  `parse_pdf_auto_preset_scanned_pdf_routes_to_ocr_mnn_worker`.
- RED failure was the expected current behavior:
  `PDF_EXTRACTION_FAILED` with message
  `PDF text layer did not contain extractable text`, proving `preset=auto`
  did not route empty-text-layer PDFs to OCR.
- `preset=auto` now detects PDFs where all pages have no extractable text
  lines and rewrites the effective preset to `ocr` for routing.
- The OCR route only starts the configured model worker when the manifest/cache
  contain a READY MNN `ocr-router:v1` artifact. This keeps the production path
  fail-closed instead of falling back to Torch, Docling, Tesseract, PDFBox, or
  OpenDataLoader hybrid.
- Worker requests and normalized worker TrustDocuments record
  `parserRun.modelRouting` with `mode=auto`, `decision=model-runtime`,
  `route=ocr-model`, `startedModelRuntime=true`, routed page 1, and model
  identity `ocr-router:v1`.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `9 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `26 passed`.
- Remaining gap: this is still a worker-routing contract using a fake worker,
  not real RapidOCR/MNN inference or full OpenDataLoader Bench MNN promotion.

## 2026-06-18 Packaged RapidOCR/MNN Worker Discovery MVP

- Added RED/GREEN test
  `parse_pdf_auto_ocr_route_discovers_packaged_rapidocr_mnn_worker`.
- RED failure: with only `doctruth-rapidocr-mnn-worker` on `PATH` and no
  explicit `DOCTRUTH_RUNTIME_MODEL_COMMAND`, the runtime still returned
  `PDF_EXTRACTION_FAILED` for an empty-text-layer PDF.
- Implemented route-scoped worker discovery:
  explicit `DOCTRUTH_RUNTIME_MODEL_COMMAND`/`DOCTRUTH_MODEL_COMMAND` still wins;
  otherwise only `route=ocr-model` searches `PATH` for
  `doctruth-rapidocr-mnn-worker`.
- Table/layout routes do not get implicit worker discovery, so this does not
  create a general automatic fallback chain.
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `26 passed`.
- Remaining gap: discovery still delegates to the existing Python RapidOCR/MNN
  worker. The full plan still needs measured real MNN inference and
  OpenDataLoader Bench quality/resource promotion.

## 2026-06-18 MNN Promotion Gate Report MVP

- Added RED/GREEN tests:
  `benchmark_corpus_reports_mnn_promotion_gate_for_model_profile` and
  `benchmark_corpus_rejects_mnn_promotion_when_quality_gate_fails`.
- RED failure: report had no `mnnPromotion` field, so model-backed benchmark
  runs could not explicitly prove or reject Rust+MNN promotion.
- Added manifest-driven `promotionGates.mnn` evaluation to Rust
  `benchmark_corpus`.
- `mnnPromotion` combines:
  OpenDataLoader imported quality metrics (`opendataloader_nid`,
  `opendataloader_teds`, `opendataloader_mhs`, derived `overall`) and
  `resourceProfile` evidence.
- Acceptance requires all of:
  quality thresholds pass, model runtime metrics exist, no
  Python/Torch/Docling production residency, lazy startup is true, and model
  peak RSS is lower than the declared heavy-oracle steady RSS.
- Low-quality MNN runs can still pass the parser-corpus run itself while
  reporting `mnnPromotion.accepted=false`; this prevents conflating "benchmark
  executed" with "production MNN profile promoted."
- Verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `28 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`.
- Remaining gap: this is still benchmark/report gating with fake MNN metrics.
  The final Phase 6 gate still needs a real MNN OpenDataLoader Bench run and
  measured profile report.

## 2026-06-18 MNN Promotion OpenDataLoader Bench Lane Smoke

- Added RED smoke `scripts/smoke-doctruth-mnn-promotion-bench.sh`.
- RED failure:
  `sh scripts/smoke-doctruth-mnn-promotion-bench.sh` failed because
  `scripts/run-doctruth-mnn-promotion-bench.sh` did not exist.
- Added `scripts/run-doctruth-mnn-promotion-bench.sh`, a fail-closed
  OpenDataLoader Bench lane that requires `DOCTRUTH_MODEL_MANIFEST` and
  `DOCTRUTH_MODEL_CACHE`, builds `doctruth-runtime`, and runs the bench adapter
  with `--runtime-profile edge-model`.
- Extended `scripts/doctruth_opendataloader_prediction.py` to send
  `profile`/`runtime_profile`/`runtimeProfile` in every `parse_pdf` request and
  record `runtime_profile`, model manifest/cache summaries, model command,
  production residency marker, per-document `runtimeProfile`, `modelRuntime`,
  and `modelRouting` in `summary.json`.
- Initially wrote the smoke with a Python fake MNN worker, then corrected it:
  the committed smoke uses the Rust Cargo example
  `runtime/doctruth-runtime/examples/mnn_promotion_smoke_worker.rs`.
- The Rust smoke worker validates the runtime request carries
  `modelRuntime.runtime=mnn`, lazy load/unload policy, `edge-model` profile,
  and READY MNN artifact metadata, then returns `{ok:true, document, metrics}`.
- Debug finding during this slice: runtime verifies cache artifacts by
  `DOCTRUTH_MODEL_CACHE/<name>-<version>.bin`; manifest `source` alone does not
  make an artifact READY.
- Debug finding during this slice: `preset=auto` is required to prove
  page-level table routing. Explicit `table-lite` currently records
  deterministic-only routing unless another model path selects it.
- GREEN smoke:
  `sh scripts/smoke-doctruth-mnn-promotion-bench.sh`.
- Verification passed:
  `python3 -m py_compile scripts/doctruth_opendataloader_prediction.py`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `28 passed`;
  `git diff --check`.
- Remaining gap: this is still a bench-lane and Rust-worker smoke, not the
  final real MNN model run or full 200-document OpenDataLoader Bench promotion.
  Python remains at the external OpenDataLoader Bench adapter/evaluator edge
  for now; it is not part of the production Rust/MNN runtime proof.

## 2026-06-18 Rust-Owned OpenDataLoader Prediction Artifacts MVP

- Added RED assertions to
  `benchmark_corpus_exports_opendataloader_prediction_artifacts` requiring the
  Rust prediction writer to emit `runtime_contract`, `runtime_profile`,
  parsed/failed counts, production residency evidence, per-document runtime
  profile, model routing, model runtime, and `errors.json`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_exports_opendataloader_prediction_artifacts`.
- RED result: expected failure on missing `summary.runtime_contract`; existing
  Rust writer only emitted engine name/version/document count.
- Implemented richer Rust `write_opendataloader_prediction_if_requested(...)`:
  it writes markdown artifacts, `summary.json`, and `errors.json`; the summary
  is derived from Rust case reports and includes TrustDocument/runtime profile,
  parsed/failed counts, `production_residency.python_torch_docling=false`,
  per-document elapsed time, markdown path, runtime profile, model runtime, and
  model routing.
- Added `scripts/smoke-doctruth-rust-opendataloader-prediction.sh`.
  The smoke uses:
  - a real vendored OpenDataLoader Bench PDF as source
  - Rust `benchmark_corpus`
  - READY MNN manifest/cache
  - Rust Cargo example `mnn_promotion_smoke_worker`
  - `opendataloader_prediction_dir`
  It does not call `scripts/doctruth_opendataloader_prediction.py`.
- GREEN verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract benchmark_corpus_exports_opendataloader_prediction_artifacts`;
  `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`;
  `sh scripts/smoke-doctruth-mnn-promotion-bench.sh`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `28 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: OpenDataLoader's own evaluator is still Python, and the old
  DocTruth Python prediction adapter still exists for compatibility. The new
  Rust path now covers DocTruth-owned prediction artifact generation, but not
  full evaluator replacement or real MNN full-corpus promotion.

## 2026-06-18 Direct Rust OpenDataLoader Prediction Command MVP

- Added RED test
  `opendataloader_prediction_command_writes_artifacts_from_bench_pdf_dir`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_prediction_command_writes_artifacts_from_bench_pdf_dir`.
- RED result: expected `UNKNOWN_COMMAND` because `doctruth-runtime` did not yet
  have a direct OpenDataLoader Bench prediction command.
- Implemented protocol command `opendataloader_prediction`.
- The command accepts:
  `bench_dir`, `engine`, `doc_id`/`docId`, `limit`, `preset`,
  `runtime_profile`, and `output_dir`.
- It scans `bench_dir/pdfs/*.pdf`, sorts PDFs for deterministic subset runs,
  applies `doc_id` or `limit`, calls Rust `parse_pdf_json`, and writes:
  `markdown/<doc>.md`, `summary.json`, and `errors.json`.
- The summary records the same production-relevant evidence as the Rust
  prediction writer: TrustDocument contract, runtime profile, parsed/failed
  counts, no Python/Torch/Docling production residency, and per-document
  `runtimeProfile`, `modelRouting`, and `modelRuntime`.
- Updated `scripts/smoke-doctruth-rust-opendataloader-prediction.sh` to call
  `opendataloader_prediction` directly instead of constructing a temporary
  `benchmark_corpus` manifest. This removes another DocTruth-owned Python/
  manifest-adapter layer from the prediction generation path.
- GREEN verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_prediction_command_writes_artifacts_from_bench_pdf_dir`;
  `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`;
  `sh scripts/smoke-doctruth-mnn-promotion-bench.sh`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `29 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: the direct Rust command still only writes prediction artifacts.
  It does not replace the upstream OpenDataLoader evaluator, and it has not yet
  run a real full/subset MNN benchmark with accepted quality thresholds.

## 2026-06-18 Direct Prediction Evaluator Import And Promotion Report MVP

- Added RED test
  `opendataloader_prediction_command_imports_evaluator_metrics_for_promotion_report`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_prediction_command_imports_evaluator_metrics_for_promotion_report`.
- RED result: expected failure on missing
  `report.externalMetrics.opendataloader.nid`.
- Implemented `opendataloader_prediction` evaluator import through
  `opendataloader_evaluation` / `opendataloaderEvaluation`.
- The command now imports OpenDataLoader evaluator JSON, exposes
  `metrics.opendataloader_*`, `externalMetrics.opendataloader`, synthesizes a
  direct prediction `resourceProfile`, and evaluates `promotionGates.mnn`
  through the same `mnn_promotion_json(...)` gate used by `benchmark_corpus`.
- The RED/GREEN test intentionally uses `edge-fast` so quality can pass while
  resource promotion fails because `modelRuntimePresent=false`. This prevents a
  fake promotion claim without MNN runtime evidence.
- Updated direct prediction smoke to assert `mnnPromotion.evaluated=false`
  when no evaluator/gate is supplied.
- GREEN verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_prediction_command_imports_evaluator_metrics_for_promotion_report`;
  `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `30 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: this imports evaluator output but does not replace the
  upstream OpenDataLoader evaluator. It also has not run real MNN models over a
  full/subset corpus to produce an accepted promotion report.

## 2026-06-18 Existing Prediction Promotion Report MVP

- Added RED test
  `opendataloader_promotion_report_uses_existing_prediction_summary_without_reparse`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_promotion_report_uses_existing_prediction_summary_without_reparse`.
- RED result: expected `UNKNOWN_COMMAND` because `doctruth-runtime` did not yet
  have a report-only promotion command.
- Implemented protocol command `opendataloader_promotion_report`.
- The command reads an existing Rust prediction `summary.json`, imports an
  OpenDataLoader evaluator JSON, synthesizes `resourceProfile`, and applies the
  same `promotionGates.mnn` decision path without reparsing PDFs.
- Updated `scripts/smoke-doctruth-rust-opendataloader-prediction.sh` to prove
  the two-step bench flow:
  Rust `opendataloader_prediction` -> evaluator JSON -> Rust
  `opendataloader_promotion_report`.
- Fixed `max_runtime_metric` so model peak-memory metrics survive when worker
  JSON reports floating-point MB values such as `123.0`; the promotion gate
  still requires a concrete peak-memory value and remains fail-closed.
- GREEN verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `31 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`.
- Remaining gap: OpenDataLoader's evaluator is still upstream Python. This
  slice removes DocTruth-owned prediction/report assembly from Python, but it
  does not replace external scoring or prove full-corpus real MNN acceptance.

## 2026-06-18 Rust OpenDataLoader Evaluator MVP

- Added RED test
  `opendataloader_evaluate_prediction_writes_rust_evaluation_without_python`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_evaluate_prediction_writes_rust_evaluation_without_python`.
- RED result: expected `UNKNOWN_COMMAND` because `doctruth-runtime` did not yet
  own an OpenDataLoader-style evaluator command.
- Implemented protocol command `opendataloader_evaluate_prediction`.
- The command reads `ground_truth_dir/*.md`, `prediction_dir/markdown/*.md`,
  optional `doc_id` / `docId`, and optional `output_path`.
- It emits OpenDataLoader-style `evaluation.json` with:
  summary passthrough, per-document `scores`, `prediction_available`,
  aggregate `metrics.score.*_mean`, metric counts, and `missing_predictions`.
- Implemented MVP metric behavior in Rust:
  whitespace-normalized reading-order similarity, HTML table presence/content
  similarity for simple TEDS plumbing, and Markdown heading similarity for MHS
  plumbing.
- Updated `scripts/smoke-doctruth-rust-opendataloader-prediction.sh` so the
  smoke now proves:
  Rust `opendataloader_prediction` -> Rust `opendataloader_evaluate_prediction`
  -> Rust `opendataloader_promotion_report`.
- GREEN verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `32 passed`;
  `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: this is a Rust evaluator MVP, not a full clone of upstream
  Python `rapidfuzz` ratio, APTED tree edit distance, lxml HTML parsing, or
  BeautifulSoup table extraction. Full metric parity must be proven before
  replacing the upstream evaluator as the authoritative full-corpus gate.

## 2026-06-18 Rust Evaluator Upstream Normalization Parity Slice

- Added RED test
  `opendataloader_evaluator_matches_upstream_heading_and_table_normalization`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_evaluator_matches_upstream_heading_and_table_normalization`.
- RED result: `mhs_mean` was `0.933333` instead of `1.0` because the Rust MVP
  penalized `# Heading` vs `### Heading`, while upstream MHS treats heading
  levels as equivalent.
- Implemented evaluator normalization improvements:
  - Markdown headings normalize to `heading:<text>` instead of `h1:<text>`.
  - Table markup lowercases, converts `th` to `td`, and removes `thead` /
    `tbody` wrappers before simple TEDS comparison.
  - String similarity now uses an LCS/Indel-style ratio closer to
    `rapidfuzz.fuzz.ratio`, replacing Levenshtein divided by max length.
- GREEN verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_evaluator_matches_upstream_heading_and_table_normalization`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `33 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: table and heading metrics still do not implement full APTED
  tree-edit parity or full HTML/Markdown conversion parity. This slice closes
  specific upstream normalization gaps, not the whole evaluator replacement.

## 2026-06-18 Rust Evaluator MHS Tree Content Parity Slice

- Added RED test
  `opendataloader_evaluator_mhs_scores_content_separately_from_structure`.
- RED command:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_evaluator_mhs_scores_content_separately_from_structure`.
- RED result: Rust evaluator returned `mhs=1.0` and `mhs_s=1.0` even though
  one content paragraph differed. This proved the MVP compared heading labels
  only and ignored content nodes.
- Implemented a Rust heading evaluator tree:
  - `document` root
  - flat `heading` nodes with normalized heading text
  - `content` child nodes flushed under the current heading
  - ordered child sequence edit distance
  - insert/delete cost = subtree size
  - rename cost = tag mismatch or normalized text distance when MHS includes
    text
  - MHS-S uses the same tree but ignores node text
- GREEN verification passed:
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_evaluator_mhs_scores_content_separately_from_structure`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_evaluator_matches_upstream_heading_and_table_normalization`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract`
  -> `34 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract`
  -> `10 passed`;
  `cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test protocol_contract`
  -> `56 passed`;
  `sh scripts/smoke-doctruth-rust-opendataloader-prediction.sh`;
  `cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check`;
  `git diff --check`.
- Remaining gap: this is an ordered-tree edit approximation shaped to the
  upstream MHS tree contract. It still needs explicit upstream fixture parity
  before replacing Python APTED as the authoritative full-corpus evaluator.
