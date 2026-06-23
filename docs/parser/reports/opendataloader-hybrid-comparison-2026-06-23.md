# OpenDataLoader Hybrid Comparison - 2026-06-23

## Inputs

- Reference: `third_party/opendataloader-bench/prediction/opendataloader-hybrid/evaluation.json`
- Candidate: `third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23/evaluation.json`
- Command: `opendataloader_compare_reports`
- Note: this report compares existing evaluation artifacts only; it does not rerun full200.

## Summary

| Metric | Reference | Candidate | Delta |
| --- | ---: | ---: | ---: |
| Overall | 0.906572 | 0.738756 | -0.167816 |
| NID | 0.933731 | 0.859061 | -0.074670 |
| TEDS | 0.927643 | 0.475822 | -0.451821 |
| MHS | 0.820776 | 0.469231 | -0.351545 |

## Coverage

| Field | Value |
| --- | ---: |
| Compared documents | 200 |
| Reference-only documents | 0 |
| Candidate-only documents | 0 |

Both reports cover the same 200 OpenDataLoader Bench documents. The deltas are
therefore quality gaps, not corpus mismatch artifacts.

## Bottom Regression Cases

| Document | Overall Delta | NID Delta | TEDS Delta | MHS Delta |
| --- | ---: | ---: | ---: | ---: |
| `01030000000165` | -0.844331 | -0.860421 | -1.000000 | -0.672572 |
| `01030000000170` | -0.649175 | -0.300824 | -0.997527 | n/a |
| `01030000000082` | -0.640821 | -0.294452 | -0.987190 | n/a |
| `01030000000110` | -0.619685 | -0.309729 | -0.929641 | n/a |
| `01030000000104` | -0.595566 | -0.237021 | n/a | -0.954112 |
| `01030000000168` | -0.579702 | -0.224557 | n/a | -0.934846 |
| `01030000000185` | -0.570287 | -0.429586 | n/a | -0.710990 |
| `01030000000084` | -0.559201 | -0.201048 | -0.917355 | n/a |
| `01030000000147` | -0.548387 | -0.099680 | -1.000000 | -0.545483 |
| `01030000000163` | -0.544425 | -0.454925 | n/a | -0.633924 |
