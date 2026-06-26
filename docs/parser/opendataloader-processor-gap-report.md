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
| Heading promotion and hierarchy | partial | `PdfHeadingClassificationTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest`, `PdfTwoColumnSemanticSectionTest` | current-full200 heading buckets | Java/PDFBox heading signals now survive into `TrustDocument`, `content_blocks`, OpenDataLoader `blocks[]`, and `headings[]`; title-case known resume section names at body size are now promoted as heading anchors while field values and sentences stay body. Heading hierarchy mismatch remains one of the largest gaps. |
| Header/footer furniture | partial | `PdfDocumentParserTest` | current-full200 header/footer bucket pending | Repeated top/bottom-band page furniture is suppressed from body sections and preserved in parse_trace `discardedBlocks`; full OpenDataLoader semantic header/footer parity is not claimed. |
| Table detection | partial | `PdfPageTableExtractorTest`, `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase6-table-spacer-collapse` smoke: case `01030000000083` TEDS `0.9958`, case `01030000000127` TEDS `0.888889` | Regular and borderless table extraction now handles multiple table runs on one page instead of rejecting the whole page. Full OpenDataLoader table parity is still not claimed until full200 evidence is recorded. |
| Borderless table clustering | partial | `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase6-table-spacer-collapse` smoke table cases | Borderless clustering now segments aligned row runs, assigns text by cell cluster instead of character midpoint, absorbs stacked header bands into table rows, and merges first-column continuation rows such as `Environment, Health and Safety`, `Compliances with imprisonment`, and `Percentage of imprisonment clauses`. Remaining gap: broader full-bench cluster parity. |
| Table cell grid reconstruction | partial | `OpenDataLoaderBackendProtocolTest`, `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase6-table-spacer-collapse` smoke TEDS cases | TrustTable cells are projected and real OpenDataLoader table smoke cases now produce non-zero TEDS. Header-only/data-only spacer columns collapse for `Small / Medium / Large` style tables; remaining gaps are model/OCR table cases and full200 coverage. |
| Caption binding | partial | `PdfDocumentParserTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest` | current-full200 caption buckets pending | Standalone table/figure-style captions adjacent to detected tables are promoted into `FigureSection`, preserve bbox evidence, and project as `caption` blocks in `content_blocks` and OpenDataLoader-shaped `blocks[]`; broader figure, image, and full-bench caption parity is still pending. |
| OCR region routing | partial | `PdfDocumentParserTest`, `TrustDocumentAdapterTest` | scanned/OCR corpus pending | Low-text pages route through OCR worker SPI; worker-returned regions now remain separate bbox-backed parser sections and become `OCR_REGION` units under OCR parser runs, but OpenDataLoader strategy parity is not proven. |
| Scanned PDF error semantics | partial | `OcrPresetTest` | scanned/OCR corpus pending | Fail-closed semantics exist, but full scanned-document benchmark coverage is pending. |

## Current Priority

1. Broaden table-cell grid normalization beyond the current smoke cases and cover model/OCR table cases.
2. Copy/adapt remaining paragraph/list/heading hierarchy processors where full-bench buckets still lag.
3. Re-run OpenDataLoader Bench and update this report with case-level evidence.
4. Only mark a row `matched` when the focused test and full-bench evidence are both present.
