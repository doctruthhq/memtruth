# OpenDataLoader Bench Import

Source: https://github.com/opendataloader-project/opendataloader-bench

Imported commit: `7af1d8f4d0c09f51ea1a5c6ba5f66e993286d109`

License: Apache-2.0 for the benchmark repository. Dataset notices are preserved
in `THIRD_PARTY_NOTICES.md`; DP-Bench is listed there as MIT.

Purpose in DocTruth:

- External parser-quality benchmark corpus and evaluator reference.
- Ground truth for Markdown-oriented document parsing quality.
- Metrics reference for reading order, table fidelity, heading hierarchy, and
  speed.
- Baseline comparison artifacts for engines such as OpenDataLoader, Docling,
  MinerU, Marker, Unstructured, PyMuPDF4LLM, MarkItDown, and LiteParse.

DocTruth integration boundary:

- This directory is third-party benchmark material, not DocTruth-owned training
  data and not DocTruth's canonical evidence contract.
- `TrustDocument` remains the canonical DocTruth output.
- DocTruth should export predictions into an OpenDataLoader Bench-compatible
  shape, run or consume its evaluator outputs, then import metrics into
  DocTruth benchmark reports under external parser-quality metrics.
- OpenDataLoader Bench answers parser substrate quality. DocTruth still owns
  evidence spans, source maps, replay packages, audit-grade gates, and source
  hash binding.

Do not modify imported files casually. Prefer adding DocTruth adapters outside
this directory unless the change is explicitly a vendored third-party update.
