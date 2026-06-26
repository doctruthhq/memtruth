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
| Heading promotion and hierarchy | partial | `PdfHeadingClassificationTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest`, `PdfTwoColumnSemanticSectionTest` | `doctruth-java-core-phase10-title-heading-full200/full200`: MHS `0.472714`, MHS_s `0.619291`, overall `0.746136` | Java/PDFBox heading signals survive into `TrustDocument`, `content_blocks`, OpenDataLoader `blocks[]`, `headings[]`, and clean Markdown heading nodes. Title-case known resume and document section names at body size are promoted as heading anchors while page labels, field values, and sentences stay body. Remaining heading gap is fine-grained hierarchy and missed headings that do not match title/all-caps/known-section rules. |
| Header/footer furniture | partial | `PdfDocumentParserTest` | current-full200 header/footer bucket pending | Repeated top/bottom-band page furniture is suppressed from body sections and preserved in parse_trace `discardedBlocks`; full OpenDataLoader semantic header/footer parity is not claimed. |
| Table detection | partial | `PdfPageTableExtractorTest`, `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase15-cluster-gated-full200/full200`: overall `0.758789`, TEDS `0.537275`; cases `01030000000121` and `01030000000182` now recover structured tables while `01030000000044` and `01030000000196` stay non-table text | Regular and borderless table extraction now handles multiple table runs on one page, detects wide long-text comparative tables, preserves dense benchmark matrix tables, rejects sparse grid furniture/whole-page text promoted as fake tables, restores headered column-stream numeric tables, restores data-only continuation numeric tables, and reconstructs selected text-heavy cluster tables when the text layer exposes stable row/cell positions. Full table parity is still not claimed because many weak-border, OCR/model, multi-segment rowspan, and chart-adjacent table cases remain. |
| Borderless table clustering | partial | `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase15-cluster-gated-full200/full200`; cases `01030000000178`, `01030000000200`, `01030000000117`, `01030000000121`, and `01030000000182` are covered by focused tests | Borderless clustering segments aligned row runs, assigns text by cell cluster for normal tables, absorbs stacked header bands into table rows, merges first-column continuation rows, has a wide-text comparative-table path with word-zone column assignment, splits dense spanning header cells by word-center column assignment, avoids promoting sparse one-cell grids, resume-style parallel section headings, table-of-contents pages, and ordinary two-column narrative text as borderless tables, and adds a final geometry-driven cluster fallback for text-heavy tables. Remaining gap: broader multi-segment cluster parity. |
| Table cell grid reconstruction | partial | `OpenDataLoaderBackendProtocolTest`, `PdfBorderlessTableExtractionTest`, `opendataloader_table_processor_contract` | `doctruth-java-core-phase15-cluster-gated-full200/full200` records 200/200 parsed at mean `81.109628` ms/doc, RSS peak `20MB`, and no Python/Torch/Docling residency | TrustTable cells are projected and real OpenDataLoader table smoke cases produce high TEDS for selected cases. Header-only/data-only spacer columns collapse for `Small / Medium / Large` style tables; wide long-text tables merge multi-row headers and blank-first continuation rows; dense matrix tables split spanning header cells; sparse grid false positives are discarded; headered column-stream numeric tables use data-row anchors plus header-zone projection; data-only continuation tables use numeric-row anchors and first-column continuation merging; text-heavy cluster tables now support stacked headers, single-cell header splitting, blank-first/lowercase continuation merges, explicit two-column Reagents/Supplies lists, and horizontal matrix row-label recovery. Remaining gaps are model/OCR table cases and multi-segment rowspans. |
| Caption binding | partial | `PdfDocumentParserTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest` | current-full200 caption buckets pending | Standalone table/figure-style captions adjacent to detected tables are promoted into `FigureSection`, preserve bbox evidence, and project as `caption` blocks in `content_blocks` and OpenDataLoader-shaped `blocks[]`; broader figure, image, and full-bench caption parity is still pending. |
| OCR region routing | partial | `PdfDocumentParserTest`, `TrustDocumentAdapterTest` | scanned/OCR corpus pending | Low-text pages route through OCR worker SPI; worker-returned regions now remain separate bbox-backed parser sections and become `OCR_REGION` units under OCR parser runs, but OpenDataLoader strategy parity is not proven. |
| Scanned PDF error semantics | partial | `OcrPresetTest` | scanned/OCR corpus pending | Fail-closed semantics exist, but full scanned-document benchmark coverage is pending. |

## Current Priority

1. Broaden table-cell grid normalization beyond the current smoke and wide-text cases, then cover model/OCR table cases.
2. Copy/adapt remaining paragraph/list/heading hierarchy processors where full-bench buckets still lag.
3. Re-run OpenDataLoader Bench and update this report with case-level evidence.
4. Only mark a row `matched` when the focused test and full-bench evidence are both present.

## Latest Full200 Run

`doctruth-java-core-phase15-cluster-gated-full200/full200` is the latest
recorded Java-core run. It parsed 200/200 documents in `16221.925625` ms, with
a mean `81.109628` ms/doc, lazy model startup enabled, process RSS peak `20MB`,
and no Python/Torch/Docling production residency.

Quality now clears the initial plan target:

```text
overall: 0.758789
nid:     0.890112
teds:    0.537275
mhs:     0.485718
```

The phase8 sparse-grid guard fixed a real class of table false positives,
especially content pages where one large text cell was being rendered as a fake
table. Phase9 then rendered existing heading units as Markdown heading nodes in
clean Markdown, raising MHS from `0.006794` to `0.315461` and overall from
`0.626221` to `0.706434` without a material runtime regression. Phase10 added
standalone title-case document heading classification, lifting overall to
`0.746136` and MHS to `0.472714`.

Phase11 added column-stream numeric table reconstruction for text-layer tables
where data rows expose stable numeric anchors but header rows and first-column
labels are split across lines. Case `01030000000051` improved from TEDS `0.0`
to `0.998662`, and the full200 TEDS mean rose from `0.341325` to `0.378735`.
Phase12 broadened that family to three-column observer tables and data-only
continuation tables. Cases `01030000000045` and `01030000000053` improved from
TEDS `0.0` to `1.0`, and the full200 TEDS mean rose to `0.426354`.
Phase13 added a final geometry-driven cluster fallback for text-heavy tables
after the existing numeric fallback. It restored the promotional-materials table
in `01030000000178` to TEDS `0.998433`, the lab measurement matrix in
`01030000000117` to TEDS `1.0`, and partially restored the long service-flow
table in `01030000000200` to TEDS `0.41318`. Full200 TEDS rose to `0.503217`,
and MHS rose to `0.483981`.

Phase14 broadened cluster handling for two-column list tables and horizontal
matrix tables, but it over-promoted ordinary two-column narrative pages,
table-of-contents pages, and figure-adjacent prose into Markdown tables. The
focused targets improved, but overall quality regressed, so that run is not an
accepted baseline. Phase15 added a post-normalization table-likeness gate:
explicit two-column list headers such as `Reagents`/`Supplies` are still
accepted, horizontal matrix headers remain accepted, and compact multi-column
rows are accepted, while ordinary two-column prose and TOC pages stay as text.
Case `01030000000121` improved from TEDS `0.0` to `0.996544`, case
`01030000000182` improved from TEDS `0.0` to `0.522366`, and the worst
phase14 false positives `01030000000044` and `01030000000196` returned to the
phase13 scores.

Overall, TEDS, and MHS now beat the historical initial acceptance baseline
`overall=0.745414`, `TEDS=0.496416`, and `MHS=0.483837`. This is still not a
claim of full OpenDataLoader hybrid/model parity. The next high-impact gaps are
multi-segment rowspan tables, OCR/image-only table content, chart/table
distinction, remaining heading hierarchy misses, and broader reading-order/text
normalization.
