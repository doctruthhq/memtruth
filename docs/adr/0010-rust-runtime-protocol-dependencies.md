# ADR 0010: Rust Runtime Protocol Dependencies

Status: accepted

## Context

DocTruth v1 introduces a Rust sidecar runtime boundary for the parser core. The
Java SDK talks to this sidecar through a JSON stdin/stdout protocol. The runtime
needs deterministic JSON parsing and rendering, plus process-level contract
tests for the binary.

## Decision

Use these Rust dependencies in `runtime/doctruth-runtime`:

```text
lopdf        direct page content operation parsing for simple bordered-table grids
pdf-extract runtime text-layer PDF extraction for the first non-model baseline
serde_json  runtime JSON protocol parsing and rendering
sha2        stable per-page runtime hash metadata
assert_cmd  dev-only binary contract tests
predicates  dev-only stdout/stderr assertions
```

`lopdf` is declared with `default-features = false`. The runtime only needs
basic PDF object/content-stream parsing here; optional chrono, jiff, rayon, and
time features are not part of the local sidecar baseline.

`sha2` is used only for deterministic local metadata. The runtime does not yet
render page images; the current Rust-side page hash is a stable hash over page
content bytes and media-box dimensions, not a rendered PNG hash.

The MVP intentionally does not add OCR, ONNX, model-assisted table, or Markdown
rendering dependencies. Those will need separate ADRs because they affect
runtime size, licensing, model provenance, and local installation behavior.

## Consequences

- The sidecar protocol is covered by executable-level tests instead of only unit
  tests.
- Runtime output remains standard JSON that the Java `SidecarParserBackend`
  can consume.
- The first Rust parser slice can extract text-layer PDFs but does not imply
  layout/table/OCR quality claims.
- The first Rust table slice can recover simple bordered-grid tables from PDF
  drawing operations, but it does not imply borderless, merged-cell, multi-page,
  OCR-backed, or model-assisted table quality claims.
