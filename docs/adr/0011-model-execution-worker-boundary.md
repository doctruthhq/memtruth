# ADR 0011: Model Execution Worker Boundary

Status: accepted

## Context

DocTruth v1 is moving parser ownership into `doctruth-runtime`, but the heavy
model stack is heterogeneous:

```text
RT-DETR/TATR       ONNXRuntime artifacts and tensor decoders
SLANeXT/PaddleOCR  PaddleOCR plus PaddlePaddle runtime
RapidOCR           RapidOCR plus ONNXRuntime or MNN backends
```

Bundling all of these directly into the Rust process would make the local
runtime larger, harder to install, harder to license-audit, and less portable.
It would also force every user to carry OCR/table/layout dependencies even when
they only need text-layer evidence.

## Decision

For DocTruth v1, Rust core means:

```text
doctruth-runtime owns parser orchestration
doctruth-runtime owns model manifest/cache validation
doctruth-runtime owns source hash and request envelope construction
doctruth-runtime owns worker response validation and normalization
doctruth-runtime owns benchmark_corpus execution
doctruth-runtime owns audit-grade warning propagation
heavy model execution may happen in isolated local workers
```

The model workers remain local, explicit, auditable processes connected through
JSON stdin/stdout:

```text
scripts/doctruth-onnx-model-worker
scripts/doctruth-slanext-table-worker
scripts/doctruth-rapidocr-mnn-worker
```

The Rust runtime must treat workers as implementation details behind its
control plane. A successful model-assisted parse must still return a normalized
`TrustDocument` with:

```text
parserRun.backend = rust-sidecar+model-worker
parserRun.workerBackend = original worker backend
parserRun.runtime = doctruth-runtime
parserRun.models = required model identities
```

## Consequences

- The CLI can be Rust-first without bundling PaddleOCR, PaddlePaddle,
  RapidOCR, MNN, or ONNXRuntime into the Rust binary.
- Heavy dependencies stay opt-in and can be installed in isolated Python
  environments.
- Release packages can include worker adapters and skip-safe smoke scripts
  without redistributing third-party model weights.
- In-process Rust model execution remains a future optimization, not a v1
  correctness requirement.
- Parser accuracy still requires labeled corpus evidence. Passing generated
  real-route smokes proves integration, not production accuracy.

## Verification

The accepted worker boundary is covered by:

```text
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml
DOCTRUTH_RUNTIME_REAL_MODEL_ARTIFACTS=1 sh scripts/smoke-doctruth-runtime-real-model-artifacts.sh
DOCTRUTH_RUNTIME_REAL_OCR_CORPUS_SMOKE=1 sh scripts/smoke-doctruth-runtime-real-ocr-corpus.sh
DOCTRUTH_RUNTIME_REAL_SLANEXT_SMOKE=1 sh scripts/smoke-doctruth-runtime-real-slanext-artifact.sh
```

These tests and smokes prove the Rust runtime controls the model-assisted parse
route and normalizes worker output. They do not replace broad human-reviewed
parser accuracy corpora.
