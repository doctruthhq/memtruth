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
| PDF text normalization | partial | `PdfDocumentParserTest` | current-full200 text buckets | Basic text-layer extraction exists, but normalization still contributes to reading-order/text mismatch buckets. |
| Hidden/off-page/tiny/background text filtering | partial | `PdfTextPositionFilterTest` | current-full200 text-noise bucket | Text-position filtering now covers tiny, off-page, blank/control-only text; contrast/background filtering is still pending. |
| Duplicate text suppression | partial | `PdfTextPositionFilterTest` | current-full200 text-noise bucket | Same-text overlapping duplicates are filtered; full OpenDataLoader chunk-level duplicate parity is still pending. |
| XY-Cut geometry reading order | partial | `PdfGeometryReadingOrderTest` | current-full200 reading-order bucket | Projection-cut ordering now covers a full-width heading between two-column regions; full XY-Cut++ projection parity is not proven. |
| Paragraph and line merging | partial | `PdfDocumentParserTest` | current-full200 reading-order bucket | Basic merging exists; OpenDataLoader paragraph heuristics are not fully matched. |
| Heading promotion and hierarchy | partial | `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest`, `PdfTwoColumnSemanticSectionTest` | current-full200 heading buckets | Java/PDFBox heading signals now survive into `TrustDocument`, `content_blocks`, OpenDataLoader `blocks[]`, and `headings[]`; heading hierarchy mismatch remains one of the largest gaps. |
| Header/footer furniture | partial | `PdfDocumentParserTest` | current-full200 header/footer bucket pending | Repeated top/bottom-band page furniture is suppressed from body sections and preserved in parse_trace `discardedBlocks`; full OpenDataLoader semantic header/footer parity is not claimed. |
| Table detection | partial | `PdfPageTableExtractorTest` | current-full200 table buckets | Regular table extraction exists but does not yet match all OpenDataLoader table cases. |
| Borderless table clustering | partial | `PdfBorderlessTableExtractionTest` | current-full200 table buckets | Borderless heuristic exists; cluster parity and row/column reconstruction remain incomplete. |
| Table cell grid reconstruction | partial | `OpenDataLoaderBackendProtocolTest` | current-full200 TEDS bucket | TrustTable cells are projected, but OpenDataLoader-equivalent grid semantics are not complete. |
| Caption binding | partial | `PdfDocumentParserTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest` | current-full200 caption buckets pending | Standalone table/figure-style captions adjacent to detected tables are promoted into `FigureSection`, preserve bbox evidence, and project as `caption` blocks in `content_blocks` and OpenDataLoader-shaped `blocks[]`; broader figure, image, and full-bench caption parity is still pending. |
| OCR region routing | partial | `PdfDocumentParserTest`, `TrustDocumentAdapterTest` | scanned/OCR corpus pending | Low-text pages route through OCR worker SPI; worker-returned regions now remain separate bbox-backed parser sections and become `OCR_REGION` units under OCR parser runs, but OpenDataLoader strategy parity is not proven. |
| Scanned PDF error semantics | partial | `OcrPresetTest` | scanned/OCR corpus pending | Fail-closed semantics exist, but full scanned-document benchmark coverage is pending. |

## Current Priority

1. Copy/adapt hidden/off-page/tiny/background text filters into Java first.
2. Copy/adapt duplicate suppression and geometry reading-order processors.
3. Re-run OpenDataLoader Bench and update this report with case-level evidence.
4. Only mark a row `matched` when the focused test and full-bench evidence are both present.
