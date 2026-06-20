# Python to Rust Parser Parity Checklist

DocTruth production parsing must be Rust-owned. Python paths are legacy oracle,
smoke, or test harness support only. This checklist tracks the remaining
behavior that must move from Python adapters into `runtime/doctruth-runtime`
before OpenDataLoader Bench is used as a final acceptance gate.

## Projection and Markdown

- [x] Rust-owned OpenDataLoader prediction command and evaluator path.
- [x] Content block rendering without duplicated source units.
- [x] Page-number noise filtering.
- [x] Render TrustDocument tables as GFM-compatible HTML tables.
- [x] Match Python heading promotion contract:
  numbered headings, title-case headings, common single-word headings, and
  numeric/table caption exclusions.
- [x] Match Python linewise paragraph projection and optional paragraph-join
  behavior.
- [x] Match Python table-of-contents Markdown rendering for detected table
  outputs.
- [x] Match Python synthetic table reconstruction from ordered text lines.

## Spatial Tables

- [x] Match Python spatial segment boundaries:
  row grouping, weak-row handling, minimum strong rows, and column density.
- [x] Match Python table-likeness gates:
  column count, median cell length, fill ratio, row width, and formula rejection.
- [x] Match Python formula/list/TOC false-positive rejection before emitting
  spatial table HTML.
- [x] Match Python spatial-table output contract:
  consume source units and append recovered table HTML after normal text
  projection unless a later Rust-owned contract replaces that behavior.
- [x] Match Python party-registration table reconstruction.

## Model and Worker Runtime

- [x] Replace default production discovery for OCR/table model routes with the
  Rust MNN worker protocol entrypoint.
- [x] Remove Python RapidOCR, SLANeXT/PaddleOCR, and ONNX worker adapters from
  source install and release packaging.
- [x] Make Rust MNN worker fail closed by default until real MNN inference is
  wired; contract-smoke stub mode is explicit and non-audit-grade.
- [x] Add optional `mnn-native` Rust feature using `mnn-rs` so native MNN
  binding compilation is verified without bloating the default build.
- [x] Add `doctruth-mnn-model-worker --probe-model` and an env-gated native MNN
  smoke for real executable `.mnn` artifacts.
- [x] Implement real MNN OCR inference path inside
  `doctruth-mnn-model-worker` behind the `mnn-ocr` feature.
- [ ] Validate real MNN OCR model pack quality against scanned-PDF fixtures.
- [ ] Implement real MNN table/layout inference inside `doctruth-mnn-model-worker`.
- [x] Replace Python ONNX model worker with Rust/MNN model worker or remove it
  from production packaging.
- [x] Keep Python model workers available only behind oracle/test opt-in if they
  remain in the repository.
- [x] Record model manifest, model SHA, profile, RSS, latency, and unload
  behavior for each accepted edge-model profile.

Native MNN acceptance requires a real executable `.mnn` model. Benchmark-only
or shape-only artifacts with stripped weights are useful for parser plumbing
tests but do not count as inference acceptance.

## Benchmark Boundary

- [x] Default OpenDataLoader runner refuses Python oracle unless explicitly
  opted in.
- [x] OpenDataLoader Bench corpus is vendored under `third_party/`.
- [ ] Full OpenDataLoader Bench acceptance runs only after the Rust contract
  parity items above are covered by tests.
- [ ] Benchmark report must include scores, speed, resource profile, source
  hashes, and remaining quality gaps.
