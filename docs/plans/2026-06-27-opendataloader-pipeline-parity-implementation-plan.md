# OpenDataLoader Pipeline Parity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a processor-level OpenDataLoader parity layer so DocTruth ports behavior by processor contract instead of tuning individual benchmark samples.

**Architecture:** `TrustDocument` remains canonical. The parity layer exposes OpenDataLoader processor order, coverage status, focused contract ownership, and benchmark evidence through Rust runtime metadata and checked-in docs. Existing Java-core/OpenDataLoader-compatible behavior remains the current quality oracle while Rust owns runtime metadata, model/process orchestration, benchmark commands, and future replacement seams.

**Tech Stack:** Rust `doctruth-runtime`, Cargo tests, existing Java-core benchmark path, OpenDataLoader Bench artifacts, Markdown docs, shell benchmark scripts.

---

## Guardrails

- Do not replace `TrustDocument` with OpenDataLoader JSON or Markdown.
- Do not add Python/Torch/Docling production residency.
- Do not run full200 after every tiny change.
- Do not tune by PDF id unless the rule is generalized under a named processor.
- Commit each task separately.
- Preserve existing uncommitted work unless the user explicitly asks to fold it into a task.

## Task 1: Add Runtime Processor Parity Matrix

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
    ] {
        assert!(names.contains(&expected), "missing {expected}");
    }
}

#[test]
fn opendataloader_parity_matrix_has_status_and_owner_for_every_processor() {
    let matrix = opendataloader_parity_matrix_json();
    let processors = matrix["processors"].as_array().expect("processors array");

    assert!(!processors.is_empty());
    for entry in processors {
        assert!(entry["upstream"].as_str().is_some(), "missing upstream");
        assert!(entry["status"].as_str().is_some(), "missing status for {entry:?}");
        assert!(entry["doc_truth_owner"].as_str().is_some(), "missing owner for {entry:?}");
        assert!(entry["focused_test"].as_str().is_some(), "missing focused test for {entry:?}");
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
```

Expected: fail because `opendataloader_parity_matrix_json` does not exist.

**Step 3: Add the minimal runtime module**

Create `runtime/doctruth-runtime/src/opendataloader_parity.rs`:

```rust
use serde_json::{json, Value};

pub fn opendataloader_parity_matrix_json() -> Value {
    json!({
        "schema": "doctruth.opendataloader.parity_matrix.v1",
        "canonical_output": "TrustDocument",
        "processors": processors(),
    })
}

fn processors() -> Vec<Value> {
    vec![
        row("DocumentProcessor", "partial", "document_parse", "benchmark_corpus_contract"),
        row("TaggedDocumentProcessor", "partial", "structure_tree", "benchmark_corpus_contract"),
        row("TextProcessor", "partial", "text_filter", "opendataloader_text_processor_contract"),
        row("TextLineProcessor", "partial", "line_grouping", "opendataloader_line_paragraph_contract"),
        row("ParagraphProcessor", "partial", "paragraph_merge", "opendataloader_line_paragraph_contract"),
        row("HeadingProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
        row("ListProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
        row("CaptionProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
        row("LevelProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
        row("HeaderFooterProcessor", "partial", "header_footer", "PdfDocumentParserTest"),
        row("ContentFilterProcessor", "partial", "content_filter_probe", "opendataloader_content_filter_probe"),
        row("TextDecorationProcessor", "partial", "text_decoration", "opendataloader_text_processor_contract"),
        row("TableBorderProcessor", "partial", "table_border_probe", "opendataloader_table_processor_contract"),
        row("ClusterTableProcessor", "partial", "table_cluster", "opendataloader_table_processor_contract"),
        row("SpecialTableProcessor", "partial", "table_special_cases", "opendataloader_table_processor_contract"),
        row("TableStructureNormalizer", "partial", "table_normalizer", "opendataloader_table_processor_contract"),
        row("HybridDocumentProcessor", "partial", "java_core_auto_mnn", "benchmark_corpus_contract"),
        row("TriageProcessor", "partial", "triage_probe", "opendataloader_triage_probe"),
    ]
}

fn row(upstream: &str, status: &str, owner: &str, test: &str) -> Value {
    json!({
        "upstream": upstream,
        "status": status,
        "doc_truth_owner": owner,
        "focused_test": test,
        "full200_evidence": "",
        "remaining_gap": "tracked in docs/parser/opendataloader-processor-gap-report.md",
    })
}
```

Modify `runtime/doctruth-runtime/src/lib.rs`:

```rust
pub mod opendataloader_parity;
pub use opendataloader_parity::opendataloader_parity_matrix_json;
```

If `serde_json` is already available in the crate, reuse it. If not, add it to
the existing dependency list only after confirming `Cargo.toml`.

**Step 4: Add the checked-in matrix doc**

Create `docs/parser/opendataloader-parity-matrix.md` with the same processor
rows, status definitions, and pointer to `docs/parser/opendataloader-processor-gap-report.md`.

**Step 5: Run tests**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
git diff --check
```

Expected: pass.

**Step 6: Commit**

```bash
git add runtime/doctruth-runtime/src/lib.rs \
  runtime/doctruth-runtime/src/opendataloader_parity.rs \
  runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs \
  docs/parser/opendataloader-parity-matrix.md
git commit -m "feat: add opendataloader parity matrix"
```

## Task 2: Add Pipeline Stage Order Contract

**Files:**
- Modify: `runtime/doctruth-runtime/src/opendataloader_parity.rs`
- Modify: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`

**Step 1: Write the failing test**

Add this test:

```rust
#[test]
fn opendataloader_pipeline_stage_order_is_explicit() {
    let matrix = opendataloader_parity_matrix_json();
    let stages = matrix["pipeline_stages"].as_array().expect("pipeline stages");
    let names = stages
        .iter()
        .filter_map(|stage| stage["name"].as_str())
        .collect::<Vec<_>>();

    assert_eq!(
        names,
        vec![
            "pdf_text_extraction",
            "text_normalization",
            "content_filtering",
            "line_grouping",
            "paragraph_merge",
            "heading_hierarchy",
            "list_grouping",
            "caption_binding",
            "table_border_detection",
            "borderless_table_clustering",
            "table_structure_normalization",
            "chart_table_gate",
            "ocr_table_model_routing",
            "reading_order",
            "trust_document_export",
        ]
    );
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract opendataloader_pipeline_stage_order_is_explicit -- --nocapture
```

Expected: fail because `pipeline_stages` is missing.

**Step 3: Implement stage metadata**

Add `pipeline_stages()` to the parity module and include it in
`opendataloader_parity_matrix_json()`.

Each stage entry should include:

```json
{
  "name": "text_normalization",
  "owner": "TextProcessor",
  "canonical_output": "TrustDocument intermediate block stream"
}
```

Keep the data static and simple. Do not add runtime parser behavior in this
task.

**Step 4: Run tests**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
git diff --check
```

Expected: pass.

**Step 5: Commit**

```bash
git add runtime/doctruth-runtime/src/opendataloader_parity.rs \
  runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs
git commit -m "feat: expose opendataloader pipeline stage order"
```

## Task 3: Add Processor Ownership Contract for Existing Heuristics

**Files:**
- Modify: `runtime/doctruth-runtime/src/opendataloader_parity.rs`
- Modify: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`
- Modify: `docs/parser/opendataloader-parity-matrix.md`

**Step 1: Write the failing test**

Add a test that checks existing high-risk heuristic owners:

```rust
#[test]
fn existing_heuristics_are_mapped_to_processor_owners() {
    let matrix = opendataloader_parity_matrix_json();
    let heuristics = matrix["heuristic_owners"].as_array().expect("heuristic owners");
    let names = heuristics
        .iter()
        .filter_map(|entry| entry["heuristic"].as_str())
        .collect::<Vec<_>>();

    for expected in [
        "hidden_offpage_tiny_duplicate_text_filter",
        "right_aligned_paragraph_precedence",
        "wrapped_list_continuation",
        "nested_list_hierarchy",
        "caption_marker_classification",
        "survey_chart_table_rejection",
        "borderless_cluster_table_reconstruction",
        "ocr_rescue_sparse_java_output_only",
        "prediction_markdown_repair",
    ] {
        assert!(names.contains(&expected), "missing heuristic owner {expected}");
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract existing_heuristics_are_mapped_to_processor_owners -- --nocapture
```

Expected: fail because `heuristic_owners` is missing.

**Step 3: Implement heuristic owner metadata**

Add `heuristic_owners()` to the parity module. Each entry should include:

```json
{
  "heuristic": "wrapped_list_continuation",
  "processor": "ListProcessor",
  "owner": "structure_probe",
  "focused_test": "opendataloader_structure_contract"
}
```

Do not move implementation code yet. This task records ownership and creates
the contract that future code moves must satisfy.

**Step 4: Update matrix doc**

Add a "Heuristic Ownership" section to
`docs/parser/opendataloader-parity-matrix.md`.

**Step 5: Run tests**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
git diff --check
```

Expected: pass.

**Step 6: Commit**

```bash
git add runtime/doctruth-runtime/src/opendataloader_parity.rs \
  runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs \
  docs/parser/opendataloader-parity-matrix.md
git commit -m "feat: map parser heuristics to opendataloader processors"
```

## Task 4: Add Behavior-Family Contract Buckets

**Files:**
- Modify: `runtime/doctruth-runtime/src/opendataloader_parity.rs`
- Modify: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`
- Modify: `docs/parser/opendataloader-parity-matrix.md`

**Step 1: Write the failing test**

Add a test that ensures behavior-family coverage is represented:

```rust
#[test]
fn processor_contract_buckets_cover_behavior_families_not_pdf_ids() {
    let matrix = opendataloader_parity_matrix_json();
    let buckets = matrix["contract_buckets"].as_array().expect("contract buckets");
    let names = buckets
        .iter()
        .filter_map(|entry| entry["bucket"].as_str())
        .collect::<Vec<_>>();

    for expected in [
        "text_noise_filtering",
        "two_column_reading_order",
        "sidebar_reading_order",
        "paragraph_merge",
        "heading_hierarchy",
        "list_grouping",
        "caption_binding",
        "bordered_tables",
        "borderless_tables",
        "table_false_positive_rejection",
        "ocr_sparse_page_rescue",
    ] {
        assert!(names.contains(&expected), "missing contract bucket {expected}");
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract processor_contract_buckets_cover_behavior_families_not_pdf_ids -- --nocapture
```

Expected: fail because `contract_buckets` is missing.

**Step 3: Implement bucket metadata**

Add `contract_buckets()` to the parity module. Each bucket should include:

```json
{
  "bucket": "borderless_tables",
  "processor": "ClusterTableProcessor",
  "contract_style": "behavior_family",
  "not_pdf_id_patch": true
}
```

**Step 4: Update docs**

Add examples explaining that a processor contract covers a behavior family and
must not be a single benchmark PDF id patch.

**Step 5: Run tests**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
git diff --check
```

Expected: pass.

**Step 6: Commit**

```bash
git add runtime/doctruth-runtime/src/opendataloader_parity.rs \
  runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs \
  docs/parser/opendataloader-parity-matrix.md
git commit -m "feat: add opendataloader behavior contract buckets"
```

## Task 5: Add Stage-Gated Benchmark Report Contract

**Files:**
- Modify: `runtime/doctruth-runtime/src/opendataloader_parity.rs`
- Modify: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`
- Modify: `docs/parser/opendataloader-parity-matrix.md`
- Modify: `scripts/run-opendataloader-java-core-parity.sh`
- Modify: `scripts/run-doctruth-opendataloader-bench.sh`

**Step 1: Write the failing test**

Add a Rust metadata test:

```rust
#[test]
fn full200_gate_requires_metrics_resources_and_buckets() {
    let matrix = opendataloader_parity_matrix_json();
    let gate = &matrix["full200_gate"];

    for key in [
        "overall",
        "nid",
        "teds",
        "mhs",
        "parsed_count",
        "failed_count",
        "latency",
        "resources",
        "low_score_buckets",
        "artifact_path",
        "previous_doc_truth_baseline",
    ] {
        assert!(gate[key].is_string() || gate[key].is_array() || gate[key].is_object(), "missing {key}");
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract full200_gate_requires_metrics_resources_and_buckets -- --nocapture
```

Expected: fail because `full200_gate` is missing or incomplete.

**Step 3: Implement full200 gate metadata**

Add static metadata that defines required report fields. Do not hard-code the
latest benchmark numbers as acceptance truth in the runtime module; this is the
schema for reports, not the report itself.

**Step 4: Update bench scripts**

Ensure the scripts document or emit these fields in their generated report path:

```text
overall
nid
teds
mhs
parsed_count
failed_count
latency
resources
low_score_buckets
artifact_path
previous_doc_truth_baseline
```

Keep shell changes narrow. Do not rewrite the benchmark runner unless required.

**Step 5: Run tests and script smoke**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
sh scripts/run-opendataloader-java-core-parity.sh --help || true
sh scripts/run-doctruth-opendataloader-bench.sh --help || true
git diff --check
```

Expected: Rust tests pass. Script help may exit nonzero if the script has no
help mode, but it must not reveal syntax breakage from the edits.

**Step 6: Commit**

```bash
git add runtime/doctruth-runtime/src/opendataloader_parity.rs \
  runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs \
  docs/parser/opendataloader-parity-matrix.md \
  scripts/run-opendataloader-java-core-parity.sh \
  scripts/run-doctruth-opendataloader-bench.sh
git commit -m "feat: define opendataloader full200 gate contract"
```

## Task 6: Update Gap Report to Use the Parity Matrix

**Files:**
- Modify: `docs/parser/opendataloader-processor-gap-report.md`
- Modify: `docs/parser/opendataloader-parity-matrix.md`
- Modify: `docs/plans/2026-06-23-opendataloader-parity-coverage-plan.md`

**Step 1: Update docs**

Add a short "Source of truth" section:

```text
The parity matrix owns processor status and processor-order metadata.
The gap report owns detailed evidence and narrative.
The implementation plan owns execution steps.
```

**Step 2: Remove contradictory wording**

Make sure docs do not imply that:

- a single sample fix is parity
- Java is the destination parser core
- OpenDataLoader output is canonical
- full200 should run after every tiny change

**Step 3: Run docs verification**

Run:

```bash
git diff --check
```

Expected: pass.

**Step 4: Commit**

```bash
git add docs/parser/opendataloader-processor-gap-report.md \
  docs/parser/opendataloader-parity-matrix.md \
  docs/plans/2026-06-23-opendataloader-parity-coverage-plan.md
git commit -m "docs: align opendataloader parity docs"
```

## Task 7: Run Focused Verification and Prepare Full200 Gate

**Files:**
- No required source edits unless tests reveal a real metadata defect.

**Step 1: Run focused tests**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test benchmark_corpus_contract opendataloader_prediction_ -- --nocapture
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test model_worker_contract -- --nocapture
git diff --check
```

Expected: pass.

**Step 2: Decide whether full200 is warranted**

Only run full200 if Tasks 1-6 changed benchmark scripts or runtime output. If
only metadata/docs changed, record that full200 is not required yet.

**Step 3: Commit verification notes if docs changed**

If verification notes are added:

```bash
git add docs/parser/opendataloader-processor-gap-report.md findings.md
git commit -m "docs: record opendataloader parity verification"
```

## Final Handoff

After Task 7, report:

- commits created
- tests run
- whether full200 was run
- whether the branch is clean except for pre-existing user changes
- next processor family to port under the new contract

