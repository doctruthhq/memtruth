# OpenDataLoader Full200 Report - 2026-06-23

This report records the current DocTruth Rust `edge-fast` parser quality on the
full OpenDataLoader Bench corpus. It is evidence of the current parser state,
not a parity claim.

## Commands

Prediction:

```bash
printf '%s' '{
  "command": "opendataloader_prediction",
  "bench_dir": "third_party/opendataloader-bench",
  "output_dir": "third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23",
  "engine": "doctruth-rust-opendataloader-full200-2026-06-23",
  "preset": "edge-fast",
  "profile": "edge-fast",
  "allow_full200": true,
  "timeout_seconds": 30
}' | cargo run --manifest-path runtime/doctruth-runtime/Cargo.toml --quiet --bin doctruth-runtime
```

Evaluation:

```bash
printf '%s' '{
  "command": "opendataloader_evaluate_prediction",
  "ground_truth_dir": "third_party/opendataloader-bench/ground-truth/markdown",
  "prediction_dir": "third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23",
  "output_path": "third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23/evaluation.json"
}' | cargo run --manifest-path runtime/doctruth-runtime/Cargo.toml --quiet --bin doctruth-runtime
```

## Artifacts

- DocTruth revision used for run: `c65f0e0`
- Prediction directory:
  `third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23/`
- Prediction summary:
  `third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23/summary.json`
- Evaluation:
  `third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23/evaluation.json`

## Scores

| Metric | Score |
| --- | ---: |
| Overall mean | 0.738756 |
| NID mean | 0.859061 |
| NID-S mean | 0.838722 |
| TEDS mean | 0.475822 |
| TEDS-S mean | 0.534886 |
| MHS mean | 0.469231 |
| MHS-S mean | 0.626041 |

## Coverage And Runtime

| Field | Value |
| --- | ---: |
| Documents | 200 |
| Parsed | 199 |
| Failed | 1 |
| Missing predictions | 0 |
| NID-counted docs | 200 |
| TEDS-counted docs | 42 |
| MHS-counted docs | 109 |
| Total elapsed | 217820.636958 ms |
| Mean per document | 1089.103185 ms |
| Runtime profile | edge-fast |
| Model-required routes | 0 |
| Started model runtimes | 0 |

## Failed Parse

| Case | Error | Interpretation |
| --- | --- | --- |
| 01030000000165 | `PDF_EXTRACTION_FAILED` | Text layer was not extractable; output Markdown is empty. Needs OCR/model route for scanned or image-only pages. |

## Bottom 30 Cases

| Case | Overall | NID | TEDS | MHS | Primary bucket | Next action |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 01030000000165 | 0.000000 | 0.000000 | 0.000000 | 0.000000 | OCR/text-layer | Route image-only pages to OCR instead of emitting empty Markdown. |
| 01030000000141 | 0.003407 | 0.006814 | n/a | 0.000000 | OCR/layout | Preserve brochure text and visual reading order; current output is nearly empty. |
| 01030000000110 | 0.259914 | 0.519828 | 0.000000 | n/a | Table/formula | Recover Reynolds formula and viscosity table structure. |
| 01030000000107 | 0.303476 | 0.373557 | n/a | 0.233394 | Reading order | Improve multi-block reading order and heading hierarchy. |
| 01030000000170 | 0.308225 | 0.616449 | 0.000000 | n/a | Table | Convert conservation-practice table to valid HTML/GFM structure. |
| 01030000000150 | 0.315741 | 0.866902 | 0.000000 | 0.080321 | Table/heading | Preserve table structure and heading levels. |
| 01030000000082 | 0.318828 | 0.624846 | 0.012810 | n/a | Table | Split appendix table text into clean table blocks. |
| 01030000000146 | 0.332638 | 0.901961 | 0.000000 | 0.095954 | Heading/table | Avoid false headings inside framework table-like content. |
| 01030000000149 | 0.336356 | 0.851013 | 0.000000 | 0.158055 | Table/heading | Recover table projection and suppress heading pollution. |
| 01030000000185 | 0.339749 | 0.534851 | n/a | 0.144646 | Reading order | Improve block grouping and flow reconstruction. |
| 01030000000168 | 0.348347 | 0.696694 | n/a | 0.000000 | Heading | Recover heading hierarchy for long educational content. |
| 01030000000163 | 0.349335 | 0.523211 | n/a | 0.175459 | Reading order | Improve dense page line grouping and ordering. |
| 01030000000147 | 0.352919 | 0.866042 | 0.000000 | 0.192714 | Table/heading | Recover table cells and avoid heading-level drift. |
| 01030000000104 | 0.363752 | 0.727503 | n/a | 0.000000 | Heading | Add robust heading-tree reconstruction for this layout family. |
| 01030000000187 | 0.374228 | 0.919607 | 0.000000 | 0.203076 | Table/heading | Improve TEDS for benchmark table pages. |
| 01030000000183 | 0.376541 | 0.588088 | n/a | 0.164993 | Reading order | Improve flow segmentation and heading alignment. |
| 01030000000084 | 0.391948 | 0.701251 | 0.082645 | n/a | Table | Recover appendix table rows and column spans. |
| 01030000000200 | 0.400072 | 0.520773 | 0.489096 | 0.190347 | Mixed | Improve late-corpus mixed table plus heading recovery. |
| 01030000000197 | 0.405490 | 0.914987 | 0.000000 | 0.301483 | Table | Table structure is the primary failure. |
| 01030000000122 | 0.413279 | 0.807601 | 0.000000 | 0.432236 | Table | Recover table HTML/GFM projection. |
| 01030000000199 | 0.437046 | 0.756651 | n/a | 0.117440 | Mixed | Improve block grouping and heading recovery. |
| 01030000000144 | 0.441278 | 0.603798 | n/a | 0.278758 | Mixed | Improve text ordering and hierarchy. |
| 01030000000154 | 0.446360 | 0.892720 | n/a | 0.000000 | Heading | Heading hierarchy is the dominant failure. |
| 01030000000145 | 0.453519 | 0.574843 | n/a | 0.332195 | Reading order | Improve dense layout order and section grouping. |
| 01030000000182 | 0.453656 | 0.894571 | 0.000000 | 0.466396 | Table | Table projection is missing or malformed. |
| 01030000000058 | 0.462278 | 0.924556 | n/a | 0.000000 | Heading | Heading hierarchy is missing. |
| 01030000000157 | 0.478196 | 0.956391 | n/a | 0.000000 | Heading | Heading hierarchy is missing. |
| 01030000000179 | 0.491228 | 0.982456 | n/a | 0.000000 | Heading | Heading hierarchy is missing. |
| 01030000000051 | 0.493500 | 0.725115 | 0.502764 | 0.252621 | Mixed | Table and heading metrics both need improvement. |
| 01030000000133 | 0.494342 | 0.988683 | n/a | 0.000000 | Heading | Heading hierarchy is missing. |

## Interpretation

The run proves that the current Rust `edge-fast` path can process the full
OpenDataLoader corpus without Python, Torch, Docling, or a resident model
runtime. It also shows the current quality ceiling clearly:

- Plain text extraction and many simple layouts are already strong enough to
  keep the overall mean at `0.738756`.
- Table structure is the largest quality gap. Cases with `TEDS = 0` dominate
  the bottom list.
- Heading hierarchy is the second major gap. Several cases have good NID but
  `MHS = 0`.
- OCR/text-layer handling is still required for image-only or non-extractable
  pages; `01030000000165` produced an empty Markdown artifact.

## Next Actions

1. Add an OCR/model route for `PDF_EXTRACTION_FAILED` and empty-text pages.
2. Prioritize table reconstruction for cases with `TEDS = 0`, starting with
   `01030000000110`, `01030000000170`, `01030000000082`, and
   `01030000000146`.
3. Add heading hierarchy recovery tests for the MHS-zero family.
4. Keep this report as the baseline for future OpenDataLoader parity work.
