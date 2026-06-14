# CLI

DocTruth CLI is the try/debug/inspect entry point for the Rust-core document
evidence runtime. The Java SDK and CLI are wrappers; parser ownership lives in
`runtime/doctruth-runtime`.

Build the standalone CLI jar:

```bash
mvn package -DskipTests
```

Run it:

```bash
java -jar target/doctruth-java-0.2.0-alpha-all.jar --help
```

Install a local launcher:

```bash
scripts/install-cli.sh --prefix "$HOME/.local"
export PATH="$HOME/.local/bin:$PATH"
doctruth version
```

See [Install DocTruth CLI](install.md) for the install path.

## Commands

### Initialize

```bash
doctruth init
```

Creates:

```text
doctruth.yml
schemas/
.doctruth/runs/
```

`doctruth.yml` stores defaults for provider, model, and output directory.

### Parse

No provider key required:

```bash
doctruth parse contract.pdf
```

Prints a summary:

```text
contract.pdf
pages: 3
sections: 42
text: 38
tables: 2
figures: 0
bbox coverage: 31/38
```

By default the CLI uses `--backend auto`, which requires the local Rust runtime.
Installed release launchers set `DOCTRUTH_RUNTIME_COMMAND` automatically. Direct
jar usage must set `DOCTRUTH_RUNTIME_COMMAND` or pass `--runtime <path>`.
Missing Rust runtime is an installation/configuration error, not a Java/PDFBox
fallback. Use `--backend pdfbox` only for legacy/oracle comparison.

```bash
doctruth parse contract.pdf --format json
DOCTRUTH_RUNTIME_COMMAND=./doctruth-runtime doctruth parse contract.pdf --format json
doctruth parse contract.pdf --backend pdfbox --format json
```

Write parsed sections as JSON:

```bash
doctruth parse contract.pdf --json -o parsed.json
```

Write a clean plain-text consumption view for LLM/RAG cleanup steps:

```bash
doctruth parse contract.pdf --format plain -o parsed.txt
```

Plain text keeps the parser reading order and table row/column content, but
does not include Markdown table separators, evidence anchors, bbox metadata, or
hashes. Use JSON or Markdown plus `--source-map` when the downstream consumer
needs audit-grade evidence links.

Write compact evidence wire output for LLM/RAG context:

```bash
doctruth parse contract.pdf --format compact -o context.doctruth-wire
```

Compact output keeps document id, source hash, unit ids, evidence span ids,
table ids, warnings, and optional `bbox=` metadata for citeable units while
remaining materially smaller than full JSON. When `--out` is used, compact
output is written through the streaming writer path rather than first rendering
the full wire document into one aggregate string.

Compact output can also emit a source-map sidecar:

```bash
doctruth parse contract.pdf --format compact --source-map -o context.doctruth-wire
```

The compact source map records rendered offsets for compact unit text fields,
so LLM/RAG context can be tied back to unit ids and evidence span ids.

Verify that a rendered Markdown file still matches its source-map sidecar:

```bash
doctruth verify-source-map parsed.md parsed.doctruth-map.json --source contract.pdf
```

This recomputes the rendered content hash and, when `--source` is supplied, the
source document hash. It fails if the Markdown or source document has been
changed after the source map was generated.

Write a hashable audit package for compliance/replay systems:

```bash
doctruth parse contract.pdf --format audit -o audit.json
```

Verify the audit package against the canonical full TrustDocument JSON:

```bash
doctruth parse contract.pdf --format json --profile full -o trust-document.json
doctruth verify-audit trust-document.json audit.json
```

Audit JSON includes the source hash, canonical `TrustDocument` hash, evidence
hash, parser run metadata, audit-grade status, and evidence units. It is
hashable and replay-friendly. `verify-audit` fails if the audit package no
longer matches the canonical document, source hash, canonical hash, evidence
hash, parser run metadata, or evidence payload. It is not yet an externally
signed or timestamped audit package.

Write a page-aware HTML review surface for bbox overlays:

```bash
doctruth parse contract.pdf --format html -o review.html
```

HTML review output includes page containers with page number, dimensions,
text-layer availability, page image hash, nested unit/table/cell anchors, and
page-scoped bbox overlay nodes for units, tables, and cells. It is intended for
local evidence review and overlay tooling, not as a full
hosted auditor UI.

Write a local review package for visual parser QA:

```bash
doctruth review-package contract.pdf -o .doctruth/reviews/contract
```

The package includes `review.html`, `trust-document.json`, page PNG artifacts,
and `page-images.json`. Phase 250 also writes layered trace artifacts:
`content_blocks.json`, `parse_trace.json`, `layout-debug.html`, and
`span-debug.html`. `content_blocks.json` is the flat reading-order block stream.
`parse_trace.json` is the page/block/line/span evidence layer. The two debug
HTML files carry `data-trace-block-id`, `data-trace-line-id`, and
`data-trace-span-id` attributes whose ids match the corresponding entries in
`parse_trace.json`, so reviewers can inspect layout and span overlays against
the same trace ids used by the machine-readable trace.

This closes the review-package visual trace artifact contract. It does not
claim that Rust-native real model/OCR execution or the broad human-reviewed
parser accuracy corpus are complete; those remain pending.

Show that bbox recovery is enabled in the summary:

```bash
doctruth parse contract.pdf --bboxes
```

### Ingest Audit

Run a no-LLM PDF corpus audit before extraction:

```bash
doctruth ingest-audit ./resumes --json -o ingest-audit.json
```

This walks local PDFs and reports parser-layer gaps only: pages that should be
routed to OCR before DocTruth block assembly, oversized blocks, missing headings,
missing text bboxes, and parse failures. It does not call providers or OCR
engines and does not include recovered document text in the JSON.

### Benchmark Corpus

Run a labeled parser benchmark corpus with metric thresholds:

```bash
doctruth benchmark-corpus parser-corpus.json
doctruth benchmark-corpus parser-corpus.json --json
doctruth benchmark-corpus parser-corpus.json --json --report-out parser-report.json
doctruth benchmark-corpus parser-corpus.json --offline
doctruth verify-benchmark-report parser-report.json
```

The corpus manifest resolves paths relative to itself and requires each case to
provide:

```text
source
or sourceUrl + sourceSha256
expectedMarkdown
expectedDocument
```

Use `--report-out <report.json>` for recorded parser-quality runs. The report is
the machine-readable benchmark result plus `reportFormat` and the resolved
manifest path with `manifestSha256`. It also copies the `minimums` and
`maximums` thresholds used for the run and records actual `caseCount` plus
`casesPerTag` coverage from the cases that ran. Per-case entries include label
id, coverage tags, metrics, and `sourceSha256` when the manifest pins the source
PDF, so parser-accuracy evidence can be archived instead of relying on terminal
output.

Use `verify-benchmark-report <report.json>` to verify a recorded report without
rerunning the parser. The verifier checks the report format, pass status,
manifest path, `manifestSha256`, copied threshold objects, actual
`caseCount`/`casesPerTag`, copied coverage thresholds such as
`minCasesPerTag`/`minTotalCases`, metric values against `minimums`/`maximums`,
aggregate metrics recomputed from case-level metric evidence, and source-hash
pins echoed from the manifest.

Use top-level `minimums` for higher-is-better metrics such as
`reading_order_f1`, `quote_anchor_accuracy`, `bbox_iou`, and `table_cell_f1`.
Use top-level `maximums` for lower-is-better metrics such as
`strict_warning_false_negative_rate` and aggregate runtime gates such as
`parser_latency_p95`.

`--json` emits corpus-level aggregate metrics under top-level `metrics`,
including `parser_latency_p50` and `parser_latency_p95`, and per-case metrics
under each case.

Use `--offline` to require cache-only execution for remote `sourceUrl` cases.
Uncached remote fixtures fail before any network request; previously cached
fixtures are still verified by `sourceSha256` before parsing.

`source` is a manifest-relative local path. `sourceUrl` downloads a remote
fixture into `.doctruth-corpus-cache` next to the manifest and requires
`sourceSha256` in `sha256:<hex>` form before parsing. `expectedDocument` is the
lossless `TrustDocument` JSON label. The command reuses the SDK benchmark
metrics and exits non-zero when any configured minimum threshold fails.

### Local OCR

`doctruth parse` keeps normal text-layer PDFs on the PDFBox path. For low-text
PDF pages, the CLI tries a local OCR worker before DocTruth block assembly. The
worker protocol is JSON over stdin/stdout. The source install and release
tarball include `doctruth-rapidocr-mnn-worker`, a thin Python adapter around
RapidOCR that keeps OCR outside the Java jar.

For v1 `TrustDocument` outputs, use the OCR preset explicitly:

```bash
doctruth parse scanned.pdf --format json --preset ocr -o scanned.trust.json
doctruth review-package scanned.pdf --preset ocr -o .doctruth/reviews/scanned
```

Those commands emit `parserRun.backend=pdfbox+ocr`, include
`rapidocr-mnn:local` in parser models, and mark recovered text units as
`OCR_REGION`. OCR page confidence is copied into the unit evidence. If the
worker returns confidence below `0.85`, the unit receives a severe
`ocr_low_confidence` warning and the document is `NOT_AUDIT_GRADE`; the text is
still present for review and replay.

Discovery order:

```bash
DOCTRUTH_OCR_COMMAND=/path/to/doctruth-rapidocr-mnn-worker
doctruth-rapidocr-mnn-worker on PATH
tradebot-ocr-worker-rs on PATH
tradebot-ocr-worker on PATH
DOCTRUTH_OCR_ENGINE=mnn
DOCTRUTH_OCR_FALLBACK_ENGINE=onnxruntime
DOCTRUTH_OCR_TIMEOUT_MS=30000
```

The same values can be supplied as JVM properties, for example
`-Ddoctruth.ocr.command=/path/to/doctruth-rapidocr-mnn-worker`.

The worker `--doctor` command can also verify strict MNN backend readiness:

```bash
DOCTRUTH_RAPIDOCR_BACKEND=mnn doctruth-rapidocr-mnn-worker --doctor
```

In that mode the worker reports `backend=mnn`, `backendReady`, and
`backendVersion`. RapidOCR import success alone is not treated as proof that the
MNN backend is available.

The adapter expects Python to be able to import `rapidocr`. The raw `rapidocr`
CLI is not treated as a worker unless it is wrapped behind DocTruth's JSON
stdin/stdout protocol. Package OCR model files with the client runtime or local
Python environment; they are not bundled in the generic Java jar.

For a Rust MNN worker, package these model files with the client runtime:

```bash
TRADEBOT_OCR_DET_MODEL=/path/to/ocr/det_model.mnn
TRADEBOT_OCR_REC_MODEL=/path/to/ocr/rec_model.mnn
TRADEBOT_OCR_KEYS_PATH=/path/to/ocr/ppocr_keys.txt
```

### Local ONNX Model Worker

The source install and release tarball also include
`doctruth-onnx-model-worker`, a JSON stdin/stdout adapter for local
ONNXRuntime parser-model experiments. The executable is a small shim over the
packaged `doctruth_onnx_worker_lib.py` support module, and both files must be
present in the same `bin/` directory:

```bash
doctruth-onnx-model-worker --doctor
```

When used through `DOCTRUTH_MODEL_COMMAND` or
`-Ddoctruth.model.command=...`, the worker expects `cache warm`/doctor-verified
ONNX artifacts with `backend: "onnxruntime"` and `format: "onnx"`. The current
adapter proves local ONNXRuntime loading and one inference pass over a cached
artifact. For `task: "table-structure-recognition"`, it also decodes
TATR/DETR-like `pred_logits` and `pred_boxes` outputs into `TrustTable` and
`TABLE_CELL` units. Table detections below `0.85` emit a severe
`table_structure_low_confidence` parser warning and make the returned document
`NOT_AUDIT_GRADE` while preserving the table for review/replay. For
`task: "layout-detection"`, it decodes RT-DETR/
DETR-like `pred_logits` and `pred_boxes` outputs into bbox-bearing layout
`TEXT_BLOCK` units sorted by reading order. These are local decoder contracts,
not a claim that bundled RT-DETR/TATR/SLANeXT production weights are available.
Layout detections below `0.85` emit a severe `layout_low_confidence` unit
warning and make the returned document `NOT_AUDIT_GRADE` while preserving the
region for review/replay.

Direct worker calls include a `metrics` object with `wallMs`,
`inferenceWallMs`, `rssMb`, and `peakMemoryMb`. The Java parser currently uses
the returned `document`; the metrics are available for smoke tests and worker
diagnostics.

To validate a user-supplied real model artifact against the same runtime path,
write a model manifest with `source`, `sha256`, `task`, `backend:
"onnxruntime"`, and `format: "onnx"`, then run the opt-in smoke:

```bash
DOCTRUTH_REAL_MODEL_MANIFEST=models.json \
DOCTRUTH_REAL_MODEL_PRESET=table-lite \
DOCTRUTH_REAL_MODEL_EXPECTED_ID=tatr:v1 \
DOCTRUTH_REAL_MODEL_EXPECTED_TASK=table-structure-recognition \
scripts/smoke-doctruth-real-model-artifact.sh
```

The smoke skips when `DOCTRUTH_REAL_MODEL_MANIFEST` is not set. When it is set,
it warms the local cache, checks `doctruth-onnx-model-worker --doctor`, runs
`doctruth parse` with the configured model worker, and asserts that the returned
`TrustDocument` came from `rust-sidecar+model-worker`, with worker-level
provenance preserved separately when present. This is the acceptance harness
for real RT-DETR/TATR/SLANeXT-compatible artifacts; the repository still does
not bundle those model weights.

### Schema

Check a JSON Schema:

```bash
doctruth schema contract.schema.json
```

Machine-readable summary:

```bash
doctruth schema contract.schema.json --json
```

### Extract

Default extraction:

```bash
doctruth extract contract.pdf -s contract.schema.json
```

By default, DocTruth:

- reads provider/model/output defaults from `doctruth.yml` when present
- uses `openai` as the default provider
- requires citations for top-level schema fields
- writes `result.json` and `audit.json` to `.doctruth/runs/<run-id>/`

Common overrides:

```bash
doctruth extract contract.pdf -s contract.schema.json -o out/
doctruth extract contract.pdf -s contract.schema.json --provider anthropic
doctruth extract contract.pdf -s contract.schema.json --model gpt-4o-mini
doctruth extract contract.pdf -s contract.schema.json --base-url http://localhost:11434/v1
doctruth extract contract.pdf -s contract.schema.json --allow-uncited
doctruth extract contract.pdf -s contract.schema.json --require partyA,totalValue
```

Provider keys:

| Provider | Env var |
| --- | --- |
| `openai` | `OPENAI_API_KEY` |
| `anthropic` | `ANTHROPIC_API_KEY` |
| `gemini` | `GOOGLE_API_KEY` |
| `deepseek` | `DEEPSEEK_API_KEY` |

### Audit

Read an audit JSON file:

```bash
doctruth audit .doctruth/runs/run_abc/audit.json
```

Machine-readable summary:

```bash
doctruth audit .doctruth/runs/run_abc/audit.json --json
```

### Doctor

Check the local runtime, project config, output directory, and provider-key
readiness:

```bash
doctruth doctor
doctruth doctor --json
```

`doctor` does not call an LLM. It is safe to run before configuring extraction.
It also reports local OCR worker readiness: resolved worker command, `mnn`
engine setting, fallback engine, timeout, and whether OCR is disabled. This is
an executable/protocol readiness check; a raw `rapidocr` command is not assumed
to be a compatible worker unless it is wrapped behind DocTruth's JSON
stdin/stdout worker protocol.

### Cache Warm

Warm a local parser model cache from a manifest before using a model-assisted
preset:

```bash
doctruth cache warm models.json --preset table-lite --cache .doctruth/models --json
```

The manifest is keyed by parser preset id and can reference local files,
`file://` URLs, or HTTP(S) URLs:

```json
{
  "presets": {
    "table-lite": [
      {
        "name": "slanet-plus",
        "version": "local",
        "source": "models/slanet.onnx",
        "sha256": "sha256:...",
        "sizeBytes": 123456,
        "required": true,
        "task": "table-structure",
        "backend": "onnxruntime",
        "format": "onnx",
        "precision": "int8",
        "license": "apache-2.0"
      }
    ]
  }
}
```

`cache warm` copies local sources or downloads HTTP(S) sources into the
standard DocTruth cache filename for that model, then verifies SHA-256 through
the same model-cache verifier used by MCP and model-worker requests.
`--offline` refuses remote sources before any network request. Runtime hint
fields are preserved in `cache warm --json`, `doctor --json`, and the local
model-worker request; they describe how a real worker should load the artifact,
but do not make DocTruth execute ONNX by themselves.

### MCP

Run a local stdio MCP server for agent-side document evidence access:

```bash
doctruth mcp
```

The bundled skill package can write a local MCP config snippet:

```bash
skills/doctruth/scripts/bootstrap-local-mcp.sh --command doctruth --print-json
```

Supported tools:

```text
doctruth.parse_document
doctruth.get_layout_regions
doctruth.get_table_cells
doctruth.get_evidence_span
doctruth.verify_citation
doctruth.warm_model_cache
```

`doctruth.parse_document` accepts a local `path`, optional `preset`, optional
`format` (`compact_llm`, `json_evidence`, or `json_full`), and optional
`sourceMap`. The tool returns MCP `structuredContent` with compact LLM text,
JSON evidence units, bbox-bearing unit locations, and a source map when
requested. This is a local stdio gateway over the same parser contracts used by
the CLI and SDK.

The evidence tools all accept a local `path` and optional `preset`.
`doctruth.get_layout_regions` returns citeable units with page, reading order,
evidence span ids, text, and bbox anchors. `doctruth.get_table_cells` returns
structured tables and cell-level bboxes. `doctruth.get_evidence_span` returns
the unit backing a requested `evidenceSpanId`. `doctruth.verify_citation`
checks a caller-supplied `quote` against an `evidenceSpanId` and returns a
boolean verification plus match score. `doctruth.warm_model_cache` verifies a
caller-supplied local model cache directory and expected model descriptors
before model-assisted parsing; it reports READY/MISSING/SHA_MISMATCH without
downloading models.

### Completion

Generate shell completion:

```bash
doctruth completion bash > ~/.local/share/bash-completion/completions/doctruth
doctruth completion zsh > "${fpath[1]}/_doctruth"
doctruth completion fish > ~/.config/fish/completions/doctruth.fish
```

### Version

```bash
doctruth version
doctruth --version
```

## Advanced: Pydantic Schema Migration

This is not the primary path. Use it only when a team already owns Pydantic v2
models and wants to export JSON Schema at build time.

Export a Pydantic v2 model to JSON Schema:

```bash
doctruth migrate pydantic myapp.schemas:Resume -o resume.schema.json --check
```

This command may invoke Python during migration. Runtime Java extraction only
uses the exported schema file.

## Exit Codes

| Code | Meaning |
| --- | --- |
| `0` | Command succeeded |
| `1` | Runtime failure, parse failure, provider failure, or schema compatibility failure |
| `2` | Invalid CLI usage |
