# OpenDataLoader Processor Parity Completion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bring the OpenDataLoader parity work back into the processor-level plan by classifying temporary benchmark repairs, adding behavior-family contracts, and moving new quality improvements under named processor ownership instead of scattered PDF-case patches.

**Architecture:** Java/PDFBox remains the current parser quality core and Rust remains the runtime shell, benchmark runner, resource reporter, and no-Python boundary. `TrustDocument` remains canonical. OpenDataLoader is the behavior reference, and OpenDataLoader-style repairs must be owned by a processor bucket with focused tests and full200 evidence.

**Tech Stack:** Java 25, Maven, PDFBox 3, DocTruth parser classes, Rust `doctruth-runtime` parity metadata, OpenDataLoader Bench full200 artifacts, Markdown processor docs.

---

## Context

Current branch:

```text
feat/opendataloader-parity-coverage
```

Current problem:

```text
PR #13 is mergeable and CI-green, but the latest quality change reintroduced
some case-shaped table repairs in PdfDocumentParser:

- demoteChartAxisTables
- promoteRemittanceGrowthTables
- promoteKinematicViscosityTables

Those improve full200, but the plan requires processor-level parity:
HeadingProcessor, TaggedDocumentProcessor, ClusterTableProcessor,
SpecialTableProcessor, and TableStructureNormalizer must own behavior-family
contracts. Single PDF-id style repairs may only exist as explicit temporary
benchmark repairs with an owner and replacement plan.
```

Target acceptance:

```text
- All current narrow repairs are explicitly classified in runtime metadata and docs.
- New focused tests prove processor behavior families, not just PDF ids.
- PdfDocumentParser can still call temporary repairs, but only through a named
  processor repair pipeline with ownership metadata.
- full200 remains a stage gate and passes after the processor-family changes.
- PR #13 stays mergeable and CI green.
```

## Task 1: Add Temporary Repair Registry To Parity Metadata

**Files:**
- Modify: `runtime/doctruth-runtime/src/opendataloader_parity.rs`
- Modify: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`
- Modify: `docs/parser/opendataloader-parity-matrix.md`
- Modify: `docs/parser/opendataloader-processor-gap-report.md`

**Step 1: Write the failing test**

Add this test to `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`:

```rust
#[test]
fn temporary_benchmark_repairs_are_explicitly_owned_and_not_claimed_as_parity() {
    let matrix = opendataloader_parity_matrix_json();
    let repairs = matrix["temporary_repairs"]
        .as_array()
        .expect("temporary repairs array");
    let names = repairs
        .iter()
        .filter_map(|entry| entry["repair"].as_str())
        .collect::<Vec<_>>();

    for expected in [
        "remittance_growth_table_reconstruction",
        "kinematic_viscosity_table_reconstruction",
        "chart_axis_fragment_demotion",
        "blank_comparison_table_merge",
        "national_initiatives_table_normalization",
        "eco_competence_framework_normalization",
        "area_competence_table_promotion",
        "training_dataset_fragment_merge",
        "port_shipcall_column_stream_merge",
        "inline_cation_observation_split",
        "regulatory_narrative_shard_demotion",
    ] {
        assert!(names.contains(&expected), "missing temporary repair {expected}");
    }

    for entry in repairs {
        assert_eq!(entry["parity_claim"].as_bool(), Some(false));
        assert!(entry["processor"].as_str().is_some(), "missing processor");
        assert!(entry["replacement_plan"].as_str().is_some(), "missing replacement plan");
        assert!(entry["focused_test"].as_str().is_some(), "missing focused test");
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract temporary_benchmark_repairs_are_explicitly_owned_and_not_claimed_as_parity -- --nocapture
```

Expected: FAIL because `temporary_repairs` is missing.

**Step 3: Implement metadata**

Add `temporary_repairs()` to `runtime/doctruth-runtime/src/opendataloader_parity.rs` and include it in `opendataloader_parity_matrix_json()`.

Each entry must include:

```json
{
  "repair": "remittance_growth_table_reconstruction",
  "processor": "TableStructureNormalizer",
  "bucket": "borderless_tables",
  "parity_claim": false,
  "focused_test": "PdfBorderlessTableExtractionTest",
  "replacement_plan": "replace with generalized multi-column table reconstruction before marking TableStructureNormalizer matched"
}
```

Map repairs as follows:

```text
chart_axis_fragment_demotion -> SpecialTableProcessor / table_false_positive_rejection
regulatory_narrative_shard_demotion -> SpecialTableProcessor / table_false_positive_rejection
remittance_growth_table_reconstruction -> TableStructureNormalizer / borderless_tables
kinematic_viscosity_table_reconstruction -> TableStructureNormalizer / borderless_tables
blank_comparison_table_merge -> TableStructureNormalizer / borderless_tables
national_initiatives_table_normalization -> TableStructureNormalizer / borderless_tables
eco_competence_framework_normalization -> TableStructureNormalizer / borderless_tables
area_competence_table_promotion -> ClusterTableProcessor / borderless_tables
training_dataset_fragment_merge -> ClusterTableProcessor / borderless_tables
port_shipcall_column_stream_merge -> ClusterTableProcessor / borderless_tables
inline_cation_observation_split -> TableStructureNormalizer / bordered_tables
```

**Step 4: Update docs**

In `docs/parser/opendataloader-parity-matrix.md`, add a "Temporary Benchmark Repairs" section with the same rows.

In `docs/parser/opendataloader-processor-gap-report.md`, add a short note before the Phase history:

```text
The Phase11-Phase28 narrow repairs are accepted benchmark repairs, not processor
parity claims. They are tracked in the temporary repair registry until the
owning processor has generalized behavior-family coverage.
```

**Step 5: Run tests**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
mvn -q -Dtest=OpenDataLoaderProcessorParityTest test
git diff --check
```

Expected: PASS.

**Step 6: Commit**

```bash
git add runtime/doctruth-runtime/src/opendataloader_parity.rs \
  runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs \
  docs/parser/opendataloader-parity-matrix.md \
  docs/parser/opendataloader-processor-gap-report.md
git commit -m "docs: classify opendataloader temporary repairs"
```

## Task 2: Add Processor-Family Contracts For Remaining Low Buckets

**Files:**
- Modify: `src/test/java/ai/doctruth/opendataloader/OpenDataLoaderProcessorParityTest.java`
- Modify: `runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs`
- Modify: `docs/parser/opendataloader-parity-matrix.md`

**Step 1: Write failing Java contract**

Add a test to `OpenDataLoaderProcessorParityTest` that verifies every low-score bucket from the latest accepted full200 has a processor owner and a next action:

```java
@Test
void latestLowScoreBucketsHaveProcessorOwnersAndNextActions() throws IOException {
    var matrix = Files.readString(Path.of("docs/parser/opendataloader-parity-matrix.md"));

    assertThat(matrix).contains("| heading_hierarchy | HeadingProcessor |");
    assertThat(matrix).contains("| two_column_reading_order | TaggedDocumentProcessor |");
    assertThat(matrix).contains("| sidebar_reading_order | TaggedDocumentProcessor |");
    assertThat(matrix).contains("| text_noise_filtering | ContentFilterProcessor |");
    assertThat(matrix).contains("| bordered_tables | TableBorderProcessor |");
    assertThat(matrix).contains("| borderless_tables | ClusterTableProcessor |");

    assertThat(matrix).contains("Next Processor Work");
    assertThat(matrix).contains("HeadingProcessor");
    assertThat(matrix).contains("TaggedDocumentProcessor");
    assertThat(matrix).contains("TableStructureNormalizer");
}
```

**Step 2: Write failing Rust metadata contract**

Add a test to `opendataloader_parity_matrix_contract.rs`:

```rust
#[test]
fn next_processor_work_prioritizes_latest_full200_buckets() {
    let matrix = opendataloader_parity_matrix_json();
    let next = matrix["next_processor_work"]
        .as_array()
        .expect("next processor work");
    let names = next
        .iter()
        .filter_map(|entry| entry["processor"].as_str())
        .collect::<Vec<_>>();

    for expected in [
        "HeadingProcessor",
        "TaggedDocumentProcessor",
        "TableStructureNormalizer",
        "SpecialTableProcessor",
        "ContentFilterProcessor",
    ] {
        assert!(names.contains(&expected), "missing next work {expected}");
    }
}
```

**Step 3: Run tests to verify failure**

Run:

```bash
mvn -q -Dtest=OpenDataLoaderProcessorParityTest#latestLowScoreBucketsHaveProcessorOwnersAndNextActions test
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract next_processor_work_prioritizes_latest_full200_buckets -- --nocapture
```

Expected: FAIL because `Next Processor Work` / `next_processor_work` is missing.

**Step 4: Implement docs and metadata**

Add `next_processor_work()` to `opendataloader_parity.rs`.

Each row must include:

```json
{
  "processor": "HeadingProcessor",
  "bucket": "heading_hierarchy",
  "current_cases": 57,
  "current_metric": "mhs",
  "next_action": "port generalized heading hierarchy reconstruction before additional case repairs"
}
```

Use the latest full200 numbers:

```text
HeadingProcessor: heading_hierarchy 57
TaggedDocumentProcessor: two_column_reading_order/sidebar_reading_order 15
TableStructureNormalizer: table_structure 5
SpecialTableProcessor: table_false_positive_rejection/text_noise overlap 18
ContentFilterProcessor: text_noise_filtering 18
```

Add the same table to `docs/parser/opendataloader-parity-matrix.md`.

**Step 5: Run tests**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
mvn -q -Dtest=OpenDataLoaderProcessorParityTest test
git diff --check
```

Expected: PASS.

**Step 6: Commit**

```bash
git add runtime/doctruth-runtime/src/opendataloader_parity.rs \
  runtime/doctruth-runtime/tests/opendataloader_parity_matrix_contract.rs \
  src/test/java/ai/doctruth/opendataloader/OpenDataLoaderProcessorParityTest.java \
  docs/parser/opendataloader-parity-matrix.md
git commit -m "feat: prioritize opendataloader processor work"
```

## Task 3: Rehome Current Table Repairs Behind A Named Processor Pipeline

**Files:**
- Modify: `src/main/java/ai/doctruth/PdfDocumentParser.java`
- Modify: `src/test/java/ai/doctruth/PdfBorderlessTableExtractionTest.java`
- Modify: `docs/parser/opendataloader-processor-gap-report.md`

**Step 1: Write failing test**

Add this assertion to `PdfBorderlessTableExtractionTest` as a new test:

```java
@Test
@EnabledIf("hasOpenDataLoaderBench")
void opendataloaderTemporaryTableRepairsAreProcessorOwnedBehaviorFamilies() throws Exception {
    var document = parsePdfBox(opendataloaderBenchPdf("01030000000078"));
    var markdown = document.toMarkdownClean();

    assertThat(markdown).contains("Table 1.4. Growth in migrant remittance inflows");
    assertThat(markdown).contains("| Cambodia |");

    var report = Files.readString(Path.of("docs/parser/opendataloader-processor-gap-report.md"));
    assertThat(report).contains("temporary repair registry");
    assertThat(report).contains("TableStructureNormalizer");
    assertThat(report).contains("SpecialTableProcessor");
}
```

**Step 2: Run test to verify failure**

Run:

```bash
mvn -q -Dtest=PdfBorderlessTableExtractionTest#opendataloaderTemporaryTableRepairsAreProcessorOwnedBehaviorFamilies test
```

Expected: FAIL until docs mention the registry and processor ownership.

**Step 3: Extract named repair pipeline methods**

In `PdfDocumentParser.extractSections`, replace the deeply nested expression with named processor-pipeline methods:

```java
var merged = mergeTableContinuations(sections);
var tableFalsePositiveFiltered = applySpecialTableProcessorRepairs(merged);
var tableStructureNormalized = applyTableStructureNormalizerRepairs(tableFalsePositiveFiltered);
var clusterNormalized = applyClusterTableProcessorRepairs(tableStructureNormalized);
return new ExtractedSections(demoteNarrativeShardTables(clusterNormalized), List.copyOf(discarded));
```

Add private methods:

```java
private static List<ParsedSection> applySpecialTableProcessorRepairs(List<ParsedSection> sections) {
    return demoteChartAxisTables(sections);
}

private static List<ParsedSection> applyTableStructureNormalizerRepairs(List<ParsedSection> sections) {
    return promoteKinematicViscosityTables(promoteRemittanceGrowthTables(
            promoteInlineCationObservationTables(promoteBlankComparisonTables(
                    promoteNationalInitiativesTables(promoteEcoCompetenceFrameworkTables(sections))))));
}

private static List<ParsedSection> applyClusterTableProcessorRepairs(List<ParsedSection> sections) {
    return promoteAreaCompetenceTables(promotePortShipcallColumnStreamTables(
            promoteTrainingDatasetFragmentTables(sections)));
}
```

Do not change behavior in this task. This is a mechanical rehome so the current repairs have explicit processor owner boundaries.

**Step 4: Run tests**

Run:

```bash
mvn -q -Dtest=PdfBorderlessTableExtractionTest test
mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest test
git diff --check
```

Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/ai/doctruth/PdfDocumentParser.java \
  src/test/java/ai/doctruth/PdfBorderlessTableExtractionTest.java \
  docs/parser/opendataloader-processor-gap-report.md
git commit -m "refactor: rehome opendataloader table repairs"
```

## Task 4: Run Stage Gate And Update PR Evidence

**Files:**
- Modify only if needed: `docs/parser/opendataloader-processor-gap-report.md`

**Step 1: Run focused verification**

Run:

```bash
cargo fmt --manifest-path runtime/doctruth-runtime/Cargo.toml -- --check
cargo test --manifest-path runtime/doctruth-runtime/Cargo.toml --test opendataloader_parity_matrix_contract -- --nocapture
mvn -q -Dtest=OpenDataLoaderProcessorParityTest test
mvn -q -Dtest=PdfBorderlessTableExtractionTest test
mvn -q -Dtest=PdfDocumentParserTest,PdfVisualLayoutParserTest,PdfTwoColumnSemanticSectionTest test
mvn -B -ntp spotless:check checkstyle:check
git diff --check
```

Expected: PASS.

**Step 2: Run full200 stage gate**

Run:

```bash
DOCTRUTH_RUNTIME_BUILD_PROFILE=release \
DOCTRUTH_OPENDATALOADER_PRESET=auto \
sh scripts/run-opendataloader-java-core-parity.sh --full200
```

Expected:

```text
parsed 200/200
failed 0
production_residency.python_torch_docling false
```

The metrics must not materially regress from the latest accepted run:

```text
overall >= 0.795
nid >= 0.913
teds >= 0.781
mhs >= 0.495
```

If full200 changes only by metadata/refactor noise, record the new artifact path in the final response but do not claim new quality parity.

**Step 3: Run full Maven verification**

Run:

```bash
DOCTRUTH_RUNTIME_COMMAND=$PWD/runtime/doctruth-runtime/target/release/doctruth-runtime \
mvn -B -ntp verify -P recorded
```

Expected: PASS.

**Step 4: Push branch**

Run:

```bash
git push
gh pr view 13 --json number,state,mergeable,statusCheckRollup,url
```

Expected:

```text
PR #13 remains OPEN and MERGEABLE.
Checks are SUCCESS or running from the new push.
```

**Step 5: Commit if docs changed during verification**

If a report path or verification note is added:

```bash
git add docs/parser/opendataloader-processor-gap-report.md
git commit -m "docs: record opendataloader processor parity gate"
```

## Final Handoff

Report:

```text
- commits created
- tests run
- full200 artifact path and metrics
- whether PR #13 remains mergeable
- what is now aligned with the processor-parity plan
- what still remains for true OpenDataLoader parity
```
