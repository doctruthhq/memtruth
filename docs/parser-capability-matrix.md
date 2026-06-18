# Parser Capability Matrix

DocTruth parsing exists to preserve evidence anchors for extraction. It is not a
general document conversion product.

## Runtime Status

`doctruth-runtime` is now an active Rust-controlled runtime, not only a future
placeholder. It owns `parse_pdf`, `benchmark_corpus`,
`verify_benchmark_report`, `--doctor`, model-worker request handoff, layered
`TrustDocument` outputs, and real-route smokes for runtime, corpus, OCR, table,
and model-worker paths.

Rust is the default parser core. Packaged CLI installs wire
`DOCTRUTH_RUNTIME_COMMAND` automatically, and direct SDK/JAR usage must configure
the runtime explicitly. Java/PDFBox is legacy/oracle-only and must be selected
explicitly for migration or differential testing. Heavy layout, table, and OCR
model execution remains local-worker and opt-in; those smokes prove integration
through the real route, not broad production parser accuracy.

| Source | Text Anchor | Visual Anchor | Current Notes |
| --- | --- | --- | --- |
| PDF text | page, line, char offset | optional page-normalized bbox | Best-supported path for reviewer highlights |
| PDF scanned image | OCR adapter via `OcrEngine` SPI | OCR bbox when regions are supplied | Low-text pages route before DocTruth block assembly; CLI auto-discovers local OCR workers when packaged |
| DOCX | paragraph-style logical sections | none | Word pagination is not stable without a renderer |
| XLSX | sheet/row-style logical sections | none | Cell-level bbox is future work |
| CSV | row/column-style logical sections | none | Logical tabular evidence only |
| PDF tables | table/cell source object ids | table/cell page-normalized bbox when detected | Generated bordered-grid, conservative borderless aligned text, horizontal colspan, and vertical rowspan fixtures are covered; model-assisted and real-world labeled table accuracy remain future work |

## Output Profiles

| Profile | Consumer | Evidence contract |
| --- | --- | --- |
| `json_full` | SDKs, audit storage, replay packages | Full trust document with evidence spans, source hashes, warnings, parser run, and audit grade |
| `json_evidence` | audit pipelines that only need evidence-bearing content | Evidence-bearing subset |
| `markdown_clean` | LLM/RAG document consumption | Readable Markdown without inline evidence syntax; pair with a source map when audit lookup is needed |
| `plain_text` | cleanup, keyword search, and simple LLM context | Clean text and tab-separated table rows only; not an audit artifact by itself |
| `compact_llm` | token-efficient LLM/RAG context | Compact deterministic wire format with evidence ids and warnings |
| `html_review` | local evidence review UI | Review anchors suitable for bbox overlays and table/cell inspection |

Rules:

- `SourceLocation` is the durable audit anchor.
- `BoundingBox` is an optional visual anchor for PDF-originated text.
- Absence of bbox does not mean absence of evidence.
- Scanned PDFs should be routed through the Rust model-worker path before
  DocTruth block assembly.
- The CLI discovers the production local model worker via
  `DOCTRUTH_RUNTIME_MODEL_COMMAND`, `DOCTRUTH_MODEL_COMMAND`, or
  `doctruth-mnn-model-worker` on `PATH`. Legacy Python OCR/table/model worker
  names remain source-tree oracle tools only. OCR/table models stay in the
  desktop/deployment package or local model cache, not in the generic Java
  parser jar.
