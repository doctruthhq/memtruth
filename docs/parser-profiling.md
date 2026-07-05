# Parser Profiling

DocTruth profiles parser backends before proposing Rust, Go, or native
optimization work. The default PDF backend is OpenDataLoader.

Run a local profile:

```bash
doctruth profile contract.pdf --iterations 3 --json
```

Include JSON rendering cost when you are profiling parser plus output overhead:

```bash
doctruth profile contract.pdf --iterations 3 --include-output trust-json --json
```

Measure the streaming file-writer path used by `doctruth parse -o`:

```bash
doctruth profile contract.pdf --iterations 3 --include-output trust-json-file --json
```

Compare the legacy PDFBox backend on the same file:

```bash
doctruth profile contract.pdf --parser pdfbox --iterations 3 --json
```

The command reports:

```text
parser
iterations
fileSizeBytes
sectionCount
includeOutput
parseLatencyMillis
outputLatencyMillis
coldLatencyMillis
warmAverageLatencyMillis
coldOutputLatencyMillis
warmAverageOutputLatencyMillis
profiledOutputChars
profiledOutputBytes
heapUsedBeforeBytes
heapUsedAfterBytes
heapDeltaBytes
```

`includeOutput` defaults to `parser-only`. Use `trust-json` for DocTruth's
audit-ready TrustDocument JSON or `parsed-json` for the compatibility parser
debug format. Use `trust-json-file` or `parsed-json-file` when profiling the
streaming writer path instead of string materialization.

`warmAverageLatencyMillis` means subsequent full parses in the same JVM. It is
not a persistent parser-worker or model-residency measurement; the OpenDataLoader
backend still tears down its static runtime after each parse.

Treat these numbers as evidence for the named machine, corpus, parser backend,
DocTruth version, and command. They are not global product thresholds. Native
work should target a measured bottleneck while preserving the DocTruth
TrustDocument JSON contract.
