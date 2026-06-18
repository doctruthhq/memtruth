# OpenDataLoader PDF Reference Snapshot

This directory vendors selected Apache-2.0 source files from:

```text
Repository: https://github.com/opendataloader-project/opendataloader-pdf
Commit: d1845179a1286bbb76f9618e8b6c8f51509a52f4
License: Apache-2.0
```

The files are kept as a local behavior reference for DocTruth's Rust parser
runtime. They are not compiled into DocTruth and they do not define DocTruth's
public schema.

Reference composition rule:

```text
pdf_oxide       = Rust PDF substrate
OpenDataLoader  = geometry, filtering, table, list, heading behavior reference
Kreuzberg       = Rust runtime, model/cache, worker architecture reference
Docling         = unified document model and lossy export reference
MinerU          = layered output product reference
DocTruth        = TrustDocument, evidence, citations, audit gates, replay
```

`TrustDocument` remains the canonical DocTruth output contract.
