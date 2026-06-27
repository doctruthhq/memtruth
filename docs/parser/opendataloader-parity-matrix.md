# OpenDataLoader Parity Matrix

This matrix tracks DocTruth runtime parity against the Apache-2.0
OpenDataLoader PDF processor surface. Status values are conservative and do not
claim parser behavior that has not been ported or verified in DocTruth.

Current execution boundary: Java/OpenDataLoader-compatible parser core is the
current quality source of truth. Rust owns the runtime shell and Python
replacement boundary. Python/OpenDataLoader original runners are oracle-only.

## Source Snapshot

- Upstream repository:
  https://github.com/opendataloader-project/opendataloader-pdf
- License: Apache-2.0
- Reference commit: d1845179a1286bbb76f9618e8b6c8f51509a52f4
- Local path: `third_party/opendataloader-pdf-reference`
- Usage: local behavior reference, benchmark input, and oracle source for
  Java parser-core ports first, with Rust ports only after benchmark evidence
  supports replacement. The reference files are not compiled into DocTruth and
  are not a production parser fallback.

## Status Values

- `ported`: behavior is implemented and covered in DocTruth Java parser-core
  tests, plus Rust shell tests when benchmark/runtime packaging is affected.
- `partial`: related behavior exists, but parity is incomplete or still under
  verification.
- `not_ported`: no DocTruth-owned runtime equivalent has been added yet.
- `oracle_only`: used as an external comparison or schema reference, not as a
  DocTruth runtime implementation.
- `intentionally_skipped`: out of scope for DocTruth runtime by design.

## Latest Full200 Snapshot

- Report:
  `docs/parser/opendataloader-processor-gap-report.md`
- Artifacts:
  `third_party/opendataloader-bench/prediction/doctruth-java-core-phase27-regulatory-narrative-full200/full200/`
- DocTruth revision used for run: worktree benchmark run, pending commit
- Runtime profile: `edge-model`
- Corpus: 200 OpenDataLoader Bench PDFs
- Prediction: 200 parsed, 0 failed
- Overall mean: `0.779731`
- NID mean: `0.898148`
- TEDS mean: `0.736174`
- MHS mean: `0.489455`
- Resource: mean `81.093350` ms/doc, process RSS peak `21MB`, no
  Python/Torch/Docling production residency
- Interpretation: current Java/OpenDataLoader-compatible quality core clears
  the initial local acceptance baseline, but it is still not OpenDataLoader
  hybrid parity. The next gaps are OCR/model-backed tables, multi-segment
  rowspans, remaining heading hierarchy misses, and broader paragraph/list
  parity.

## Processor Matrix

| Upstream processor | Status | DocTruth owner | Focused test | Full200 evidence |
| --- | --- | --- | --- | --- |
| DocumentProcessor | partial | document_parse | benchmark_corpus_contract | current full200 report |
| TaggedDocumentProcessor | partial | structure_tree | benchmark_corpus_contract | current full200 report |
| TextProcessor | partial | text_filter | opendataloader_text_processor_contract | text-noise bucket pending |
| TextLineProcessor | partial | line_grouping | opendataloader_line_paragraph_contract | reading-order bucket pending |
| ParagraphProcessor | partial | paragraph_merge | opendataloader_line_paragraph_contract | reading-order bucket pending |
| HeadingProcessor | partial | structure_probe | opendataloader_structure_contract | MHS bucket pending |
| ListProcessor | partial | structure_probe | opendataloader_structure_contract | list bucket pending |
| CaptionProcessor | partial | structure_probe | opendataloader_structure_contract | caption bucket pending |
| LevelProcessor | partial | structure_probe | opendataloader_structure_contract | MHS bucket pending |
| HeaderFooterProcessor | partial | header_footer | PdfDocumentParserTest | header/footer bucket pending |
| ContentFilterProcessor | partial | content_filter_probe | opendataloader_content_filter_probe | text-noise bucket pending |
| TextDecorationProcessor | partial | text_decoration | opendataloader_text_processor_contract | text-decoration bucket pending |
| TableBorderProcessor | partial | table_border_probe | opendataloader_table_processor_contract | TEDS bucket pending |
| ClusterTableProcessor | partial | table_cluster | opendataloader_table_processor_contract | TEDS bucket pending |
| SpecialTableProcessor | partial | table_special_cases | opendataloader_table_processor_contract | TEDS bucket pending |
| TableStructureNormalizer | partial | table_normalizer | opendataloader_table_processor_contract | TEDS bucket pending |
| HiddenTextProcessor | partial | content_filter_probe | opendataloader_content_filter_probe | text-noise bucket pending |
| HybridDocumentProcessor | partial | java_core_auto_mnn | benchmark_corpus_contract | current full200 report |
| TriageProcessor | partial | triage_probe | opendataloader_triage_probe | routing bucket pending |
| DoclingSchemaTransformer | oracle_only | docling_schema_reference | opendataloader_parity_matrix_contract | not a runtime processor |
| OcrStrategy | partial | ocr_routing | model_worker_contract | scanned/OCR bucket pending |

## Pipeline Stage Order

This stage order is the contract for OpenDataLoader-style behavior alignment.
It is not a second parser schema. Each stage normalizes behavior toward
DocTruth-owned `TrustDocument` output.

| Stage | Owning reference processor |
| --- | --- |
| pdf_text_extraction | DocumentProcessor |
| text_normalization | TextProcessor |
| content_filtering | ContentFilterProcessor |
| line_grouping | TextLineProcessor |
| paragraph_merge | ParagraphProcessor |
| heading_hierarchy | HeadingProcessor |
| list_grouping | ListProcessor |
| caption_binding | CaptionProcessor |
| table_border_detection | TableBorderProcessor |
| borderless_table_clustering | ClusterTableProcessor |
| table_structure_normalization | TableStructureNormalizer |
| chart_table_gate | SpecialTableProcessor |
| ocr_table_model_routing | HybridDocumentProcessor |
| reading_order | TaggedDocumentProcessor |
| trust_document_export | DocumentProcessor |

## DocumentProcessor

Status: `partial`. DocTruth has document-level parsing and `TrustDocument`
emission, but full OpenDataLoader processor parity is not yet claimed.

## TaggedDocumentProcessor

Status: `partial`. Tagged or structured PDF signals are part of the runtime
direction, but complete upstream behavior remains under parity review.

## TextProcessor

Status: `partial`. Native text extraction exists through the Rust PDF substrate,
but upstream text processing parity is still incomplete.

## TextLineProcessor

Status: `partial`. Text line handling exists in the runtime, but line grouping
has not been certified against the upstream processor.

## ParagraphProcessor

Status: `partial`. Paragraph-like grouping is present only as partial structure
recovery and requires further OpenDataLoader parity coverage.

## HeadingProcessor

Status: `partial`. Heading signals exist in parser-quality work, but upstream
heading processor parity is still under verification.

## ListProcessor

Status: `partial`. List detection is treated as partial document structure
recovery and is not yet a full upstream processor port.
`opendataloader_structure_probe` covers sequential lower/upper letter lists,
sequential numeric lists, bullet lists, and non-sequential false-positive
guards. It also joins wrapped continuation lines and emits structured
`listItems` with indentation-derived levels for nested list hierarchy while
preserving the legacy flat `items` field. Full-bench list evidence remains
pending.

## CaptionProcessor

Status: `partial`. Standalone table/figure-style captions adjacent to detected
tables are promoted into bbox-backed caption blocks in the Java/OpenDataLoader-
compatible parser core. Broader image/figure caption behavior and full-bench
caption evidence remain pending. `opendataloader_structure_probe` recognizes
`Figure`, `Table`, `Fig.`, and `Tab.` numeric caption markers while keeping
ordinary phrases such as `Figure skating` or `table stakes` as paragraph text.

## LevelProcessor

Status: `partial`. Structural level handling exists in layout and reading-order
recovery, and `opendataloader_structure_probe` now maps numbered heading depth
(`1.`, `1.2`, `1.2.3`) to heading levels. Full upstream hierarchy parity and
full-bench MHS evidence remain pending.

## HeaderFooterProcessor

Status: `partial`. Repeated top/bottom-band page furniture is suppressed from
body sections and preserved in parse_trace `discardedBlocks`. This is a narrow
Java/OpenDataLoader-compatible parser-core behavior, not a complete semantic
header/footer object port.

## ContentFilterProcessor

Status: `partial`. `opendataloader_content_filter_probe` now exposes focused
hidden, off-page, tiny, and duplicate text filtering behavior at the runtime
boundary. Low-contrast graphics/color evidence and full upstream parity remain
pending.

## TextDecorationProcessor

Status: `partial`. Decoration signals such as underline and strike handling are
covered in part, but full upstream parity is not claimed.

## TableBorderProcessor

Status: `partial`. Table border signals are handled in part through Rust table
recognition, with upstream parity still incomplete.

## ClusterTableProcessor

Status: `partial`. Cluster-table behavior is represented in current parser
direction, but the upstream processor is not fully ported.

## SpecialTableProcessor

Status: `partial`. Special table cases are tracked as partial table-recognition
coverage until parity tests prove the behavior.

## TableStructureNormalizer

Status: `partial`. Table normalization exists only in partial form and remains a
known parity area. The runtime now forwards request-supplied `tableTextTokens`
and `ocrTokens` into configured table model workers, and the native MNN worker
can use those spans for bbox-backed cell text assignment; broader model/OCR
table quality remains unproven.

## HiddenTextProcessor

Status: `partial`. Hidden text filtering is covered by
`opendataloader_content_filter_probe` when hidden text candidates are provided,
but low-contrast graphics/color-derived hidden text evidence and full-bench
coverage remain pending.

## HybridDocumentProcessor

Status: `partial`. Hybrid parsing is represented by runtime orchestration and
model slots, but upstream hybrid behavior is not fully ported.

## TriageProcessor

Status: `partial`. Runtime routing and warnings cover some triage concerns, but
the upstream processor is not fully ported. The black-box
`opendataloader_triage_probe` now exposes replacement-ratio, vector-line,
table-border, suspicious-gap, large-image, and threshold routing signals for
focused parity tests.

## DoclingSchemaTransformer

Status: `oracle_only`. Docling-style schema transformation is treated as a
comparison or oracle surface, not as a DocTruth runtime output contract.

## OcrStrategy

Status: `partial`. OCR routing is part of the runtime contract. Worker-returned
OCR regions are preserved as bbox-backed parser sections and adapt into
`OCR_REGION` trust units when the parser backend is OCR-shaped, but full
OpenDataLoader strategy parity has not been verified.
