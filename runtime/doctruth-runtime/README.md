# doctruth-runtime

`doctruth-runtime` is the local sidecar boundary for DocTruth's Rust parser
core. The Java SDK remains the stable public integration surface; this binary
speaks a small stdin/stdout protocol used by `SidecarParserBackend`.

Current status:

```text
implemented:
  --doctor
  parse_pdf protocol request/response
  benchmark_corpus protocol request/response
  verify_benchmark_report protocol request/response
  text-layer PDF extraction with page, line, bbox, and table evidence
  layered TrustDocument outputs for audit, LLM, source-map, and review flows
  model-worker request handoff for model-assisted presets
  real-route smoke coverage for runtime, corpus, OCR, table, and model-worker paths
  stable JSON error responses

current limits:
  Rust is not the unconditional default for every entry point
  Java/PDFBox remains an explicit fallback and compatibility oracle
  heavy model execution is external-worker and opt-in, not in-process Rust
  real-route smokes prove integration, not broad production parser accuracy
  broad human-reviewed layout, table, and OCR corpora are still required
```

Run tests:

```bash
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml
```

Run the local smoke:

```bash
sh scripts/smoke-doctruth-runtime.sh
```

Run focused runtime smokes when changing the corresponding route:

```bash
sh scripts/smoke-doctruth-runtime-benchmark-corpus.sh
sh scripts/smoke-doctruth-runtime-model-worker.sh
```

Protocol request:

```json
{
  "command": "parse_pdf",
  "source_path": "document.pdf",
  "source_hash": "sha256:...",
  "preset": "lite",
  "offline_mode": true,
  "allow_model_downloads": false
}
```

The runtime can parse text-layer PDFs into evidence-bearing `TrustDocument`
JSON with page, line, bbox, table, parser-run, warning, and layered-output
metadata. It should still be described as a Rust-controlled local runtime, not
as proof that every parser path is Rust-only or that model execution is bundled
inside the binary.

Model-assisted presets can be routed through a configured local worker via
`DOCTRUTH_RUNTIME_MODEL_COMMAND` or `DOCTRUTH_MODEL_COMMAND`. Those workers are
local JSON stdin/stdout processes; they are not bundled model execution inside
the Rust binary.
