# OpenDataLoader Hybrid Rustification TDD Plan

Date: 2026-06-18

Status: superseded for execution by
`docs/plans/2026-06-23-java-core-rust-shell-opendataloader-parity.md`

Owner: DocTruth

## Goal

Make DocTruth practical for edge and local-agent use by turning the proven
OpenDataLoader hybrid quality path into a DocTruth-owned runtime path, then
progressively replacing the Python/Torch-heavy pieces with Rust and MNN-first
lazy model runtime.

Correction: this plan's practical intent was to preserve OpenDataLoader-quality
parsing while Rustifying the expensive Python/Docling/Torch outer runtime. It
must not be read as "replace the Java/PDFBox/OpenDataLoader-compatible parser
quality core with a from-scratch Rust parser before benchmark parity." Current
execution keeps the Java/OpenDataLoader-compatible parser core as the quality
source of truth and makes Rust own the runtime shell, MNN worker boundary,
benchmark runner, resource accounting, and Python replacement path.

This plan supersedes the idea that DocTruth v1 should first become a fully
from-scratch Rust parser. The more practical route is:

```text
OpenDataLoader hybrid quality baseline first
-> DocTruth TrustDocument adapter
-> Rust deterministic local parser parity
-> MNN-first lazy model runtime
-> OpenDataLoader/Docling/Python/Torch as benchmark oracle only
```

The target is not to make OpenDataLoader, Docling, or MinerU schemas canonical.
`TrustDocument` remains canonical. External parser output is input evidence and
quality reference only.

## Current Measured Baseline

The live OpenDataLoader hybrid benchmark was run locally against the vendored
OpenDataLoader Bench corpus.

```text
engine: opendataloader-hybrid 2.2.1
corpus: 200 PDFs
quality:
  overall: 0.9065718466674022
  NID:     0.9337307553293448
  TEDS:    0.9276430534097512
  MHS:     0.8207761855598542
speed:
  parser total: 125.29678010940552s
  parser avg:   0.6264839005470276s/doc
  command wall: 130.33s
resources:
  docling-fast hybrid server RSS: about 1.39GB to 1.51GB
  client/JAR full-run peak RSS: about 408MB
  warm client single-run peak RSS: about 140MB
```

Interpretation:

```text
OpenDataLoader hybrid quality works.
DocTruth parser quality does not yet match it.
The current memory problem is mostly Docling/Torch/model runtime, not Java alone.
```

## Architecture Direction

### Runtime Tiers

DocTruth should expose three parser tiers under one TrustDocument contract.

```text
Tier 0: Rust local deterministic
  PDF substrate, spans, bbox, XY-Cut++, safety filters, table geometry,
  heading/list/section inference.
  Default for local/edge use.

Tier 1: Rust + MNN lazy model runtime
  Layout/table/OCR models loaded on demand.
  ONNX is allowed only as a conversion interchange artifact.
  MNN is the production local model format.
  Target for high-quality local use without Python/Torch server residency.

Tier 2: OpenDataLoader hybrid benchmark oracle
  opendataloader-pdf + docling-fast/Torch.
  Highest current quality reference.
  Not a production fallback path.
  Used for benchmark reproduction, migration comparison, and quality triage.
```

### Reference Composition

```text
OpenDataLoader Bench = objective parser-quality gate
OpenDataLoader PDF   = high-quality hybrid baseline and behavior reference
Docling              = layout/table model quality reference
Kreuzberg            = Rust runtime/model cache/worker architecture reference
MinerU               = layered output product reference
DocTruth             = TrustDocument, sourceRefs, parseTrace, audit, replay
```

No external schema becomes canonical. All outputs normalize into:

```text
TrustDocument
contentBlocks
parseTrace
sourceRefs
audit JSON
replay artifacts
benchmark reports
```

## TDD Rules For This Work

Every implementation slice must follow red-green-refactor.

Required evidence per slice:

```text
1. RED test added first
2. RED failure captured in progress.md
3. minimal implementation
4. GREEN test output captured in progress.md
5. benchmark or smoke delta recorded when applicable
6. no production behavior marked complete without a failing test first
```

Do not claim quality improvement from code review or screenshots. Quality claims
must come from:

```text
OpenDataLoader Bench metrics
DocTruth benchmark-corpus reports
per-case regression fixtures
resource measurements
```

## Phase 1: Live Hybrid Benchmark Oracle Adapter

Goal: make the current quality baseline reproducible from DocTruth benchmark
tooling without turning OpenDataLoader hybrid into a production parser backend
or runtime fallback.

Scope:

```text
- Add a DocTruth benchmark oracle adapter named opendataloader-hybrid.
- Start/reuse a local hybrid server only from benchmark/oracle commands.
- Call opendataloader-pdf hybrid conversion.
- Capture produced Markdown.
- Record backend provenance:
  - opendataloader-pdf version
  - docling version
  - hybrid mode
  - server URL
  - runtime RSS if measurable
  - elapsed time
- Normalize output into TrustDocument.
- Mark evidence grade honestly:
  - Markdown-only mapping is not span-perfect.
  - sourceRefs are coarse until structured/bbox adapter lands.
- Do not expose this as an automatic runtime fallback for production parsing.
```

TDD tests:

```text
RED: benchmark oracle command rejects opendataloader-hybrid when dependency is missing with a clear doctor hint.
RED: benchmark oracle command accepts opendataloader-hybrid and emits TrustDocument with parserRun.backend.
RED: parserRun records externalBackend provenance and elapsedMs.
RED: audit status is NOT_AUDIT_GRADE when only Markdown-level source mapping exists.
RED: benchmark adapter can run one vendored OpenDataLoader PDF through the backend.
RED: production parse profiles cannot auto-select opendataloader-hybrid.
```

Done when:

```text
doctruth benchmark-oracle --engine opendataloader-hybrid <pdf> --json
```

produces a valid TrustDocument and a recorded one-document benchmark smoke.

## Phase 2: OpenDataLoader Structured Output Adapter

Goal: stop treating Markdown as the only output and extract the richest
available OpenDataLoader object structure before rendering.

Scope:

```text
- Investigate opendataloader-pdf public API for structured objects.
- Prefer object/block/table/list/heading output over Markdown parsing.
- Map OpenDataLoader object types into TrustDocument units.
- Preserve table cells, heading levels, lists, reading order, and coarse bbox
  if available.
- Keep Markdown as a lossy export, not the source of truth.
```

TDD tests:

```text
RED: known table PDF maps to TrustDocument TABLE with expected row/column counts.
RED: known heading PDF maps to contentBlocks heading levels without Markdown inference.
RED: list PDF preserves list items as list blocks.
RED: adapter emits source mapping quality = structured when block ids are available.
RED: adapter falls back to Markdown only with explicit warning when structured API is unavailable.
```

Done when:

```text
OpenDataLoader object/block output -> TrustDocument
```

is the default for this backend, with Markdown as a secondary export.

## Phase 3: Rust Deterministic Parity For Non-Model Work

Goal: move the deterministic parts that do not require Docling/Torch into Rust,
using OpenDataLoader behavior as the reference.

Scope:

```text
- PDF substrate and glyph/span extraction through Rust.
- safety filters:
  - whitespace
  - off-page
  - tiny text
  - duplicate text
  - invisible render mode
  - near-white/background-like text
  - hidden OCG when substrate exposes enough data
- tagged-PDF structure-tree preference.
- XY-Cut++ reading order.
- table geometry:
  - bordered tables
  - cluster/borderless tables
  - sparse rows
  - empty-cell preservation
  - continued table detection
- heading/list/section tree.
```

TDD tests:

```text
RED: per-case OpenDataLoader Bench failures become Rust fixtures.
RED: each fixture asserts TrustDocument, not Markdown-only output.
RED: fixture tags cover reading-order, table, heading, safety-filter, source-map.
RED: benchmark report rejects claiming parity without external NID/TEDS/MHS gates.
```

Done when:

```text
Rust local deterministic backend beats current DocTruth pass2 scores materially
and closes a documented subset of OpenDataLoader hybrid failures without model use.
```

The target for this phase is not full hybrid parity. It is to avoid model
startup for ordinary text-layer PDFs.

## Phase 4: MNN-First Model Runtime Boundary

Goal: replace always-on Python/Torch/Docling server residency with a single
production model path: Rust orchestrates lazy local MNN model execution.

Scope:

```text
- Define model manifest contract for layout/table/OCR models.
- Use ONNX only as an intermediate conversion artifact.
- Convert ONNX artifacts to MNN before production packaging.
- Ship MNN artifacts for local runtime.
- Support FP32 MNN by default.
- Allow MNN weight-only 8-bit models only after benchmark delta is proven.
- Add lazy MNN model loading and unload policy.
- Add page-level routing:
  - simple text page -> Rust deterministic only
  - complex layout/table page -> MNN layout/table model
  - scanned/OCR page -> MNN OCR model
- Record model provenance and resource metrics in parserRun.
- Fail closed when a required MNN model is unavailable.
- Do not silently fall back to ONNX Runtime, Torch, Docling, Tesseract, PDFBox,
  or another parser backend.
```

Candidate model families:

```text
layout:
  RT-DETR/DocLayNet-style layout detector
  Docling layout model only if it can be converted into the MNN runtime path

table:
  TATR / Table Transformer
  SLANeXT / SLANet-style table recognizer where licensing and runtime permit

OCR:
  RapidOCR/MNN
  MNN-compatible OCR models with pinned manifest and corpus validation
```

TDD tests:

```text
RED: model manifest SHA mismatch blocks model use.
RED: missing required MNN model fails the requested model feature or marks output not audit-grade; it does not invoke another runtime.
RED: simple PDF does not start MNN runtime.
RED: table-heavy PDF routes only relevant pages to table model.
RED: scanned PDF routes to MNN OCR model.
RED: ONNX artifact is accepted only by the conversion toolchain, not by production parse runtime.
RED: Torch/Docling/OpenDataLoader hybrid cannot be selected as automatic runtime fallback.
RED: resource report includes model cold-start, inference time, and peak RSS when measurable.
```

Done when:

```text
DocTruth can parse a mixed corpus with lazy MNN model startup and lower steady
RSS than docling-fast/Torch while keeping documented quality on routed cases.
```

## Phase 5: Resource Gate And Edge Profile

Goal: make edge/local-agent use measurable and enforceable.

Profiles:

```text
edge-fast:
  Rust deterministic only.
  No network.
  No model server.
  Target RSS: low tens to low hundreds of MB.

edge-model:
  Rust deterministic + lazy MNN runtime.
  No Torch.
  Model cache verified.
  Target RSS: measured per model manifest and platform, materially below the
  docling-fast/Torch oracle, and released toward the profile idle budget after
  unload. No universal absolute RSS gate before the real MNN profile report.

benchmark-oracle:
  OpenDataLoader hybrid/docling-fast.
  Highest current quality reference.
  Explicit benchmark/comparison mode only.
  Not a production parse fallback.
```

TDD tests:

```text
RED: doctor reports active profile and unavailable capabilities.
RED: edge-fast profile rejects model startup.
RED: edge-model loads MNN models lazily.
RED: benchmark-oracle refuses to run unless explicitly requested.
RED: production profiles reject automatic runtime fallback chains.
RED: parser benchmark report includes RSS/cold-start/warm-run metrics.
```

Done when:

```text
doctruth doctor
doctruth parse --profile edge-fast
doctruth parse --profile edge-model
doctruth parse --profile benchmark-oracle
```

have explicit, tested behavior and resource reports.

## Phase 6: Benchmark Gates And Promotion Criteria

Goal: prevent parser-quality claims from drifting back into subjective language.

Required benchmark lanes:

```text
1. DocTruth seed corpus
2. OpenDataLoader Bench one-doc smoke
3. OpenDataLoader Bench subset by fixture type
4. OpenDataLoader Bench full 200 PDFs
5. replay-validity benchmark
6. resource benchmark
```

Promotion gates:

```text
OpenDataLoader hybrid benchmark oracle:
  must reproduce published/local hybrid baseline metrics within tolerance.
  must not be promoted as production runtime fallback.

Rust deterministic:
  must improve over current DocTruth runtime baseline and report known gaps.

Rust + MNN:
  must prove lower steady RSS than docling-fast and pass routed-case quality gates.
  ONNX artifacts are not production runtime artifacts.
  must run OpenDataLoader Bench because converted MNN models may degrade quality.
  quality may be slightly lower than OpenDataLoader hybrid oracle, but not
  materially worse.
  performance and resource use must be materially better than docling-fast/Torch.

Audit-grade:
  requires TrustDocument sourceRefs, quote replayability, evidence-span
  replayability, source hashes, parser warnings, and benchmark report binding.
```

Done when:

```text
No parser backend can be promoted to audit-grade only because its Markdown looks good.
```

### Final MNN Acceptance Gate

The MNN production runtime is accepted only when it passes a full measured
quality and resource gate against the same OpenDataLoader Bench corpus used for
the OpenDataLoader hybrid oracle.

Required run:

```text
DocTruth MNN runtime -> TrustDocument -> OpenDataLoader Bench prediction format
OpenDataLoader Bench evaluator -> NID/TEDS/MHS/overall
DocTruth resource benchmark -> cold start, warm latency, steady RSS, peak RSS
```

Reference baseline:

```text
OpenDataLoader hybrid oracle:
  overall: 0.9065718466674022
  NID:     0.9337307553293448
  TEDS:    0.9276430534097512
  MHS:     0.8207761855598542
  RSS:     about 1.39GB to 1.51GB for docling-fast server
  speed:   about 0.626s/doc on the measured full run
```

Initial acceptance target:

```text
Quality:
  overall >= 0.88
  NID     >= 0.91
  TEDS    >= 0.88
  MHS     >= 0.78

Resource/performance:
  no Python/Torch/Docling process in production parse runtime
  steady RSS must be materially lower than the measured docling-fast/Torch oracle
  cold start must be materially lower than docling-fast server startup
  warm per-doc latency should be competitive with OpenDataLoader hybrid
  absolute RSS values are measured budgets first, not universal product gates
  no implementation is accepted or rejected solely because it matches an
  arbitrary RSS number before a named profile report exists
```

The quality thresholds are explicit gates because they describe user-visible
parser quality. The resource thresholds are deliberately profile-based because
one memory number cannot honestly cover every model, page crop policy, allocator,
and machine. Resource gates are split into three levels:

```text
Level 1 hard gate:
  production parse runtime must not keep Python/Torch/Docling resident.

Level 2 comparative gate:
  Rust + MNN must be materially lighter than the measured docling-fast/Torch
  oracle on the same corpus and machine.

Level 3 profile regression gate:
  after a specific model manifest/platform/corpus has a measured report, future
  releases for that profile must not materially regress without a new report and
  rationale.
```

This matters because model size, precision mode, platform allocator behavior,
crop buffers, batching, and unload policy can change the absolute RSS profile.
The production resource hard gates are:

```text
- no Python/Torch/Docling process in production parse runtime
- steady RSS must be materially lower than the measured docling-fast/Torch oracle
- memory must return toward the configured idle budget after model unload
- each accepted model profile must publish cold-load RSS, warm steady RSS, peak
  RSS, idle-after-unload RSS, cold latency, warm latency, and corpus scope
```

Do not hard-code a universal absolute RSS threshold such as `steady RSS <=
600MB`. That would make the plan look precise while hiding the variables that
actually decide memory use.

Absolute RSS numbers are profiling budgets first. They become regression guards
only after a full benchmark report records the actual model set, precision
mode, platform, corpus scope, crop buffers, warm/idle behavior, unload policy,
and repeated-run variance. After that report exists, convert the observed
budget into a named profile guard with platform and model manifest names
attached. The guard protects against silent regression for that profile; it is
not a product-wide promise for every model or every machine.

Initial profiling budgets should be recorded per profile:

```text
edge-fast:
  expected to stay in low tens to low hundreds of MB because it does not load
  model runtimes.

edge-model:
  expected to remain far below the docling-fast/Torch oracle in steady state.
  record cold-load RSS, warm steady RSS, peak RSS, and idle-after-unload RSS.
  the first absolute target is set only after the first full MNN benchmark run.
  express it as a regression guard for that measured profile instead of a
  universal product promise.

edge-high-accuracy:
  allowed to use larger MNN model manifests when quality requires them.
  must still avoid Python/Torch/Docling residency and publish the same resource
  breakdown. It is compared against the heavy oracle and against the previous
  accepted high-accuracy profile, not against the edge-fast budget.

Example:
  if a specific Mac ARM64 edge-model profile with a pinned model manifest
  measures 451MB warm steady RSS, that number is recorded as the baseline for
  that exact profile. The guard should then be derived from repeated-run
  variance and release risk, for example "do not materially regress from the
  recorded Mac ARM64 edge-model baseline without an updated benchmark report,"
  rather than "all edge-model builds must stay below 600MB".
```

This means `451MB` is evidence, not policy. A future MNN OCR model, table model,
larger crop buffer, or Windows allocator may have a different absolute budget.
The acceptance target is therefore not `451MB + steady RSS <= 600MB`; it is
near-hybrid quality, no Python/Torch/Docling production residency, lazy MNN
loading, measurable unload behavior, and no unexplained regression against a
named profile baseline.

Practical interpretation: before the first MNN profile report, compare against
the measured heavy oracle and record the full resource breakdown. After the
first report, use that named profile as the baseline for future regression
checks. Do not turn a provisional measurement into a product-wide limit.
The product-level policy is:

```text
1. production runtime has no Python/Torch/Docling process
2. edge-model is lazy-loaded
3. idle unload is measurable
4. each model profile publishes its own budget
5. profile releases cannot materially regress without a new benchmark report
6. quality gates still apply to the same benchmark corpus
```

Any resource threshold change must be committed with:

```text
- full benchmark report
- per-case regression report
- resource report
- model-by-model RSS and latency breakdown when measurable
- explanation of whether loss comes from conversion, quantization, routing, model choice, or runtime buffers
- updated target and rationale
```

Done when:

```text
The MNN runtime proves near-hybrid quality with substantially lower resource
use, or the report clearly identifies the model/conversion gap blocking
promotion.
```

## Expected Outcome

This route gives DocTruth a practical product path:

```text
Short term:
  Use OpenDataLoader hybrid as an explicit heavy benchmark oracle.

Medium term:
  Move the deterministic parser brain into Rust and avoid models for ordinary PDFs.

Long term:
  Replace Python/Torch residency with lazy MNN model runtime where model quality
  is necessary.
```

The key product claim becomes:

```text
DocTruth can choose the cheapest parser path that preserves replayable evidence.
```

not:

```text
DocTruth rewrote every document parser from scratch in Rust before it works.
```

## Immediate Next TDD Slice

Start with Phase 1.

First RED tests:

```text
1. Benchmark-oracle command exposes `--engine opendataloader-hybrid` and fails
   clearly when the dependency is missing.
2. A fake OpenDataLoader hybrid oracle runner returns Markdown and provenance;
   DocTruth maps it into TrustDocument with
   `parserRun.backend=opendataloader-hybrid-oracle`.
3. Markdown-only source mapping marks output `NOT_AUDIT_GRADE` with a clear
   warning.
4. Production parse profiles cannot auto-select OpenDataLoader hybrid.
5. The one-document OpenDataLoader Bench smoke can use this oracle adapter and
   write a benchmark report.
```

Only after those tests fail for the right reason should implementation begin.
