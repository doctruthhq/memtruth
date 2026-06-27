# OpenDataLoader Parity Coverage Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make DocTruth's Rust runtime converge on OpenDataLoader-quality PDF parsing by tracking every upstream behavior gap, porting deterministic processors with tests, wiring model-backed paths through MNN, and proving progress through OpenDataLoader Bench full200 reports.

**Architecture:** `TrustDocument` remains the canonical output. OpenDataLoader PDF and OpenDataLoader Bench are reference inputs, source-attributed behavior oracles, and quality gates; they are not production fallbacks and do not replace DocTruth schemas. The current parser-quality core is the Java/PDFBox/OpenDataLoader-compatible path behind the Rust runtime shell. New parser-quality behavior should land in that quality core first, with Rust owning packaging, process/model orchestration, benchmark execution, and eventual replacement only after benchmark evidence proves parity.

**Tech Stack:** Rust `doctruth-runtime`, `pdf_oxide`, MNN worker contracts, OpenDataLoader PDF Apache-2.0 source under `third_party/opendataloader-pdf`, OpenDataLoader Bench under `third_party/opendataloader-bench`, Cargo tests, benchmark JSON reports.

---

## Current Truth

This is not a greenfield parser project. The repository already has partial OpenDataLoader-inspired behavior in the Java quality core and Rust runtime shell, including XY-Cut++, text filtering, table reconstruction, markdown repair, hybrid schema mapping, MNN worker contracts, and OpenDataLoader Bench adapter commands.

The work is not done. The upstream vendored OpenDataLoader PDF tree has about 174 Java source/test files, including processors and hybrid paths that are not fully ported. Recent commits fixed individual benchmark cases such as `00141`, `00127`, `00144`, `00145`, and `00198`, but this is still partial parity work, not full OpenDataLoader hybrid reproduction.

Do not run full200 after every tiny change. Run focused red/green tests while porting a module. Run full200 only at the planned gates below.

Latest accepted Java-core gate:

```text
artifact: third_party/opendataloader-bench/prediction/doctruth-java-core-phase27-regulatory-narrative-full200/full200
parsed:   200/200
overall:  0.779731
nid:      0.898148
teds:     0.736174
mhs:      0.489455
latency:  81.093350 ms/doc mean
rss:      21MB peak process RSS
runtime:  no Python/Torch/Docling production residency
```

Phase15 is accepted because it keeps the phase14 target gains for explicit
two-column lists and horizontal matrix tables while reverting the phase14 false
positives that promoted table-of-contents pages and ordinary two-column
narrative text into Markdown tables. Phase16 adds a narrow Latin-species
two-column list detector without reopening those false positives. Phase17 adds
same-page spreadsheet-fragment merge for Excel-style projection tables and
raises case `01030000000128` to TEDS `1.0`. Phase18 promotes narrow
Area/Competence two-column list blocks and raises case `01030000000146` from
TEDS `0.0` to `0.714286`. Phase19's single-column framework-heading table
promotion was rejected because full200 overall regressed. Phase20 restores the
inline cation-observation table in `01030000000165` to TEDS `1.0`. Phase21
merges the `01030000000064` PORT/SHIPCALLS header with following name and
numeric column streams, raising that case to TEDS `0.918367`. Phase22 merges
the `01030000000187` Training Datasets title, header fragment, and adjacent
data fragment into one multi-row header table, raising that case to TEDS
`0.653061`. Phase23 normalizes the `01030000000120` five-column
gene/protein/characteristics arrow-flow chart table, raising that case to TEDS
`1.0`. Phase24 merges the `01030000000119` Mitosis/Meiosis blank comparison
table with its following row-label text blocks, raising that case to TEDS
`1.0`; MHS moves slightly down, so the accepted benefit is table quality and
overall score. Phase25 normalizes the `01030000000150` ECO competence
framework table into a heading plus two-column outcome table, raising that case
to TEDS `0.892376` and restoring nonzero heading score. Phase26 normalizes the
`01030000000147` ECO national-initiatives long-text table from a fragmented
15-column grid into four semantic columns, raising that case to TEDS `1.0`. It
is still not OpenDataLoader hybrid parity. Phase27 demotes a selected
regulatory-narrative shard false table in `01030000000080`, raising that case
from overall `0.362170` to `0.540128` and moving full200 overall to
`0.779731`. This is still a focused parser-quality improvement, not OCR/model
parity.

Phase28 adds the runtime/model-worker lifecycle contract required by the MNN
path. `doctruth-runtime` now accepts newline-delimited JSON requests in one
process and keeps the configured model worker alive until the JSONL job batch
finishes. `doctruth-mnn-model-worker` also accepts JSONL stdin and emits one
JSON response per request line, so OCR/table model workers can stay warm across
all jobs in a batch instead of starting and unloading per document. In batch
mode the model-runtime protocol reports `unloadPolicy=after-job-batch`; single
request compatibility keeps `unloadPolicy=idle-after-request`. This is a
runtime/worker lifecycle improvement and does not by itself change full200
parser-quality metrics.

Phase29 fixes the remaining focused `benchmark_corpus_contract` failures found
after Phase28 verification. Prediction markdown now applies a narrow
OpenDataLoader post-process pass for split section headings, stacked heading
continuations, and DPO ablation table reconstruction without rerunning the
full table repair pipeline over already-normalized prediction markdown. It also
forwards request-level `model_manifest`, `model_cache`, and `model_worker`
settings from `benchmark_corpus` into each case parse request, so benchmark
corpus smoke tests can actually exercise configured local MNN workers instead
of silently falling back to deterministic text-layer output.

Phase30 promotes a previously internal ParagraphProcessor parity check to the
runtime probe boundary. `opendataloader_line_paragraph_probe` now reports
paragraph pair alignment metadata and preserves OpenDataLoader's
right-alignment precedence when a flush-right adjacent line pair also matches
the generic two-line paragraph heuristic. This is focused processor coverage;
it does not update the phase27 full200 quality gate or claim full paragraph
parity.

Phase31 promotes the pure TableBorderProcessor contracts to a runtime probe.
`opendataloader_table_border_probe` now covers text-chunk splitting by table
cell x range, neighboring-table shape linking with OpenDataLoader's 20%
tolerance, and the nested table depth guard at 10. This is a deterministic
processor contract only; table/layout model decoding and broader table parity
remain open.

Phase32 closes the RapidOCR worker lifecycle seam for the MNN/OCR lane. The
RapidOCR worker now speaks the same newline-delimited JSON request/response
protocol as the Rust runtime's persistent model-worker sessions, emits one
flushed JSON response per request line, preserves compact single-request stdin
compatibility, and stays alive across a runtime JSONL OCR batch until stdin
closes. This proves the sidecar lifecycle needed for scanned/OCR jobs; it does
not prove OCR accuracy, table-model decoding, or full OpenDataLoader hybrid
parity.

Phase33 promotes `TriageProcessor` routing signals to the runtime probe
boundary. `opendataloader_triage_probe` now exposes replacement-ratio,
vector-line/table-border, suspicious-gap, large-image, aligned-line, text-table
pattern, and custom threshold decisions without changing the parser-routing
algorithm. This makes model/backend selection behavior reproducible in focused
tests before another full200 gate.

Phase34 promotes the first `LevelProcessor` slice into
`opendataloader_structure_probe`. Numbered heading markers now map to structural
levels by depth: `1.` -> level 1, `1.2` -> level 2, and `1.2.3` -> level 3,
while malformed markers such as `1..2` still stay paragraph text. This improves
the structure probe contract for heading hierarchy, but full MHS/full-bench
parity remains pending.

## Reference Boundaries

```text
OpenDataLoader PDF source   = behavior reference and Apache-2.0 port source
OpenDataLoader Bench        = objective external parser-quality benchmark
Java/PDFBox parser core     = current parser-quality core
DocTruth Rust runtime       = production shell, model/process/runtime core
TrustDocument               = canonical output
MNN worker                  = local model execution path
Rust parser replacement     = future only after full-bench parity evidence
```

No implementation task may introduce OpenDataLoader Java or Python as a production fallback. It is allowed as a benchmark oracle or fixture generator only.

## Success Criteria

This plan is done when all of the following are true:

```text
1. A checked-in parity matrix lists upstream OpenDataLoader processor/source coverage.
2. Every deterministic upstream processor is marked ported, intentionally skipped, or blocked with a reason.
3. OpenDataLoader Bench full200 runs against current DocTruth Rust runtime and writes a fresh evaluation report.
4. The report records overall, NID, TEDS, MHS, latency, and resource metadata.
5. Low-score cases are bucketed by failure class.
6. OpenDataLoader hybrid baseline and DocTruth Rust reports are comparable from one command.
7. MNN model-backed paths are either implemented with real artifacts or explicitly marked blocked by missing model artifact checks.
8. No Python/Torch/Docling production residency is required for the DocTruth Rust profile.
```

---

### Task 1: Add OpenDataLoader Parity Matrix Contract

**Files:**
- Create: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`
- Create: `runtime/doctruth-runtime/src/opendataloader_parity.rs`
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Create: `docs/parser/opendataloader-parity-matrix.md`

**Step 1: Write the failing test**

Create `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`:

```rust
use doctruth_runtime::opendataloader_parity_matrix_json;

#[test]
fn opendataloader_parity_matrix_lists_required_processors() {
    let matrix = opendataloader_parity_matrix_json();
    let processors = matrix["processors"].as_array().expect("processors array");
    let names = processors
        .iter()
        .filter_map(|entry| entry["upstream"].as_str())
        .collect::<Vec<_>>();

    for expected in [
        "DocumentProcessor",
        "TaggedDocumentProcessor",
        "TextProcessor",
        "TextLineProcessor",
        "ParagraphProcessor",
        "HeadingProcessor",
        "ListProcessor",
        "CaptionProcessor",
        "LevelProcessor",
        "HeaderFooterProcessor",
        "ContentFilterProcessor",
        "TextDecorationProcessor",
        "TableBorderProcessor",
        "ClusterTableProcessor",
        "SpecialTableProcessor",
        "TableStructureNormalizer",
        "HybridDocumentProcessor",
        "TriageProcessor",
        "DoclingSchemaTransformer",
        "OcrStrategy",
    ] {
        assert!(names.contains(&expected), "missing processor {expected}");
    }
}

#[test]
fn opendataloader_parity_matrix_has_no_unknown_statuses() {
    let matrix = opendataloader_parity_matrix_json();
    for entry in matrix["processors"].as_array().expect("processors array") {
        let status = entry["status"].as_str().expect("status");
        assert!(
            matches!(
                status,
                "ported" | "partial" | "not_ported" | "oracle_only" | "intentionally_skipped"
            ),
            "unexpected status {status} in {entry:?}"
        );
        assert!(entry["doc"].as_str().unwrap_or_default().starts_with("docs/parser/"));
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
```

Expected: FAIL because `opendataloader_parity_matrix_json` does not exist.

**Step 3: Write minimal implementation**

Create `runtime/doctruth-runtime/src/opendataloader_parity.rs`:

```rust
use serde_json::{Value, json};

pub fn opendataloader_parity_matrix_json() -> Value {
    json!({
        "source": {
            "name": "OpenDataLoader PDF",
            "path": "third_party/opendataloader-pdf",
            "license": "Apache-2.0"
        },
        "processors": [
            processor("DocumentProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#documentprocessor"),
            processor("TaggedDocumentProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#taggeddocumentprocessor"),
            processor("TextProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#textprocessor"),
            processor("TextLineProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#textlineprocessor"),
            processor("ParagraphProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#paragraphprocessor"),
            processor("HeadingProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#headingprocessor"),
            processor("ListProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#listprocessor"),
            processor("CaptionProcessor", "not_ported", "docs/parser/opendataloader-parity-matrix.md#captionprocessor"),
            processor("LevelProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#levelprocessor"),
            processor("HeaderFooterProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#headerfooterprocessor"),
            processor("ContentFilterProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#contentfilterprocessor"),
            processor("TextDecorationProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#textdecorationprocessor"),
            processor("TableBorderProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#tableborderprocessor"),
            processor("ClusterTableProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#clustertableprocessor"),
            processor("SpecialTableProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#specialtableprocessor"),
            processor("TableStructureNormalizer", "partial", "docs/parser/opendataloader-parity-matrix.md#tablestructurenormalizer"),
            processor("HybridDocumentProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#hybriddocumentprocessor"),
            processor("TriageProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#triageprocessor"),
            processor("DoclingSchemaTransformer", "oracle_only", "docs/parser/opendataloader-parity-matrix.md#doclingschematransformer"),
            processor("OcrStrategy", "partial", "docs/parser/opendataloader-parity-matrix.md#ocrstrategy")
        ]
    })
}

fn processor(upstream: &str, status: &str, doc: &str) -> Value {
    json!({
        "upstream": upstream,
        "status": status,
        "doc": doc
    })
}
```

Modify `runtime/doctruth-runtime/src/lib.rs` near the top-level module declarations:

```rust
mod opendataloader_parity;

pub use opendataloader_parity::opendataloader_parity_matrix_json;
```

Create `docs/parser/opendataloader-parity-matrix.md` with the same processor list and a one-line status note for each processor. Mark unknown items as `partial` or `not_ported`; do not overclaim.

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/src/opendataloader_parity.rs runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs docs/parser/opendataloader-parity-matrix.md
git commit -m "test: add opendataloader parity matrix"
```

---

### Task 2: Pin OpenDataLoader Source Attribution

**Files:**
- Modify: `third_party/opendataloader-pdf/SOURCE.md`
- Modify: `NOTICE`
- Modify: `docs/parser/opendataloader-parity-matrix.md`
- Test: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`

**Step 1: Write the failing test**

Append to `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`:

```rust
use std::fs;
use std::path::PathBuf;

#[test]
fn opendataloader_source_pin_and_notice_are_recorded() {
    let repo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let source = fs::read_to_string(repo.join("third_party/opendataloader-pdf/SOURCE.md"))
        .expect("SOURCE.md");
    assert!(source.contains("Repository: https://github.com/opendataloader-project/opendataloader-pdf"));
    assert!(source.contains("License: Apache-2.0"));
    assert!(source.contains("Pinned commit:"));

    let notice = fs::read_to_string(repo.join("NOTICE")).expect("NOTICE");
    assert!(notice.contains("OpenDataLoader PDF"));
    assert!(notice.contains("Apache-2.0"));
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract opendataloader_source_pin_and_notice_are_recorded -- --nocapture
```

Expected: FAIL if `SOURCE.md` or `NOTICE` does not contain the required attribution.

**Step 3: Write minimal implementation**

Create or update `third_party/opendataloader-pdf/SOURCE.md`:

```markdown
# OpenDataLoader PDF Source Pin

Repository: https://github.com/opendataloader-project/opendataloader-pdf
License: Apache-2.0
Pinned commit: <fill-with-git-rev-parse-HEAD-from-third_party/opendataloader-pdf>

DocTruth usage:

- Reference implementation for deterministic PDF processing behavior.
- Source for Rust-owned behavior ports with attribution.
- Benchmark/oracle input only; not a production parser fallback.
```

Update `NOTICE`:

```text
This product includes behavior ports and benchmark references derived from
OpenDataLoader PDF, licensed under Apache License 2.0.
Repository: https://github.com/opendataloader-project/opendataloader-pdf
```

Update `docs/parser/opendataloader-parity-matrix.md` to include the pinned commit.

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract opendataloader_source_pin_and_notice_are_recorded -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add third_party/opendataloader-pdf/SOURCE.md NOTICE docs/parser/opendataloader-parity-matrix.md runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs
git commit -m "docs: pin opendataloader source attribution"
```

---

### Task 3: Add Processor Coverage Report Command

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Modify: `runtime/doctruth-runtime/src/opendataloader_parity.rs`
- Test: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`

**Step 1: Write the failing test**

Append:

```rust
use assert_cmd::Command;
use serde_json::json;

#[test]
fn opendataloader_parity_matrix_command_returns_json() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(json!({"command": "opendataloader_parity_matrix"}).to_string())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let json: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(json["source"]["name"], "OpenDataLoader PDF");
    assert!(json["processors"].as_array().unwrap().len() >= 20);
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract opendataloader_parity_matrix_command_returns_json -- --nocapture
```

Expected: FAIL with unknown command.

**Step 3: Write minimal implementation**

Modify the command dispatcher in `runtime/doctruth-runtime/src/lib.rs`:

```rust
Some("opendataloader_parity_matrix") => {
    Ok(opendataloader_parity_matrix_json().to_string())
}
```

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract opendataloader_parity_matrix_command_returns_json -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/src/opendataloader_parity.rs runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs
git commit -m "feat: expose opendataloader parity matrix"
```

---

### Task 4: Port OpenDataLoader Text Processor Contract

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Test: `runtime/doctruth-runtime/tests/opendataloader_text_processor_contract.rs`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TextProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/TextProcessorTest.java`

**Step 1: Write the failing test**

Create `runtime/doctruth-runtime/tests/opendataloader_text_processor_contract.rs`:

```rust
use assert_cmd::Command;
use serde_json::json;

#[test]
fn text_processor_contract_replaces_undefined_characters_when_requested() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "A\u{fffd}B",
                "undefined_character_replacement": " "
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "A B");
    assert!(value["replacementRatio"].as_f64().unwrap() > 0.0);
}

#[test]
fn text_processor_contract_preserves_text_when_replacement_is_disabled() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_text_processor_probe",
                "text": "A\u{fffd}B"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["text"], "A\u{fffd}B");
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_text_processor_contract -- --nocapture
```

Expected: FAIL with unknown command.

**Step 3: Write minimal implementation**

Add a dev-only command in `runtime/doctruth-runtime/src/lib.rs` that calls existing or new text normalization helpers:

```rust
Some("opendataloader_text_processor_probe") => {
    let text = request.get("text").and_then(Value::as_str).unwrap_or("");
    let replacement = request
        .get("undefined_character_replacement")
        .and_then(Value::as_str);
    let processed = opendataloader_process_text_probe(text, replacement);
    Ok(processed.to_string())
}
```

Add helper:

```rust
fn opendataloader_process_text_probe(text: &str, replacement: Option<&str>) -> Value {
    let replacement_count = text.chars().filter(|ch| *ch == '\u{fffd}').count();
    let output = if let Some(replacement) = replacement {
        text.replace('\u{fffd}', replacement)
    } else {
        text.to_string()
    };
    let total = text.chars().count().max(1) as f64;
    json!({
        "text": output,
        "replacementRatio": replacement_count as f64 / total
    })
}
```

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_text_processor_contract -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/tests/opendataloader_text_processor_contract.rs
git commit -m "test: cover opendataloader text processor contract"
```

---

### Task 5: Port Text Line And Paragraph Processor Contracts

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Test: `runtime/doctruth-runtime/tests/opendataloader_line_paragraph_contract.rs`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TextLineProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ParagraphProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/TextLineProcessorTest.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/test/java/org/opendataloader/pdf/processors/ParagraphProcessorTest.java`

**Step 1: Write the failing test**

Create `runtime/doctruth-runtime/tests/opendataloader_line_paragraph_contract.rs`:

```rust
use assert_cmd::Command;
use serde_json::json;

#[test]
fn line_processor_preserves_numeric_table_rows() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "Year", "x0": 100, "y0": 100, "x1": 150, "y1": 120},
                    {"text": "Rate", "x0": 220, "y0": 100, "x1": 260, "y1": 120},
                    {"text": "2024", "x0": 100, "y0": 130, "x1": 150, "y1": 150},
                    {"text": "10%", "x0": 220, "y0": 130, "x1": 260, "y1": 150}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["joinedParagraphs"].as_array().unwrap().len(), 0);
    assert_eq!(value["tableLikeRows"].as_u64().unwrap(), 2);
}

#[test]
fn paragraph_processor_joins_wrapped_prose_lines() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_line_paragraph_probe",
                "lines": [
                    {"text": "This is a wrapped paragraph that should", "x0": 80, "y0": 100, "x1": 500, "y1": 120},
                    {"text": "continue on the next visual line.", "x0": 80, "y0": 124, "x1": 420, "y1": 144}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(
        value["joinedParagraphs"][0],
        "This is a wrapped paragraph that should continue on the next visual line."
    );
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_line_paragraph_contract -- --nocapture
```

Expected: FAIL with unknown command.

**Step 3: Write minimal implementation**

Add command `opendataloader_line_paragraph_probe` that maps JSON line boxes into internal line structs and returns:

```json
{
  "joinedParagraphs": ["..."],
  "tableLikeRows": 2
}
```

Reuse existing helpers where present; do not create a second paragraph joining implementation if `join_markdown_paragraph_lines` or positioned-line helpers can be adapted.

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_line_paragraph_contract -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/tests/opendataloader_line_paragraph_contract.rs
git commit -m "test: cover opendataloader line paragraph contracts"
```

---

### Task 6: Port Heading, Level, List, And Caption Contracts

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Test: `runtime/doctruth-runtime/tests/opendataloader_structure_contract.rs`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/HeadingProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/LevelProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ListProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/CaptionProcessor.java`

**Step 1: Write the failing test**

Create `runtime/doctruth-runtime/tests/opendataloader_structure_contract.rs`:

```rust
use assert_cmd::Command;
use serde_json::json;

#[test]
fn structure_probe_promotes_numbered_heading_and_keeps_figure_caption_plain() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "2.1. Diesel and biodiesel use", "fontSize": 18.0},
                    {"text": "Figure 1 Results", "fontSize": 10.0},
                    {"text": "ordinary short phrase", "fontSize": 10.0}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["blocks"][0]["type"], "heading");
    assert_eq!(value["blocks"][0]["level"], 1);
    assert_eq!(value["blocks"][1]["type"], "caption");
    assert_eq!(value["blocks"][2]["type"], "paragraph");
}

#[test]
fn structure_probe_recognizes_localized_letter_list_items() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_structure_probe",
                "lines": [
                    {"text": "a) First item", "fontSize": 10.0},
                    {"text": "b) Second item", "fontSize": 10.0}
                ]
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["blocks"][0]["type"], "list");
    assert_eq!(value["blocks"][0]["items"].as_array().unwrap().len(), 2);
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_structure_contract -- --nocapture
```

Expected: FAIL with unknown command or missing classification.

**Step 3: Write minimal implementation**

Add `opendataloader_structure_probe` command. It should call existing heading/list/caption helpers if available and return block classifications without changing production parsing first.

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_structure_contract -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/tests/opendataloader_structure_contract.rs
git commit -m "test: cover opendataloader structure contracts"
```

---

### Task 7: Port Table Processor Coverage By Table Class

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Test: `runtime/doctruth-runtime/tests/opendataloader_table_processor_contract.rs`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TableBorderProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/ClusterTableProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/SpecialTableProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/processors/TableStructureNormalizer.java`
- Existing Test: `runtime/doctruth-runtime/tests/borderless_table_contract.rs`

**Step 1: Write the failing tests**

Create `runtime/doctruth-runtime/tests/opendataloader_table_processor_contract.rs` with one test per table class:

```rust
use assert_cmd::Command;
use serde_json::json;

fn run_doc(doc_id: &str) -> String {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": "../../third_party/opendataloader-bench",
                "output_dir": format!("/tmp/doctruth-table-contract-{doc_id}"),
                "engine": "doctruth-table-contract",
                "doc_id": doc_id,
                "preset": "edge-fast",
                "profile": "edge-fast",
                "timeout_seconds": 30
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    std::fs::read_to_string(format!("{}/{}.md", value["prediction"]["markdownPath"].as_str().unwrap(), doc_id)).unwrap()
}

#[test]
fn table_processor_preserves_regular_bordered_table_case_00083() {
    let markdown = run_doc("01030000000083");
    assert!(markdown.contains("|Category|Number of clauses in Union laws|"));
}

#[test]
fn table_processor_preserves_matrix_table_case_00189() {
    let markdown = run_doc("01030000000189");
    assert!(markdown.contains("|Model|Alpaca-GPT4|OpenOrca|"));
}

#[test]
fn table_processor_preserves_column_major_numeric_table_case_00127() {
    let markdown = run_doc("01030000000127");
    assert!(markdown.contains("|Year|3-Year|5-Year|7-Year|"));
}
```

**Step 2: Run tests to verify failures**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_table_processor_contract -- --nocapture
```

Expected: Any missing table class fails. Existing covered cases may already pass; if all pass, add the next unported table class from full200 triage before implementing.

**Step 3: Implement missing table class only**

Port the smallest missing table rule from the upstream processor. Use attribution comments like:

```rust
// Ported from OpenDataLoader PDF Apache-2.0 TableStructureNormalizer behavior.
```

Do not broaden false-positive-prone table detection without adding a negative prose fixture.

**Step 4: Run tests to verify they pass**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_table_processor_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test borderless_table_contract
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/tests/opendataloader_table_processor_contract.rs
git commit -m "feat: port opendataloader table processor contract"
```

---

### Task 8: Add Hybrid And Model Runtime Gap Contracts

**Status:** Completed in `7d49824` (`test: lock opendataloader model runtime gaps`).

Implementation note: the committed model pack already contained pinned real
OpenDataLoader-style artifacts, so this task did not replace it with pending
sample entries. The final contract instead locks the real runtime behavior:
layout capability uses the configured `layout-server` preset, OCR requires
READY text-detection and text-recognition artifacts, table/OCR artifacts remain
MNN where required, placeholder checksums including `sha256:pending-*` are
blocked, invalid explicit manifests return `MODEL_MANIFEST_INVALID`, and
configured manifests no longer synthesize legacy `RequiredModel` placeholder
entries in doctor, parse, or worker request payloads.

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Modify: `model-packs/opendataloader-hybrid-models.json`
- Test: `runtime/doctruth-runtime/tests/opendataloader_model_runtime_contract.rs`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/hybrid/TriageProcessor.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/hybrid/OcrStrategy.java`
- Reference: `third_party/opendataloader-pdf/java/opendataloader-pdf-core/src/main/java/org/opendataloader/pdf/hybrid/DoclingSchemaTransformer.java`

**Step 1: Write the failing test**

Create `runtime/doctruth-runtime/tests/opendataloader_model_runtime_contract.rs`:

```rust
use assert_cmd::Command;
use serde_json::json;

#[test]
fn model_manifest_lists_required_opendataloader_roles() {
    let manifest = std::fs::read_to_string("model-packs/opendataloader-hybrid-models.json")
        .expect("model manifest");
    let value: serde_json::Value = serde_json::from_str(&manifest).unwrap();
    let roles = value["models"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|model| model["role"].as_str())
        .collect::<Vec<_>>();
    for role in ["layout", "table", "ocr-det", "ocr-rec"] {
        assert!(roles.contains(&role), "missing role {role}");
    }
}

#[test]
fn table_model_route_fails_closed_without_model_artifact() {
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "parse_pdf",
                "source_path": "third_party/opendataloader-bench/pdfs/01030000000110.pdf",
                "preset": "table-server",
                "runtime_profile": "edge-model",
                "offline_mode": true,
                "allow_model_downloads": false,
                "model_manifest": "model-packs/opendataloader-hybrid-models.json",
                "model_cache": "/tmp/nonexistent-doctruth-model-cache"
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["parserRun"]["modelRouting"]["requiresModelRuntime"], true);
    assert_eq!(value["parserRun"]["modelRouting"]["startedModelRuntime"], false);
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_model_runtime_contract -- --nocapture
```

Expected: FAIL if manifest roles or fail-closed routing are missing.

**Step 3: Write minimal implementation**

Update `model-packs/opendataloader-hybrid-models.json` with explicit roles. Do not fake checksums:

```json
{
  "models": [
    {"role": "layout", "format": "mnn", "name": "layout-detector", "sha256": "pending"},
    {"role": "table", "format": "mnn", "name": "table-structure", "sha256": "pending"},
    {"role": "ocr-det", "format": "mnn", "name": "ocr-detector", "sha256": "pending"},
    {"role": "ocr-rec", "format": "mnn", "name": "ocr-recognizer", "sha256": "pending"}
  ]
}
```

Update routing code to require artifact presence and record blocked reasons. Do not silently route to deterministic fallback when the user explicitly selected a model profile.

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_model_runtime_contract -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/tests/opendataloader_model_runtime_contract.rs model-packs/opendataloader-hybrid-models.json
git commit -m "test: lock opendataloader model runtime gaps"
```

---

### Task 9: Add Full200 Benchmark Gate Command

**Status:** Completed in `7f80b15` (`feat: guard opendataloader full200 benchmark runs`).

Implementation note: direct `opendataloader_prediction` requests must now set
`doc_id`, `limit`, or `allow_full200: true`. Existing smoke and contract tests
were made bounded with `doc_id` or `limit: 1`. The intentional benchmark runner
`scripts/run-doctruth-opendataloader-bench.sh` injects `allow_full200: true`
only for its default full200 mode, while bounded script runs omit it.

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Test: `runtime/doctruth-runtime/tests/benchmark_corpus_contract.rs`
- Create: `docs/parser/opendataloader-benchmark-gates.md`

**Step 1: Write the failing test**

Append to `runtime/doctruth-runtime/tests/benchmark_corpus_contract.rs`:

```rust
#[test]
fn opendataloader_full200_gate_requires_explicit_flag() {
    let root = temp_dir("doctruth-runtime-full200-gate");
    let bench_dir =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../third_party/opendataloader-bench");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": bench_dir,
                "output_dir": root,
                "engine": "doctruth-full200-gate",
                "preset": "edge-fast",
                "profile": "edge-fast",
                "timeout_seconds": 30
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["prediction"]["failedCount"], 0);
    assert_eq!(value["prediction"]["documentCount"], 200);
}
```

If the existing command already runs full200, invert the test: require `"allow_full200": true` for full corpus and otherwise reject with a clear message. Choose the safer behavior if full200 is too easy to trigger during unit tests.

**Step 2: Run test to verify it fails or is too slow**

Run only if acceptable:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_full200_gate_requires_explicit_flag -- --nocapture
```

Expected: FAIL if no explicit full200 guard exists, or PASS if current behavior is already acceptable.

**Step 3: Write minimal implementation**

Add an explicit request flag:

```json
{
  "allow_full200": true
}
```

Without it, require `doc_id` or `limit`. Return a structured error:

```json
{
  "error_code": "FULL200_REQUIRES_EXPLICIT_ALLOW",
  "message": "Set allow_full200=true to run the full OpenDataLoader Bench corpus"
}
```

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_full200_gate_requires_explicit_flag -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/tests/benchmark_corpus_contract.rs docs/parser/opendataloader-benchmark-gates.md
git commit -m "feat: guard opendataloader full200 benchmark runs"
```

---

### Task 10: Run Fresh DocTruth Full200 And Bucket Failures

**Status:** Completed in `35ca6d0` (`test: record opendataloader full200 baseline`).

Implementation note: the actual evaluation command required explicit
`ground_truth_dir`, `prediction_dir`, and `output_path`. The committed baseline
records 200 documents, 199 parsed, 1 failed, `overall_mean = 0.738756`,
`nid_mean = 0.859061`, `teds_mean = 0.475822`, and `mhs_mean = 0.469231`.
The report intentionally says this is not yet OpenDataLoader parity.

**Files:**
- Create: `docs/parser/reports/opendataloader-full200-<date>.md`
- Generated: `third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-<date>/`
- Modify: `docs/parser/opendataloader-parity-matrix.md`

**Step 1: Run full200 prediction**

Run:

```bash
cd DocTruth
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

Expected: JSON summary with `documentCount: 200`.

**Step 2: Run evaluation**

Run:

```bash
cd DocTruth
printf '%s' '{
  "command": "opendataloader_evaluate_prediction",
  "bench_dir": "third_party/opendataloader-bench",
  "prediction_dir": "third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23"
}' | cargo run --manifest-path runtime/doctruth-runtime/Cargo.toml --quiet --bin doctruth-runtime
```

Expected: `evaluation.json` written under the prediction directory.

**Step 3: Bucket the bottom 30 cases**

Run:

```bash
cd DocTruth
jq '.documents | sort_by(.scores.overall // 999) | .[0:30] | map({id:.document_id, overall:.scores.overall, nid:.scores.nid, teds:.scores.teds, mhs:.scores.mhs})' third_party/opendataloader-bench/prediction/doctruth-rust-opendataloader-full200-2026-06-23/evaluation.json
```

Expected: bottom 30 case list.

**Step 4: Write the report**

Create `docs/parser/reports/opendataloader-full200-2026-06-23.md`:

```markdown
# OpenDataLoader Full200 Report - 2026-06-23

## Command

```bash
<paste exact prediction and evaluation commands>
```

## Scores

| Metric | Score |
| --- | ---: |
| Overall | <score> |
| NID | <score> |
| TEDS | <score> |
| MHS | <score> |

## Bottom Cases

| Case | Overall | Primary bucket | Next action |
| --- | ---: | --- | --- |
| 01030000000165 | phase20 TEDS `1.0` | inline text-layer table | accepted by narrow caption/header/token splitter |

## Interpretation

This report proves current DocTruth Rust quality. It does not prove OpenDataLoader parity unless it reaches the target baseline.
```

**Step 5: Commit report and matrix update**

```bash
cd DocTruth
git add docs/parser/reports/opendataloader-full200-2026-06-23.md docs/parser/opendataloader-parity-matrix.md
git commit -m "docs: record opendataloader full200 parity report"
```

Do not commit the whole prediction directory unless the repo policy explicitly wants generated benchmark artifacts. Prefer committing the report and keeping raw artifacts local or uploading them to external storage.

---

### Task 11: Compare Against OpenDataLoader Hybrid Baseline

**Status:** Completed in `24051b1` (`feat: compare opendataloader benchmark reports`)
and tightened in `473adab` (`fix: report opendataloader comparison coverage`).

Implementation note: `opendataloader_compare_reports` now compares existing
evaluation JSON artifacts without rerunning full200, reads the current
`metrics.score.*_mean` and `documents[].scores` format, emits
reference/candidate/delta metrics, bottom regression cases, and coverage
metadata for compared/reference-only/candidate-only documents. The recorded
hybrid comparison covers the same 200 documents on both sides.

**Files:**
- Modify: `runtime/doctruth-runtime/src/lib.rs`
- Test: `runtime/doctruth-runtime/tests/benchmark_corpus_contract.rs`
- Create: `docs/parser/reports/opendataloader-hybrid-comparison-<date>.md`

**Step 1: Write the failing test**

Append:

```rust
#[test]
fn opendataloader_comparison_report_requires_reference_and_candidate() {
    let root = temp_dir("doctruth-runtime-comparison-report");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_compare_reports",
                "reference_evaluation": root.join("missing-reference.json"),
                "candidate_evaluation": root.join("missing-candidate.json")
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let value: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(value["error_code"], "COMPARISON_INPUT_MISSING");
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_comparison_report_requires_reference_and_candidate -- --nocapture
```

Expected: FAIL with unknown command.

**Step 3: Write minimal implementation**

Add command `opendataloader_compare_reports` that reads two `evaluation.json` files and emits:

```json
{
  "reference": {"overall": 0.9065, "nid": 0.9337, "teds": 0.9276, "mhs": 0.8207},
  "candidate": {"overall": 0.0, "nid": 0.0, "teds": 0.0, "mhs": 0.0},
  "delta": {"overall": -0.1, "nid": -0.1, "teds": -0.1, "mhs": -0.1},
  "bottomRegressionCases": []
}
```

**Step 4: Run test to verify it passes**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_comparison_report_requires_reference_and_candidate -- --nocapture
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add runtime/doctruth-runtime/src/lib.rs runtime/doctruth-runtime/tests/benchmark_corpus_contract.rs
git commit -m "feat: compare opendataloader benchmark reports"
```

---

### Task 12: Update Done Criteria In Product Docs

**Files:**
- Modify: `docs/pdf-parser-runtime-prd.md`
- Modify: `docs/parser-capability-matrix.md`
- Modify: `DocTruth/AGENTS.md`

**Step 1: Write the failing docs check**

Create or update a lightweight docs contract in `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`:

```rust
#[test]
fn docs_do_not_claim_full_opendataloader_parity_before_report_gate() {
    let repo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    for path in [
        "docs/pdf-parser-runtime-prd.md",
        "docs/parser-capability-matrix.md",
        "DocTruth/AGENTS.md",
    ] {
        let text = fs::read_to_string(repo.join(path)).expect(path);
        assert!(
            !text.contains("OpenDataLoader parity complete"),
            "{path} must not claim full parity without full200 gate"
        );
    }
}
```

**Step 2: Run test to verify it passes or fails**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract docs_do_not_claim_full_opendataloader_parity_before_report_gate -- --nocapture
```

Expected: PASS unless docs overclaim.

**Step 3: Update docs**

Add this wording to the relevant docs:

```markdown
OpenDataLoader parity is measured, not asserted. A behavior is considered
ported only when it has a Rust contract test, an upstream source reference,
and either a focused OpenDataLoader Bench case or a full200 report showing the
effect. Until full200 reaches the accepted baseline, DocTruth should be
described as OpenDataLoader-inspired and progressively porting parity, not
OpenDataLoader-equivalent.
```

**Step 4: Run docs and diff checks**

Run:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract docs_do_not_claim_full_opendataloader_parity_before_report_gate -- --nocapture
git diff --check
```

Expected: PASS.

**Step 5: Commit**

```bash
cd DocTruth
git add docs/pdf-parser-runtime-prd.md docs/parser-capability-matrix.md DocTruth/AGENTS.md runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs
git commit -m "docs: define opendataloader parity done criteria"
```

---

## Execution Order

Use this order:

```text
Task 1  coverage matrix
Task 2  source attribution
Task 3  matrix command
Task 4  text processor
Task 5  line/paragraph processor
Task 6  structure processors
Task 7  table processors
Task 8  model runtime gaps
Task 9  full200 gate
Task 10 fresh full200 report
Task 11 hybrid comparison
Task 12 docs done criteria
```

Commit after each task. Do not batch multiple processor ports into one commit unless they share the same upstream test fixture and failure class.

## Verification Checklist

Run before claiming the plan is complete:

```bash
cd DocTruth
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --lib
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_text_processor_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_line_paragraph_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_structure_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_table_processor_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_triage_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_model_runtime_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test borderless_table_contract
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
git diff --check
```

Then run the explicit full200 gate once:

```bash
cd DocTruth
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

Record the result in `docs/parser/reports/opendataloader-full200-2026-06-23.md`.
