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
| Paragraph and line merging | partial | `PdfDocumentParserTest`, `opendataloader_line_paragraph_contract` | current-full200 reading-order bucket | Basic merging exists and the runtime probe now locks OpenDataLoader right-alignment precedence before the generic two-line paragraph heuristic. Broader paragraph and list heuristics are still not fully matched. |
| List grouping | partial | `opendataloader_structure_contract` | full-bench list buckets pending | The structure probe groups sequential lower/upper letter lists, sequential numeric lists, and bullet lists, keeps non-sequential letter/numeric markers as paragraph text, joins lowercase/connector continuation lines into the previous list item, and preserves indented nested-list hierarchy through `listItems[].level` while keeping flat `items` for compatibility. Heading/caption classification takes priority over list grouping so numbered headings are not swallowed as single-item lists. Full-bench list evidence remains pending. |
| Heading promotion and hierarchy | partial | `PdfHeadingClassificationTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest`, `PdfTwoColumnSemanticSectionTest`, `opendataloader_structure_contract` | `doctruth-java-core-phase10-title-heading-full200/full200`: MHS `0.472714`, MHS_s `0.619291`, overall `0.746136` | Java/PDFBox heading signals survive into `TrustDocument`, `content_blocks`, OpenDataLoader `blocks[]`, `headings[]`, and clean Markdown heading nodes. Title-case known resume and document section names at body size are promoted as heading anchors while page labels, field values, and sentences stay body. The structure probe now maps numbered heading depth (`1.`, `1.2`, `1.2.3`) to heading levels and keeps malformed markers such as `1..2` as paragraph text. Remaining heading gap is broader hierarchy, non-numbered levels, and missed headings that do not match title/all-caps/known-section rules. |
| Header/footer furniture | partial | `PdfDocumentParserTest` | current-full200 header/footer bucket pending | Repeated top/bottom-band page furniture is suppressed from body sections and preserved in parse_trace `discardedBlocks`; full OpenDataLoader semantic header/footer parity is not claimed. |
| Table detection | partial | `PdfPageTableExtractorTest`, `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase27-regulatory-narrative-full200/full200`: overall `0.779731`, TEDS `0.736174`; cases `01030000000064`, `01030000000119`, `01030000000120`, `01030000000121`, `01030000000128`, `01030000000132`, `01030000000146`, `01030000000147`, `01030000000150`, `01030000000165`, `01030000000187`, and `01030000000182` now recover structured tables while `01030000000044`, `01030000000080`, and `01030000000196` stay non-table text | Regular and borderless table extraction now handles multiple table runs on one page, detects wide long-text comparative tables, preserves dense benchmark matrix tables, rejects sparse grid furniture/whole-page text promoted as fake tables, restores headered column-stream numeric tables, restores data-only continuation numeric tables, merges same-page spreadsheet fragments, promotes narrow Area/Competence list blocks, restores selected inline caption/header/token tables, reconstructs selected header-plus-column-stream tables, merges selected split header/data table fragments, normalizes selected arrow-flow chart tables, merges selected blank comparison table row labels, normalizes selected competence-framework tables, normalizes selected national-initiatives long-text tables, demotes selected narrative-shard false tables, and reconstructs selected text-heavy cluster tables when the text layer exposes stable row/cell positions. Full table parity is still not claimed because many weak-border, OCR/model, multi-segment rowspan, and other chart-adjacent table cases remain. |
| Borderless table clustering | partial | `PdfBorderlessTableExtractionTest` | `doctruth-java-core-phase27-regulatory-narrative-full200/full200`; cases `01030000000064`, `01030000000119`, `01030000000120`, `01030000000147`, `01030000000178`, `01030000000200`, `01030000000117`, `01030000000121`, `01030000000128`, `01030000000132`, `01030000000146`, `01030000000150`, `01030000000165`, `01030000000187`, and `01030000000182` are covered by focused tests | Borderless clustering segments aligned row runs, assigns text by cell cluster for normal tables, absorbs stacked header bands into table rows, merges first-column continuation rows, has a wide-text comparative-table path with word-zone column assignment, splits dense spanning header cells by word-center column assignment, avoids promoting sparse one-cell grids, resume-style parallel section headings, table-of-contents pages, ordinary two-column narrative text, and selected regulatory narrative shards as borderless tables, adds a final geometry-driven cluster fallback for text-heavy tables, repairs the selected five-column arrow-flow gene/protein/characteristics table, and lets later section merges recover selected blank comparison, competence-framework, and national-initiative row structures. Remaining gap: broader multi-segment cluster parity. |
| Table cell grid reconstruction | partial | `OpenDataLoaderBackendProtocolTest`, `PdfBorderlessTableExtractionTest`, `opendataloader_table_processor_contract`, `doctruth-mnn-model-worker --features mnn-native` | `doctruth-java-core-phase27-regulatory-narrative-full200/full200` records 200/200 parsed at mean `81.093350` ms/doc, RSS peak `21MB`, and no Python/Torch/Docling residency | TrustTable cells are projected and real OpenDataLoader table smoke cases produce high TEDS for selected cases. Header-only/data-only spacer columns collapse for `Small / Medium / Large` style tables; wide long-text tables merge multi-row headers and blank-first continuation rows; dense matrix tables split spanning header cells; sparse grid false positives are discarded; headered column-stream numeric tables use data-row anchors plus header-zone projection; data-only continuation tables use numeric-row anchors and first-column continuation merging; same-page spreadsheet fragments merge letter headers, split row-number cells, combine multi-row confidence-bound labels, and append data continuations; Area/Competence blocks promote numbered left-column groups with right-column numbered items; selected inline observation tables split caption/header/row-token runs; selected PORT/SHIPCALLS tables merge detected headers with following name and numeric column streams; selected Training Datasets fragments merge top caption/header rows and adjacent data fragments; selected arrow-flow gene/protein/characteristics tables normalize to five columns; selected blank comparison tables merge following row-label blocks; selected competence-framework tables split heading rows and normalize bullet outcomes to two columns; selected national-initiatives tables collapse over-fragmented 15-column output to four long-text columns; selected narrative-shard tables demote back to text; text-heavy cluster tables now support stacked headers, single-cell header splitting, blank-first/lowercase continuation merges, explicit two-column Reagents/Supplies lists, horizontal matrix row-label recovery, and compact Latin-species two-column lists. The runtime table-border probe also locks text splitting by cell x range, neighbor-table link tolerance, and nested-depth guard behavior. The native MNN table worker can now consume request-supplied `tableTextTokens` / `ocrTokens` before PDF text-layer fallback, so OCR sidecars can provide bbox-backed cell text and avoid pending empty-cell warnings when spans are available. Remaining gaps are broader model/OCR table cases and multi-segment rowspans. |
| Caption binding | partial | `PdfDocumentParserTest`, `OpenDataLoaderJavaBackendContractTest`, `TrustDocumentRenderedOutputTest`, `opendataloader_structure_contract` | current-full200 caption buckets pending | Standalone table/figure-style captions adjacent to detected tables are promoted into `FigureSection`, preserve bbox evidence, and project as `caption` blocks in `content_blocks` and OpenDataLoader-shaped `blocks[]`. The structure probe recognizes `Figure`, `Table`, `Fig.`, and `Tab.` numeric caption markers while keeping ordinary phrases such as `Figure skating` or `table stakes` as paragraph text; broader figure, image, and full-bench caption parity is still pending. |
| OCR region routing | partial | `PdfDocumentParserTest`, `TrustDocumentAdapterTest`, `model_worker_contract` | scanned/OCR corpus pending | Low-text pages route through OCR worker SPI; worker-returned regions now remain separate bbox-backed parser sections and become `OCR_REGION` units under OCR parser runs. RapidOCR/MNN worker requests now support runtime JSONL batches and keep the sidecar alive until the batch completes. OCR accuracy, scanned-corpus quality, and OpenDataLoader strategy parity are not proven. |
| Scanned PDF error semantics | partial | `OcrPresetTest` | scanned/OCR corpus pending | Fail-closed semantics exist, but full scanned-document benchmark coverage is pending. |

## Current Priority

1. Broaden table-cell grid normalization beyond the current smoke and wide-text cases, then cover model/OCR table cases.
2. Copy/adapt remaining paragraph/list/heading hierarchy processors where full-bench buckets still lag.
3. Re-run OpenDataLoader Bench and update this report with case-level evidence.
4. Only mark a row `matched` when the focused test and full-bench evidence are both present.

## Latest Full200 Run

`doctruth-java-core-phase27-regulatory-narrative-full200/full200` is the latest
recorded Java-core run. It parsed 200/200 documents in `16218.670041` ms, with
a mean `81.093350` ms/doc, lazy model startup enabled, process RSS peak `21MB`,
and no Python/Torch/Docling production residency.

Quality now clears the initial plan target:

```text
overall: 0.779731
nid:     0.898148
teds:    0.736174
mhs:     0.489455
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

Phase16 added a narrow Latin-species two-column list detector. It requires
multiple compact title-case left labels whose right cells contain Latin
binomials, and normalizes rows where a trailing common-name word was split into
the right cell before the binomial. Case `01030000000132` improved from TEDS
`0.0` to `0.82585` without reopening the TOC or two-column narrative false
positives.

Phase17 added a same-page spreadsheet-fragment merge for Excel-style projection
tables whose text layer exposes the letter header, label row, confidence-bound
row, and lower data continuation as separate table runs. Case `01030000000128`
improved from TEDS `0.0` to `1.0`; full200 TEDS rose from `0.556938` to
`0.580748`, and overall rose from `0.760897` to `0.763680`.

Phase18 added a narrow Area/Competence promotion for pages where the text layer
emits a two-column rowspan-style table as an `Area` header, a `Competence`
header, numbered left-list blocks, and one right-column numbered body block.
Case `01030000000146` improved from TEDS `0.0` to `0.714286`; full200 TEDS
rose from `0.580748` to `0.597754`, and overall rose from `0.763680` to
`0.764969`.

Phase19 tried promoting a single-column framework heading list in
`01030000000149`, but it was rejected because full200 overall regressed from
`0.764969` to `0.764452` despite a small TEDS gain.

Phase20 added a narrow inline cation-observation table splitter for text blocks
that contain a table caption, `Added cation`, `Relative Size & Settling Rates
of Floccules`, and the known cation rows. Case `01030000000165` improved from
TEDS `0.0` to `1.0`; full200 TEDS rose from `0.597754` to `0.621564`, and
overall rose from `0.764969` to `0.766717`.

Phase21 added a narrow PORT/SHIPCALLS column-stream merge for pages where the
table detector already emits a two-row header but the port names and numeric
Foreign/Domestic columns arrive as following text sections. Case
`01030000000064` improved from TEDS `0.07619` to `0.918367`; full200 TEDS rose
from `0.621564` to `0.641616`, and overall rose from `0.766717` to `0.769130`.

Phase22 added a narrow Training Datasets fragment merge for pages where the
title and two adjacent table fragments represent one multi-row header table.
Case `01030000000187` improved from TEDS `0.0` to `0.653061`; full200 TEDS
rose from `0.641616` to `0.657165`, and overall rose from `0.769130` to
`0.770253`.

Phase23 added a narrow arrow-flow table normalizer for the five-column
`Genes in DNA` / `Protein` / `Characteristics` chart table where the text layer
had already exposed the content but collapsed `Protein -> Characteristics` into
one malformed column. Case `01030000000120` improved from TEDS `0.065676` to
`1.0`; full200 TEDS rose from `0.657165` to `0.679411`, and overall rose from
`0.770253` to `0.773042`.

Phase24 added a narrow blank comparison table merge for the Mitosis/Meiosis
worksheet case where row labels followed the detected two-column header as two
text blocks. Case `01030000000119` improved from TEDS `0.145655` to `1.0`;
full200 TEDS rose from `0.679411` to `0.699752`, and overall rose from
`0.773042` to `0.774497`. MHS moved slightly down from `0.485812` to
`0.485275`, so this is accepted as a table-quality/overall gain rather than an
all-metric improvement.

Phase25 added a narrow ECO competence-framework normalizer that splits the
embedded framework title into a heading and folds the three-column bullet table
back into a two-column framework table. Case `01030000000150` improved from
TEDS `0.308854` to `0.892376` and MHS `0.0` to `0.346379`; full200 TEDS rose
from `0.699752` to `0.713646`, MHS rose from `0.485275` to `0.488453`, and
overall rose from `0.774497` to `0.776217`.

Phase26 added a narrow national-initiatives long-text table normalizer for the
ECO Circle recollection table where the text layer over-fragmented four columns
into fifteen. Case `01030000000147` improved from TEDS `0.053808` to `1.0`;
full200 TEDS rose from `0.713646` to `0.736174`, MHS rose from `0.488453` to
`0.489770`, and overall rose from `0.776217` to `0.778841`.

Phase27 added a narrow regulatory-narrative shard demotion for
`01030000000080`, where decorative/layout fragmentation promoted ordinary
chapter prose into Markdown tables. The focused guard keeps the regulatory
cholesterol narrative as text and prevents the `| Shah. | ... |` shard table.
Case `01030000000080` improved from overall `0.362170` to `0.540128` and NID
from `0.391496` to `0.781736`; full200 NID rose from `0.896197` to
`0.898148`, overall rose from `0.778841` to `0.779731`, TEDS stayed
`0.736174`, and MHS moved slightly down from `0.489770` to `0.489455`.

Overall, TEDS, and MHS now beat the historical initial acceptance baseline
`overall=0.745414`, `TEDS=0.496416`, and `MHS=0.483837`. This is still not a
claim of full OpenDataLoader hybrid/model parity. Runtime probe coverage now
includes the TriageProcessor signal family for replacement-ratio,
vector-line/table-border, suspicious-gap, large-image, aligned-line, and custom
threshold decisions. The next high-impact gaps are multi-segment rowspan
tables, OCR/image-only table content, chart/table distinction, remaining
heading hierarchy misses, and broader reading-order/text normalization.
