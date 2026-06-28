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
  `third_party/opendataloader-bench/prediction/doctruth-java-core-auto-mnn-full200-v2/full200/`
- DocTruth revision used for run: worktree benchmark run, pending commit
- Runtime profile: `edge-model`
- Corpus: 200 OpenDataLoader Bench PDFs
- Prediction: 200 parsed, 0 failed
- Overall mean: `0.781875`
- NID mean: `0.900985`
- TEDS mean: `0.736174`
- MHS mean: `0.492119`
- Resource: mean `127.476316` ms/doc, one OCR model route
  (`01030000000141`), no Python/Torch/Docling production residency
- Interpretation: current Java/OpenDataLoader-compatible quality core clears
  the initial local acceptance baseline, but it is still not OpenDataLoader
  hybrid parity. The next gaps are OCR/model-backed tables, multi-segment
  rowspans, remaining heading hierarchy misses, and broader paragraph/list
  parity.

## Next Processor Work

The latest full200 low-score buckets are owned by processor families before
new sample repairs are accepted.

| Processor | Bucket | Current cases | Current metric | Next action |
| --- | --- | --- | --- | --- |
| HeadingProcessor | heading_hierarchy | 57 | mhs | port generalized heading hierarchy reconstruction before additional case repairs |
| TaggedDocumentProcessor | two_column_reading_order,sidebar_reading_order | 15 | nid | port generalized tagged reading-order reconstruction for two-column and sidebar layouts |
| TableStructureNormalizer | table_structure | 5 | teds | port generalized table structure normalization before adding more table case repairs |
| SpecialTableProcessor | table_false_positive_rejection,text_noise overlap | 18 | overall/teds | port generalized false-table and text-noise overlap rejection gates |
| ContentFilterProcessor | text_noise_filtering | 18 | overall | port generalized text-noise filtering for latest full200 noisy-content failures |

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

## Heuristic Ownership

Existing parser-quality rules must have a processor owner before they can be
treated as parity work. This keeps future changes from becoming sample-specific
patches.

| Heuristic | Owning processor | DocTruth owner | Focused test |
| --- | --- | --- | --- |
| hidden_offpage_tiny_duplicate_text_filter | ContentFilterProcessor | content_filter_probe | opendataloader_content_filter_probe |
| right_aligned_paragraph_precedence | ParagraphProcessor | paragraph_merge | opendataloader_line_paragraph_contract |
| wrapped_list_continuation | ListProcessor | structure_probe | opendataloader_structure_contract |
| nested_list_hierarchy | ListProcessor | structure_probe | opendataloader_structure_contract |
| caption_marker_classification | CaptionProcessor | structure_probe | opendataloader_structure_contract |
| survey_chart_table_rejection | SpecialTableProcessor | table_classifier_probe | opendataloader_table_processor_contract |
| borderless_cluster_table_reconstruction | ClusterTableProcessor | table_cluster | opendataloader_table_processor_contract |
| ocr_rescue_sparse_java_output_only | HybridDocumentProcessor | java_core_auto_mnn | benchmark_corpus_contract |
| prediction_markdown_repair | DocumentProcessor | prediction_export | opendataloader_prediction_contract |

## Behavior-Family Contract Buckets

Processor parity is accepted by behavior family, not by one benchmark PDF id.
A focused test may use a named fixture, but the rule under test must generalize
to a layout or parsing behavior class. A change that only says
`01030000000110 now passes` is not enough; it must be owned by a bucket such as
`borderless_tables`, `heading_hierarchy`, or `two_column_reading_order`.

| Contract bucket | Owning processor | Contract style | PDF-id patch allowed |
| --- | --- | --- | --- |
| text_noise_filtering | ContentFilterProcessor | behavior_family | no |
| two_column_reading_order | TaggedDocumentProcessor | behavior_family | no |
| sidebar_reading_order | TaggedDocumentProcessor | behavior_family | no |
| paragraph_merge | ParagraphProcessor | behavior_family | no |
| heading_hierarchy | HeadingProcessor | behavior_family | no |
| list_grouping | ListProcessor | behavior_family | no |
| caption_binding | CaptionProcessor | behavior_family | no |
| bordered_tables | TableBorderProcessor | behavior_family | no |
| borderless_tables | ClusterTableProcessor | behavior_family | no |
| table_false_positive_rejection | SpecialTableProcessor | behavior_family | no |
| ocr_sparse_page_rescue | HybridDocumentProcessor | behavior_family | no |

## Temporary Benchmark Repairs

These repairs are accepted benchmark repairs, not processor parity claims. Each
repair stays temporary until the owning processor has generalized behavior-
family coverage and full200 evidence for the replacement plan.

| Repair | Processor | Bucket | Parity claim | Focused test | Replacement plan |
| --- | --- | --- | --- | --- | --- |
| remittance_growth_table_reconstruction | TableStructureNormalizer | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized multi-column table reconstruction before marking TableStructureNormalizer matched |
| kinematic_viscosity_table_reconstruction | TableStructureNormalizer | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized numeric table reconstruction before marking TableStructureNormalizer matched |
| chart_axis_fragment_demotion | SpecialTableProcessor | table_false_positive_rejection | false | opendataloader_table_processor_contract | replace with generalized chart-axis false-table rejection before marking SpecialTableProcessor matched |
| blank_comparison_table_merge | TableStructureNormalizer | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized blank-row label merge before marking TableStructureNormalizer matched |
| national_initiatives_table_normalization | TableStructureNormalizer | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized long-text table normalization before marking TableStructureNormalizer matched |
| eco_competence_framework_normalization | TableStructureNormalizer | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized framework-table normalization before marking TableStructureNormalizer matched |
| area_competence_table_promotion | ClusterTableProcessor | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized rowspan-style borderless table promotion before marking ClusterTableProcessor matched |
| training_dataset_fragment_merge | ClusterTableProcessor | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized adjacent table-fragment merging before marking ClusterTableProcessor matched |
| port_shipcall_column_stream_merge | ClusterTableProcessor | borderless_tables | false | PdfBorderlessTableExtractionTest | replace with generalized header-plus-column-stream merge before marking ClusterTableProcessor matched |
| inline_cation_observation_split | TableStructureNormalizer | bordered_tables | false | PdfBorderlessTableExtractionTest | replace with generalized inline caption/header/row-token splitting before marking TableStructureNormalizer matched |
| regulatory_narrative_shard_demotion | SpecialTableProcessor | table_false_positive_rejection | false | PdfBorderlessTableExtractionTest | replace with generalized narrative-shard false-table rejection before marking SpecialTableProcessor matched |

## Full200 Gate Contract

Full200 is a stage gate. It should run after a coherent processor family
changes, not after every tiny edit. The gate report must be structured enough
to show quality, resources, and failure buckets without relying on screenshots
or subjective review.

Required report fields:

| Field | Source |
| --- | --- |
| overall | `evaluation.json:metrics.score.overall_mean` |
| nid | `evaluation.json:metrics.score.nid_mean` |
| teds | `evaluation.json:metrics.score.teds_mean` |
| mhs | `evaluation.json:metrics.score.mhs_mean` |
| parsed_count | `summary.json:parsed_count` |
| failed_count | `summary.json:failed_count` |
| latency | `summary.json:total_elapsed` and `summary.json:elapsed_per_doc` |
| resources | `resources.json:rssSamples` process memory fields |
| production_residency | `summary.json:production_residency.python_torch_docling` |
| low_score_buckets | `low-score-buckets.json` behavior-family artifact from this matrix |
| artifact_path | OpenDataLoader Bench prediction output directory |
| previous_doc_truth_baseline | previous accepted DocTruth full200 artifact |

The default scripts write `summary.json`, `resources.json`,
`prediction-report.json`, and, when evaluation is enabled, `evaluation.json`
plus `low-score-buckets.json`. The Java-core parity wrapper checks summary and
metric presence before accepting smoke or full200 output. Future script changes
must preserve these fields and must not move latency/resource evidence into a
screenshot-only or free-form report.

`low-score-buckets.json` separates raw metric buckets from behavior-family
buckets. The behavior-family bucket names must match this matrix, but until the
evaluator consumes richer layout tags they are metric-proxy classifications, not
proof that a specific processor family caused the failure.

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
