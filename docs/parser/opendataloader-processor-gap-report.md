# OpenDataLoader Processor Gap Report

This report tracks the processor-level work required before DocTruth can claim
OpenDataLoader quality parity. The current product boundary is:

```text
Java/OpenDataLoader-compatible parser core = current quality source of truth
Rust runtime shell = Python replacement, packaging, resources, and benchmark runner
OpenDataLoader Python original = oracle-only comparison
TrustDocument = canonical DocTruth output
```

Status values are intentionally conservative:

- `matched`: focused test exists and at least one full-bench evidence case is recorded.
- `partial`: local behavior exists, but coverage or full-bench evidence is incomplete.
- `oracle-only`: behavior exists only in the reference/oracle path.
- `missing`: no equivalent DocTruth behavior is implemented yet.

| Processor area | Status | Focused test | Full-bench evidence | Notes |
| --- | --- | --- | --- | --- |
| PDF text normalization | partial | `PdfDocumentParserTest`, `PdfTextRenderingNormalizationTest`, `PdfTextPositionFilterTest` | current-full200 text buckets | Generated PDF text-layer output is covered for trimming and repeated-space compression in the live parser path; `PdfTextPositionFilter` also exposes box-level normalization and U+FFFD ratio helpers. Full chunk splitting/merge parity still needs bench evidence. |
| Hidden/off-page/tiny/background text filtering | partial | `PdfTextPositionFilterTest` | current-full200 text-noise bucket | Text-position filtering now covers tiny, off-page, blank/control-only text, and OpenDataLoader-style background-sized text boxes; low-contrast hidden text still requires graphics/color evidence. |
| Duplicate text suppression | partial | `PdfTextPositionFilterTest` | current-full200 text-noise bucket | Same-text overlapping duplicates are filtered, and contained same-baseline phrase fragments are now suppressed when geometry is strongly overlapping or horizontally contained. Production generated-PDF coverage is not used for this contained-fragment case because PDFBox interleaves overprinted phrase/fragments at character capture time (for example `Invoice ttottall dduuee`) instead of exposing stable phrase-plus-fragment chunks. Full OpenDataLoader chunk-level duplicate parity and benchmark evidence are still pending. |
| XY-Cut geometry reading order | partial | `PdfGeometryReadingOrderTest` | current-full200 reading-order bucket | Projection-cut ordering now covers a full-width heading between two-column regions and a narrow-outlier vertical-cut retry for page-marker-like gap elements; full XY-Cut++ projection parity is not proven. |
| Paragraph and line merging | partial | `PdfDocumentParserTest` | current-full200 reading-order bucket | Basic merging exists; OpenDataLoader paragraph heuristics are not fully matched. |
| Heading promotion and hierarchy | partial | `PdfHeadingClassificationTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest`, `PdfTwoColumnSemanticSectionTest` | `doctruth-java-core-phase9-heading-markdown-full200/full200`: MHS `0.315461`, MHS_s `0.405060`, overall `0.706434` | Java/PDFBox heading signals survive into `TrustDocument`, `content_blocks`, OpenDataLoader `blocks[]`, `headings[]`, and now clean Markdown heading nodes. Title-case known resume section names at body size are promoted as heading anchors while field values and sentences stay body. Remaining heading gap is classification/hierarchy recovery for cases where headings are still extracted as body text. |
| Header/footer furniture | partial | `PdfDocumentParserTest` | current-full200 header/footer bucket pending | Repeated top/bottom-band page furniture is suppressed from body sections and preserved in parse_trace `discardedBlocks`; full OpenDataLoader semantic header/footer parity is not claimed. |
| Table detection | partial | `PdfPageTableExtractorTest`, `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase8-sparse-grid-guard-full200/full200`: overall `0.626221`, NID `0.894930`, TEDS `0.341325`, MHS `0.006794`; sparse-grid case `01030000000198` improved to overall `0.477420`, NID `0.954839`; wide table case `01030000000088` stayed high at overall `0.916727`, TEDS `0.908856` | Regular and borderless table extraction now handles multiple table runs on one page, detects wide long-text comparative tables, preserves dense benchmark matrix tables, and rejects sparse grid furniture/whole-page text promoted as fake tables. Full parity is still not claimed because full200 remains below the historical target and model/OCR/table cases remain weak. |
| Borderless table clustering | partial | `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase8-sparse-grid-guard-full200/full200` plus earlier phase6 smoke table cases | Borderless clustering segments aligned row runs, assigns text by cell cluster for normal tables, absorbs stacked header bands into table rows, merges first-column continuation rows, has a wide-text comparative-table path with word-zone column assignment, splits dense spanning header cells by word-center column assignment, and avoids promoting sparse one-cell grids as borderless tables. Remaining gap: broader full-bench cluster parity. |
| Table cell grid reconstruction | partial | `OpenDataLoaderBackendProtocolTest`, `PdfBorderlessTableExtractionTest`, `opendataloader_table_processor_contract` | `doctruth-java-core-phase8-sparse-grid-guard-full200/full200` records 200/200 parsed at mean `76.179168` ms/doc with no Python/Torch/Docling residency | TrustTable cells are projected and real OpenDataLoader table smoke cases produce high TEDS for selected cases. Header-only/data-only spacer columns collapse for `Small / Medium / Large` style tables; wide long-text tables merge multi-row headers and blank-first continuation rows; dense matrix tables split spanning header cells; sparse grid false positives are discarded before TrustDocument emission. Remaining gaps are model/OCR table cases, heading hierarchy, and full200 parity. |
| Caption binding | partial | `PdfDocumentParserTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest` | current-full200 caption buckets pending | Standalone table/figure-style captions adjacent to detected tables are promoted into `FigureSection`, preserve bbox evidence, and project as `caption` blocks in `content_blocks` and OpenDataLoader-shaped `blocks[]`; broader figure, image, and full-bench caption parity is still pending. |
| OCR region routing | partial | `PdfDocumentParserTest`, `TrustDocumentAdapterTest` | scanned/OCR corpus pending | Low-text pages route through OCR worker SPI; worker-returned regions now remain separate bbox-backed parser sections and become `OCR_REGION` units under OCR parser runs, but OpenDataLoader strategy parity is not proven. |
| Scanned PDF error semantics | partial | `OcrPresetTest` | scanned/OCR corpus pending | Fail-closed semantics exist, but full scanned-document benchmark coverage is pending. |

## Current Priority

1. Broaden table-cell grid normalization beyond the current smoke and wide-text cases, then cover model/OCR table cases.
2. Copy/adapt remaining paragraph/list/heading hierarchy processors where full-bench buckets still lag.
3. Re-run OpenDataLoader Bench and update this report with case-level evidence.
4. Only mark a row `matched` when the focused test and full-bench evidence are both present.

## Latest Full200 Run

`doctruth-java-core-phase9-heading-markdown-full200/full200` is the latest
recorded Java-core run. It parsed 200/200 documents in `15343.369` ms, with a
mean `76.716845` ms/doc, lazy model startup enabled, and no
Python/Torch/Docling production residency.

Quality is still below the plan target:

```text
overall: 0.706434
nid:     0.894879
teds:    0.341325
mhs:     0.315461
```

The phase8 sparse-grid guard fixed a real class of table false positives,
especially content pages where one large text cell was being rendered as a fake
table. Phase9 then rendered existing heading units as Markdown heading nodes in
clean Markdown, raising MHS from `0.006794` to `0.315461` and overall from
`0.626221` to `0.706434` without a material runtime regression. The next
high-impact gaps remain heading classification/hierarchy for headings still
extracted as body text, TEDS/table structure, OCR/image-only content, and
broader reading-order/text normalization.
