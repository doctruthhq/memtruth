# OpenDataLoader Benchmark Gates

DocTruth can write OpenDataLoader Bench-compatible prediction artifacts through
the Rust runtime `opendataloader_prediction` command. The command is intentionally
bounded by default.

## Full200 Guard

`opendataloader_prediction` must not run every PDF in the OpenDataLoader Bench
corpus unless the request explicitly allows it.

When a request has neither `doc_id` nor `limit`, the runtime rejects the request
unless `allow_full200` is set to `true`:

`scripts/run-doctruth-opendataloader-bench.sh` is the intentional benchmark
runner. Its default mode has neither `--doc-id` nor `--limit`, so the script
injects `allow_full200: true` for that default full200 request. Bounded script
runs keep omitting `allow_full200`.

```json
{
  "command": "opendataloader_prediction",
  "bench_dir": "third_party/opendataloader-bench",
  "output_dir": "third_party/opendataloader-bench/prediction/doctruth-rust",
  "engine": "doctruth-rust",
  "preset": "edge-fast",
  "profile": "edge-fast"
}
```

The rejection is structured:

```json
{
  "error_code": "FULL200_REQUIRES_EXPLICIT_ALLOW",
  "message": "Set allow_full200=true to run the full OpenDataLoader Bench corpus"
}
```

## Bounded Runs

Single-document requests remain allowed without `allow_full200`:

```json
{
  "command": "opendataloader_prediction",
  "bench_dir": "third_party/opendataloader-bench",
  "output_dir": "target/opendataloader-prediction-one",
  "engine": "doctruth-rust-one",
  "doc_id": "01030000000198",
  "preset": "edge-fast",
  "profile": "edge-fast"
}
```

Small multi-document requests also remain allowed without `allow_full200` when
they set `limit`:

```json
{
  "command": "opendataloader_prediction",
  "bench_dir": "third_party/opendataloader-bench",
  "output_dir": "target/opendataloader-prediction-smoke",
  "engine": "doctruth-rust-smoke",
  "limit": 5,
  "preset": "edge-fast",
  "profile": "edge-fast"
}
```

## Explicit Full200 Run

Task 10 and release-gate style benchmark reports should opt in explicitly:

```json
{
  "command": "opendataloader_prediction",
  "bench_dir": "third_party/opendataloader-bench",
  "output_dir": "third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23",
  "engine": "doctruth-rust-opendataloader-full200-2026-06-23",
  "preset": "edge-fast",
  "profile": "edge-fast",
  "timeout_seconds": 30,
  "allow_full200": true
}
```

## Rationale

The full OpenDataLoader Bench corpus is a quality gate, not a default unit-test
or smoke-test path. Making full200 explicit prevents accidental long local runs,
keeps focused parity tests fast, and leaves a clear audit signal when a benchmark
report intentionally covers the whole corpus.
