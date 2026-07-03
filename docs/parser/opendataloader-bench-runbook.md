# OpenDataLoader Java Core Bench Runbook

`scripts/run-opendataloader-java-core-parity.sh` is the local gate for the
Java/OpenDataLoader-compatible parser core running behind the Rust benchmark
shell. It reuses `scripts/run-doctruth-opendataloader-bench.sh`, which sends one
`opendataloader_prediction` request to `doctruth-runtime`.

## Smoke Gate

Run:

```bash
bash scripts/run-opendataloader-java-core-parity.sh --smoke
```

The script builds the Java CLI jar once, builds the Rust runtime once, then runs
one smoke prediction over a temporary OpenDataLoader Bench view containing the
selected PDFs and ground-truth Markdown. This keeps one warm
`opendataloader-java-core` backend process for the smoke prediction instead of
looping over PDFs.

Smoke artifacts are written under:

```text
third_party/opendataloader-bench/prediction/doctruth-java-core-<timestamp>/smoke/
```

The selected smoke set is recorded in `smoke-docs.tsv` beside the smoke output:

| Fixture | Coverage |
| --- | --- |
| `01030000000001` | simple single column |
| `01030000000145` | two-column |
| `01030000000160` | sidebar/sidebar-like layout |
| `01030000000083` | bordered table |
| `01030000000127` | borderless table |
| `01030000000165` | scanned/OCR fixture, only when local MNN OCR artifacts exist |

The wrapper includes the scanned/OCR fixture when local MNN OCR artifacts exist,
and the same smoke prediction still keeps one warm Java backend process. The
current Java-core backend treats the preset as parser metadata and does not
route scanned or sparse visual pages to the OCR model yet; OCR routing remains a
focused model-runtime gate until Java-core OCR worker integration lands. If the
fixture fails in this smoke, the smoke fails closed. That is intentional
capability exposure. If the local MNN OCR manifest/cache are absent, the OCR
fixture is skipped and `smoke-ocr-skip.txt` records the reason. The smoke gate
still fails closed for any parsed/failed mismatch or invalid evaluation metrics.

## Full200 Gate

Run:

```bash
bash scripts/run-opendataloader-java-core-parity.sh --full200
```

`--full200` always runs smoke first. If smoke fails, the shell exits before the
full200 run starts. If smoke passes, full200 artifacts are written under:

```text
third_party/opendataloader-bench/prediction/doctruth-java-core-<timestamp>/full200/
```

Do not run full200 as routine implementation verification. Use it for release
gates or explicit benchmark acceptance work.

## Report Fields To Check

The benchmark output is split across the runner artifacts:

| Field | Artifact |
| --- | --- |
| overall, NID, TEDS, MHS | `evaluation.json` at `metrics.score.overall_mean`, `nid_mean`, `teds_mean`, `mhs_mean` |
| parsed and failed counts | `summary.json` at `parsed_count`, `failed_count`, `document_count` |
| elapsed and mean ms/doc | `summary.json` at `total_elapsed`, `elapsed_per_doc` |
| Java backend startup | `summary.json` at `javaBackendStartupMs` |
| Java startup/RSS, Rust RSS, model worker RSS | `resources.json` when the runtime resource reporter emits it |
| low-score buckets | `low-score-buckets.json` generated next to `evaluation.json` |
| worst deltas | `reference-comparison.json` at `top_losses` |
| bucket counts | `reference-comparison.json` at `summary.failure_buckets` |

Full200 acceptance should inspect all fields together. Quality metrics without
resource data are not enough for production-profile acceptance; resource data
without OpenDataLoader metrics is not parser parity evidence.

## Current Limitation

The runtime `opendataloader_prediction` command currently accepts `doc_id`,
`limit`, or an unbounded full-corpus request. It does not accept an arbitrary
doc-id list or per-document presets. The smoke gate therefore creates a
temporary bench directory with only the chosen smoke PDFs and ground-truth
Markdown, then invokes the existing runner once over that selected corpus. This
preserves the warm Java backend behavior for the actual smoke prediction while
avoiding a per-document runner loop.

Because one prediction invocation has one preset, the wrapper keeps the existing
`lite` default for the selected smoke corpus. When local OCR artifacts are
installed, the scanned/OCR fixture is included in that same prediction run based
only on artifact availability. The current Java-core backend records the preset
but still parses with the Java parser path and `OcrEngine.NOOP`, so this smoke
does not claim OCR model routing. Explicit preset overrides still use one preset
for the whole smoke corpus.
