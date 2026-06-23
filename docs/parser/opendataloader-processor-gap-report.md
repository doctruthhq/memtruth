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
| Hidden/off-page/tiny/background text filtering | partial | `PdfVisualLayoutParserTest` | current-full200 text-noise bucket | Some visual filtering exists, but the OpenDataLoader filter stack is not fully ported. |
| Duplicate text suppression | partial | `PdfVisualLayoutParserTest` | current-full200 text-noise bucket | Local duplicate control exists only through layout grouping and needs processor-level parity tests. |
| XY-Cut geometry reading order | partial | `PdfTwoColumnSemanticSectionTest` | current-full200 reading-order bucket | Two-column behavior exists, but full XY-Cut++ projection parity is not proven. |
| Paragraph and line merging | partial | `PdfDocumentParserTest` | current-full200 reading-order bucket | Basic merging exists; OpenDataLoader paragraph heuristics are not fully matched. |
| Heading promotion and hierarchy | partial | `PdfTwoColumnSemanticSectionTest` | current-full200 heading buckets | Headings are detected, but hierarchy mismatch remains one of the largest gaps. |
| Table detection | partial | `PdfPageTableExtractorTest` | current-full200 table buckets | Regular table extraction exists but does not yet match all OpenDataLoader table cases. |
| Borderless table clustering | partial | `PdfBorderlessTableExtractionTest` | current-full200 table buckets | Borderless heuristic exists; cluster parity and row/column reconstruction remain incomplete. |
| Table cell grid reconstruction | partial | `OpenDataLoaderBackendProtocolTest` | current-full200 TEDS bucket | TrustTable cells are projected, but OpenDataLoader-equivalent grid semantics are not complete. |
| Caption binding | missing | TBD | TBD | Figure/table caption processor is not implemented as a dedicated parity stage. |
| OCR region routing | partial | `OcrPresetTest` | scanned/OCR corpus pending | OCR routing exists through worker SPI, but OpenDataLoader strategy parity is not proven. |
| Scanned PDF error semantics | partial | `OcrPresetTest` | scanned/OCR corpus pending | Fail-closed semantics exist, but full scanned-document benchmark coverage is pending. |

## Current Priority

1. Copy/adapt hidden/off-page/tiny/background text filters into Java first.
2. Copy/adapt duplicate suppression and geometry reading-order processors.
3. Re-run OpenDataLoader Bench and update this report with case-level evidence.
4. Only mark a row `matched` when the focused test and full-bench evidence are both present.
