# ADR 0011: Model Execution Worker Boundary

Status: accepted

## Context

DocTruth v1 is moving parser ownership into `doctruth-runtime`. The legacy
research stack was heterogeneous:

```text
RT-DETR/TATR       ONNXRuntime artifacts and tensor decoders
SLANeXT/PaddleOCR  PaddleOCR plus PaddlePaddle runtime
RapidOCR           RapidOCR plus ONNXRuntime or MNN backends
```

Bundling all of these Python runtimes directly into the production parser path
made the local runtime larger, harder to install, harder to license-audit, and
less portable. It also forced users to reason about OCR/table/layout
dependencies even when they only needed text-layer evidence.

## Decision

For DocTruth v1, Rust core means:

```text
doctruth-runtime owns parser orchestration
doctruth-runtime owns model manifest/cache validation
doctruth-runtime owns source hash and request envelope construction
doctruth-runtime owns worker response validation and normalization
doctruth-runtime owns benchmark_corpus execution
doctruth-runtime owns audit-grade warning propagation
heavy model execution happens through Rust-owned local workers
```

The production model worker is a local, explicit, auditable Rust process
connected through JSON stdin/stdout:

```text
runtime/doctruth-runtime/src/bin/doctruth-mnn-model-worker.rs
bin/doctruth-mnn-model-worker
```

Legacy Python workers may remain in the source tree only as migration or
differential-oracle tools. They are not installed by the default source install,
are not included in release tarballs, and are not a production parser strategy.

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

- The CLI is Rust-first without bundling PaddleOCR, PaddlePaddle, RapidOCR, or
  ONNXRuntime Python environments into the production package.
- Release packages include the Rust runtime and Rust MNN worker, not Python
  worker adapters.
- Real MNN inference remains behind the Rust worker implementation and model
  manifest/cache contract; replacing the protocol stub with actual MNN calls is
  an implementation task, not a license to reintroduce Python production
  residency.
- Parser accuracy still requires labeled corpus evidence. Passing generated
  real-route smokes proves integration, not production accuracy.

## Verification

The accepted worker boundary is covered by:

```text
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml
sh scripts/smoke-doctruth-runtime-model-worker.sh
```

These tests and smokes prove the Rust runtime controls the model-assisted parse
route and normalizes worker output. They do not replace broad human-reviewed
parser accuracy corpora.
