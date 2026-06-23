use assert_cmd::Command;
use predicates::prelude::*;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

#[test]
fn opendataloader_parity_formats_section_heading_like_reference() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-heading-parity");
    let report = run_opendataloader_prediction("01030000000054", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000054.md")).unwrap();
    assert!(
        markdown.contains("# 2.1. Diesel and biodiesel use"),
        "expected OpenDataLoader-style section heading in markdown:\n{markdown}"
    );
    assert!(
        !markdown.contains("\n2.1.\nDiesel and biodiesel use\n"),
        "section heading should not remain split across plain lines:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_reconstructs_regular_tables_like_reference() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-table-parity");
    let report = run_opendataloader_prediction("01030000000083", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000083.md")).unwrap();
    assert!(
        markdown.contains("|Category|Number of clauses in Union laws|In percent|Number of clauses in State laws|In percent|"),
        "expected normalized markdown pipe table header:\n{markdown}"
    );
    assert!(
        markdown.contains("|Commercial|529|10.1%|817|3.9%|"),
        "expected complete first body row in normalized table:\n{markdown}"
    );
    assert!(
        !markdown.contains("<table>"),
        "regular OpenDataLoader-style tables should render as markdown pipe tables:\n{markdown}"
    );
}

#[test]
fn opendataloader_prediction_timeout_path_does_not_spawn_per_document_child() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-same-process-runner");
    let report = run_opendataloader_prediction("01030000000165", &output_dir);

    assert_eq!(report["prediction"]["documentCount"], 1);
    assert_eq!(report["prediction"]["failedCount"], 1);
    let errors = fs::read_to_string(output_dir.join("errors.json")).unwrap();
    assert!(
        !errors.contains("parse child exited"),
        "prediction runner should call parse_pdf in-process instead of spawning one child per PDF:\n{errors}"
    );
}

#[test]
fn opendataloader_parity_merges_stacked_caps_heading_like_reference() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-stacked-heading");
    let report = run_opendataloader_prediction("01030000000092", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000092.md")).unwrap();
    assert!(
        markdown.contains("# THE TEXTBOOK’S DIFFERENT LEVELS OF RIGOR"),
        "expected stacked all-caps heading to be merged like OpenDataLoader:\n{markdown}"
    );
    assert!(
        !markdown.contains("\nTHE\nTEXTBOOK’S\nDIFFERENT\nLEVELS\nOF\nRIGOR\n"),
        "stacked heading words should not remain separate plain lines:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_repairs_spaced_letter_and_fragmented_headings() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-spaced-heading");
    let report = run_opendataloader_prediction("01030000000163", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000163.md")).unwrap();
    assert!(
        markdown.contains("# HOW CAN YOU HELP?"),
        "letter-spaced heading should be collapsed:\n{markdown}"
    );
    assert!(
        markdown.contains("# FURTHER RESOURCES"),
        "fragmented adjacent heading words should be merged:\n{markdown}"
    );
    assert!(
        !markdown.contains("# H O W C A N Y O U H E L P ?"),
        "letter-spaced heading should not leak into markdown:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_promotes_standalone_question_headings() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-question-heading");
    let report = run_opendataloader_prediction("01030000000179", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000179.md")).unwrap();
    assert!(
        markdown.contains("# What tool(s) do you typically use in your course?"),
        "standalone tool question should be promoted:\n{markdown}"
    );
    assert!(
        markdown.contains("# What supporting materials do you utilize for this course?"),
        "standalone materials question should be promoted:\n{markdown}"
    );
    assert!(
        !markdown.contains("# Figure 12.2"),
        "figure captions should not be promoted as question headings:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_reconstructs_long_text_comparative_table() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-long-table");
    let report = run_opendataloader_prediction("01030000000088", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000088.md")).unwrap();
    assert!(
        markdown.contains("|Jurisdiction|GATS XVII Reservation (1994)|Foreign Ownership Permitted|Restrictions on Foreign Ownership|Foreign Ownership Reporting Requirements|"),
        "expected OpenDataLoader-style long text table header:\n{markdown}"
    );
    assert!(
        markdown.contains("|Argentina|Y|Y|Prohibition on ownership of property that contains or borders large and permanent bodies of water"),
        "expected Argentina row to be reconstructed as a table row:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "|Australia|N|Y|Approval is needed from the Treasurer if the acquisition constitutes"
        ),
        "expected Australia row to be reconstructed as a table row:\n{markdown}"
    );
    assert!(
        markdown.contains("|Austria|Y|Y|Prior authorization required with exceptions; authorization may be refused"),
        "expected Austria to remain a separate reconstructed row:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "|Brazil|Y|Y|Acquisition of rural property by an alien individual or company"
        ),
        "expected Brazil to remain a separate reconstructed row:\n{markdown}"
    );
    assert!(
        !markdown.contains("\\|Austria\\|"),
        "row separators must not be swallowed into Australia cell text:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_enriches_dense_table_cells_from_source_units() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-dense-table-unit-enrichment");
    let report = run_opendataloader_prediction("01030000000089", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000089.md")).unwrap();
    assert!(
        markdown.contains("|Canada|Y|Y|Prohibition on ownership of residential property with exceptions; some provinces"),
        "wide prose table cells should include continuation source units:\n{markdown}"
    );
    assert!(
        markdown.contains("|Chile|N|Y|Prohibition on acquisition of public lands within 10 kilometers from the border"),
        "multi-line dense table rows should be reconstructed from units:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_renders_table_of_contents_as_list() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-toc-list");
    let report = run_opendataloader_prediction("01030000000108", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000108.md")).unwrap();
    assert!(markdown.starts_with("# CONTENTS"), "{markdown}");
    assert!(
        markdown.contains("- Experiment #1: Hydrostatic Pressure 3"),
        "expected OpenDataLoader-style experiment list:\n{markdown}"
    );
    assert!(
        !markdown.starts_with("|About the Publisher|"),
        "table of contents should not be emitted as a pipe table:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_preserves_table_of_contents_heading_and_wrapped_items() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-toc-heading-wrap");
    let report = run_opendataloader_prediction("01030000000044", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000044.md")).unwrap();
    assert!(
        markdown.starts_with("# Table of Contents"),
        "TOC heading should be preserved instead of emitting a bare table:\n{markdown}"
    );
    assert!(
        markdown.contains("|Executive Summary|4|"),
        "first TOC row should not be dropped:\n{markdown}"
    );
    assert!(
        markdown.contains("|Political Parties, Candidates Registration and Election Campaign|18|"),
        "wrapped TOC item should be merged before table rendering:\n{markdown}"
    );
    assert!(
        !markdown.contains("|Campaign|18|"),
        "wrapped TOC continuation should not become a separate row:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_adds_heading_to_bare_contents_tables() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-bare-toc");
    let report = run_opendataloader_prediction("01030000000016", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000016.md")).unwrap();
    assert!(
        markdown.to_lowercase().starts_with("# table of contents"),
        "bare contents table should recover its heading:\n{markdown}"
    );
    assert!(
        markdown.contains("|Introduction|7|"),
        "contents rows should remain available as structured table rows:\n{markdown}"
    );
    assert!(
        markdown.contains("|Bibliography|139|"),
        "tail contents rows should be preserved:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_reconstructs_column_block_table() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-column-block-table");
    let report = run_opendataloader_prediction("01030000000178", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000178.md")).unwrap();
    assert!(
        markdown.contains("|Communication Channel|Medium|Examples|"),
        "expected column-block table header:\n{markdown}"
    );
    assert!(
        markdown.contains("|Direct communications|Physical or digital|meetings, consultations, listening sessions, email lists|"),
        "expected first reconstructed row:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "|Goodies|Primarily physical|pens, notepads, bookmarks, stickers, buttons, etc|"
        ),
        "expected final reconstructed row:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_reconstructs_two_column_reagents_table() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-reagents-table");
    let report = run_opendataloader_prediction("01030000000121", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000121.md")).unwrap();
    assert!(
        markdown.contains("|Reagents|Supplies and Equipment|"),
        "expected reagents/supplies table header:\n{markdown}"
    );
    assert!(
        markdown.contains("Resuspended DNA or ethanol precipitates from Part 1"),
        "expected reagent cell text:\n{markdown}"
    );
    assert!(
        markdown.contains("Microcentrifuge tube rack 3 1.5-mL microcentrifuge tubes"),
        "expected supplies cell text:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_reconstructs_blank_matrix_table() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-blank-matrix");
    let report = run_opendataloader_prediction("01030000000119", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000119.md")).unwrap();
    assert!(
        markdown.contains(
            "| |Mitosis Meiosis (begins with a single cell) (begins with a single cell)| |"
        ),
        "expected blank comparison matrix header:\n{markdown}"
    );
    assert!(
        markdown.contains("|# daughter cells produced| | |"),
        "expected blank matrix row to be preserved:\n{markdown}"
    );
    assert!(
        markdown.contains("|purpose| | |"),
        "expected purpose row to be preserved:\n{markdown}"
    );
}

#[test]
fn opendataloader_content_page_preserves_toc_case_00198() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-toc-case-00198");
    let report = run_opendataloader_prediction("01030000000198", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000198.md")).unwrap();
    assert_ne!(
        markdown.trim(),
        "6",
        "content page should not collapse to only the page number:\n{markdown}"
    );
    assert!(
        markdown.contains("Contents"),
        "expected Contents heading to be preserved:\n{markdown}"
    );
    for expected in [
        "1. Overview of OCR Pack",
        "2. Introduction of Product Services and Key Features",
        "3. Product - Detail Specification",
        "4. Integration Policy",
        "5. FAQ",
    ] {
        assert!(
            markdown.contains(expected),
            "expected TOC line `{expected}` to be preserved:\n{markdown}"
        );
    }
}

#[test]
fn opendataloader_matrix_table_preserves_descriptor_columns_case_00188() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-matrix-table-00188");
    let report = run_opendataloader_prediction("01030000000188", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000188.md")).unwrap();
    assert!(
        markdown
            .contains("|Model|Size|Type|H6 (Avg.)|ARC|HellaSwag|MMLU|TruthfulQA|Winogrande|GSM8K|"),
        "expected descriptor columns to remain in the matrix table header:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "|SOLAR 10.7B-Instruct|∼ 11B|Alignment-tuned|74.20|71.08|88.16|66.21|71.43|83.58|64.75|"
        ),
        "expected first model row to preserve Model/Size/Type cells before scores:\n{markdown}"
    );
}

#[test]
fn opendataloader_matrix_table_preserves_empty_boolean_cells_case_00189() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-matrix-table-00189");
    let report = run_opendataloader_prediction("01030000000189", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000189.md")).unwrap();
    assert!(
        markdown.contains(
            "|Model|Alpaca-GPT4|OpenOrca|Synth. Math-Instruct|H6 (Avg.)|ARC|HellaSwag|MMLU|TruthfulQA|Winogrande|GSM8K|"
        ),
        "expected boolean dataset columns to remain separate in the matrix table header:\n{markdown}"
    );
    assert!(
        markdown.contains("|SFT v1|O|✗|✗|69.15|67.66|86.03|65.88|60.12|82.95|52.24|"),
        "expected false boolean cells to stay aligned instead of collapsing gaps:\n{markdown}"
    );
    assert!(
        markdown.contains("|SFT v3 + v4|O|O|O|71.11|67.32|85.96|65.95|58.80|2.08|66.57|"),
        "expected merged-model row to preserve boolean and score columns:\n{markdown}"
    );
}

#[test]
fn opendataloader_matrix_table_reconstructs_later_dpo_tables_case_00189() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-dpo-tables-00189");
    let report = run_opendataloader_prediction("01030000000189", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000189.md")).unwrap();
    assert!(
        markdown.contains(
            "|Model|Ultrafeedback Clean|Synth. Math-Alignment|H6 (Avg.)|ARC|HellaSwag|MMLU|TruthfulQA|Winogrande|GSM8K|"
        ),
        "expected later DPO ablation table header to be reconstructed:\n{markdown}"
    );
    assert!(
        markdown.contains("|DPO v1|O|✗|73.06|71.42|88.49|66.14|72.04|81.45|58.83|"),
        "expected DPO v1 row to preserve boolean and score columns:\n{markdown}"
    );
    assert!(
        markdown.contains("|DPO v1 + v2|O|O|73.21|71.33|88.36|65.92|72.65|82.79|58.23|"),
        "expected merged DPO row to preserve boolean and score columns:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "|Model|SFT Base Model|H6 (Avg.)|ARC|HellaSwag|MMLU|TruthfulQA|Winogrande|GSM8K|"
        ),
        "expected base-model DPO ablation table header to be reconstructed:\n{markdown}"
    );
    assert!(
        markdown.contains("|DPO v3|SFT v3 + v4|73.58|71.33|88.08|65.39|72.45|81.93|62.32|"),
        "expected DPO v3 base-model row to preserve model and score columns:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_repairs_split_year_headers_and_empty_table_columns() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-year-table-repair");
    let report = run_opendataloader_prediction("01030000000127", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000127.md")).unwrap();
    assert!(
        markdown.contains("|Year|3-Year|5-Year|7-Year|"),
        "expected split Year header to be repaired and empty leading column removed:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "|Year|Recovery Rate|Unadjusted Basis|Depreciation Expense|Accumulated Depreciation|"
        ),
        "expected depreciation tables to drop empty spacer columns:\n{markdown}"
    );
    assert!(
        !markdown.contains("|ear|Y ear|"),
        "split glyph table header should not leak into markdown:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_does_not_render_prose_page_as_synthetic_table() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-prose-table-gate");
    let report = run_opendataloader_prediction("01030000000145", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000145.md")).unwrap();
    assert!(
        markdown.contains("# 4.1 Introduction"),
        "section heading should render as heading, not a table row:\n{markdown}"
    );
    assert!(
        !markdown.contains("|4.1|"),
        "ordinary prose page should not become a synthetic markdown table:\n{markdown}"
    );
    assert!(
        !markdown.contains("|The| |pressure|drop in a fluid|"),
        "multi-column prose fragments should stay prose:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_does_not_render_formula_prose_as_spatial_table_case_00144() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-formula-prose-00144");
    let report = run_opendataloader_prediction("01030000000144", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000144.md")).unwrap();
    assert!(
        markdown.contains("# 3.7.3 Formulae of higher accuracy from Richardson's extrapolation"),
        "formula subsection heading should survive as a heading:\n{markdown}"
    );
    assert!(
        markdown.contains("M-Q(h)") || markdown.contains("M - Q(h)") || markdown.contains("M −"),
        "expected numerical differentiation formula text to stay available:\n{markdown}"
    );
    assert!(
        !markdown.contains("|---|---|"),
        "formula prose should not become a synthetic markdown table:\n{markdown}"
    );
    assert!(
        !markdown.contains("|Inthisexampletheerrorestimateisveryreliable"),
        "formula prose should not be collapsed into table cells:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_promotes_activity_headings() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-activity-heading");
    let report = run_opendataloader_prediction("01030000000168", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000168.md")).unwrap();
    assert!(
        markdown.contains("# Activity 1: Determining pH With Indicator Strips (Field Method)"),
        "Activity heading should be promoted:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_promotes_short_title_headings() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-short-title-heading");
    let report = run_opendataloader_prediction("01030000000107", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000107.md")).unwrap();
    assert!(
        markdown.starts_with("# Print vs. Digital"),
        "short document title should be promoted:\n{markdown}"
    );
    assert!(
        !markdown.starts_with("Print vs. Digital\n"),
        "title should not remain plain text:\n{markdown}"
    );
}

#[test]
fn opendataloader_parity_repairs_split_glyph_words_in_paragraphs() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-split-glyphs");
    let report = run_opendataloader_prediction("01030000000101", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000101.md")).unwrap();
    assert!(
        markdown.contains("Vohs et al. (2006)"),
        "expected split author name to be repaired:\n{markdown}"
    );
    assert!(
        markdown.contains("behavioral psychology"),
        "expected split word to be repaired:\n{markdown}"
    );
    assert!(
        markdown.contains("# PRICE AND THE PLACEBO EFFECT"),
        "expected stacked heading merge to stay intact:\n{markdown}"
    );
}

#[test]
fn opendataloader_markdown_joins_wrapped_executive_summary_paragraph_case_00079() {
    let output_dir = temp_dir("doctruth-runtime-opendataloader-joined-paragraph-00079");
    let report = run_opendataloader_prediction("01030000000079", &output_dir);

    assert_eq!(report["prediction"]["parsedCount"], 1);
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000079.md")).unwrap();
    assert!(
        markdown.contains(
            "India suffers from ‘regulatory cholesterol’ that is getting in the way of doing business."
        ),
        "expected visually wrapped Executive Summary prose to be joined:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "since Independence, surviving three decades of economic reforms initiated in 1991. The biggest challenges come from"
        ),
        "expected year-ending sentence continuation to stay prose, not split heading/body lines:\n{markdown}"
    );
    assert!(
        markdown.contains(
            "of which 25,537 are at the Union level. These compliances need to be communicated"
        ),
        "expected uppercase line continuation inside a paragraph to be joined:\n{markdown}"
    );
}

#[test]
fn opendataloader_real_mnn_suppresses_raw_lines_after_reconstructed_table() {
    let Some((model_cache, model_manifest, model_worker)) = real_opendataloader_mnn_pack() else {
        eprintln!("skipping real MNN OpenDataLoader parity test; model pack or worker is missing");
        return;
    };
    let output_dir = temp_dir("doctruth-runtime-opendataloader-table-source-suppression");
    let report = run_opendataloader_prediction_with_real_mnn(
        "01030000000110",
        &output_dir,
        &model_cache,
        &model_manifest,
        &model_worker,
    );

    assert_eq!(report["prediction"]["parsedCount"], 1);
    assert_eq!(
        report["resourceProfile"]["modelRoutingCoverage"]["startedModelRuntime"],
        1
    );
    assert_eq!(report["resourceProfile"]["modelRuntime"]["runtime"], "mnn");
    let markdown = fs::read_to_string(output_dir.join("markdown/01030000000110.md")).unwrap();
    assert!(
        markdown.contains(
            "|Temperature (degree C)|Kinematic viscosity v (m2/s)|Temperature (degree C)|"
        ),
        "expected reconstructed viscosity table:\n{markdown}"
    );
    let table_header = markdown
        .find("|Temperature (degree C)|Kinematic viscosity")
        .expect("table header should be present");
    let after_table = &markdown[table_header..];
    assert!(
        !after_table.contains("\nKinematic viscosity v (m2/s)\n\nKinematic viscosity v"),
        "OpenDataLoader removes table-owned source text after building the table:\n{markdown}"
    );
    assert!(
        !after_table.contains("table projected row header"),
        "model structure labels should not leak into markdown:\n{markdown}"
    );
}

#[test]
fn benchmark_corpus_runs_labeled_manifest_and_reports_metrics() {
    let root = temp_dir("doctruth-runtime-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    write_opendataloader_evaluation(&root);
    fs::write(&manifest, benchmark_manifest_with_external()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(report["runtime"], "doctruth-runtime");
    assert_eq!(report["corpus"], "rust-parser-accuracy-seed");
    assert_eq!(report["kind"], "human-labeled");
    assert_eq!(report["qualityProfile"], "parser-accuracy");
    assert_eq!(report["reviewType"], "generated-seed");
    assert_eq!(report["passed"], true);
    assert_eq!(report["metrics"]["reading_order_f1"], 1.0);
    assert_eq!(report["metrics"]["quote_anchor_accuracy"], 1.0);
    assert_eq!(report["metrics"]["bbox_coverage"], 1.0);
    assert_eq!(report["cases"][0]["labelId"], "rust-seed-v1-0001");
    assert_eq!(report["cases"][0]["tags"], json!(["multi-layout"]));
    assert_eq!(report["cases"][0]["metrics"]["reading_order_f1"], 1.0);
    assert_eq!(report["resourceProfile"]["profile"], "edge-model");
    assert_eq!(report["resourceProfile"]["modelRuntime"], Value::Null);
    assert_eq!(
        report["resourceProfile"]["pythonTorchDoclingProductionResidency"],
        false
    );
    assert_eq!(report["resourceProfile"]["caseCount"], 1);
    assert!(
        report["resourceProfile"]["elapsedMs"]
            .as_f64()
            .unwrap_or(0.0)
            >= 0.0,
        "{report}"
    );
    assert_eq!(
        report["resourceProfile"]["memory"]["measurement"],
        "process-rss"
    );
    assert_eq!(report["cases"][0]["runtimeProfile"], "edge-model");
    assert!(
        report["cases"][0]["elapsedMs"].as_f64().unwrap_or(0.0) >= 0.0,
        "{report}"
    );
    assert_eq!(report["cases"][0]["memory"]["measurement"], "process-rss");
}

#[test]
fn benchmark_corpus_writes_recorded_report_artifact() {
    let root = temp_dir("doctruth-runtime-corpus-report");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    write_opendataloader_evaluation(&root);
    fs::write(&manifest, benchmark_manifest_with_external()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let stdout_report: Value = serde_json::from_slice(&output).unwrap();
    let recorded: Value = serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    assert_eq!(
        recorded["reportFormat"],
        "doctruth.parser-benchmark.report.v1"
    );
    assert!(
        recorded["manifest"]
            .as_str()
            .unwrap()
            .ends_with("corpus.json")
    );
    assert!(
        recorded["manifestSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(recorded["caseCount"], 1);
    assert_eq!(recorded["casesPerTag"]["multi-layout"], 1);
    assert_eq!(recorded["minCasesPerTag"]["multi-layout"], 1);
    assert_eq!(recorded["casesPerFixtureType"]["two-column"], 1);
    assert_eq!(recorded["fixtureCoverageRequired"]["scanned-ocr"], 1);
    assert_eq!(recorded["fixtureCoverageSatisfied"]["invoice"], true);
    assert_eq!(recorded["fixtureResults"]["invoice"]["caseCount"], 1);
    assert_eq!(recorded["fixtureResults"]["invoice"]["passed"], true);
    assert_eq!(
        recorded["fixtureResults"]["invoice"]["metrics"]["reading_order_f1"],
        1.0
    );
    assert_eq!(
        recorded["fixtureResults"]["invoice"]["cases"],
        json!(["rust-multi-layout"])
    );
    assert_eq!(recorded["resourceProfile"]["profile"], "edge-model");
    assert_eq!(
        recorded["resourceProfile"]["budgetStatus"],
        "profile-baseline-pending"
    );
    assert_eq!(recorded["cases"][0]["runtimeProfile"], "edge-model");
    assert_eq!(recorded["cases"][0]["memory"]["measurement"], "process-rss");
    assert_eq!(recorded["casesPerBehavior"]["xy-cut-edge"], 1);
    assert_eq!(
        recorded["behaviorCoverageRequired"]["structure-tree-preference"],
        1
    );
    assert_eq!(
        recorded["behaviorCoverageSatisfied"]["table-cluster-heuristics"],
        true
    );
    assert_eq!(recorded["coverageRequired"]["multi-layout"], 1);
    assert_eq!(recorded["coverageSatisfied"]["multi-layout"], true);
    assert_eq!(recorded["validityInputs"]["sourceHashes"], true);
    assert_eq!(recorded["validityInputs"]["manifestHash"], true);
    assert_eq!(recorded["validityInputs"]["parserConfig"], "TrustDocument");
    assert_eq!(
        recorded["validityInputs"]["modelCacheManifest"],
        "not-required"
    );
    assert_eq!(recorded["validityInputs"]["thresholds"], true);
    assert_eq!(recorded["validityInputs"]["expectedLabels"], true);
    assert_eq!(recorded["validityInputs"]["actualTrustDocument"], true);
    assert_eq!(recorded["minimums"]["reading_order_f1"], 1.0);
    assert!(recorded["maximums"].is_object());
    assert_eq!(recorded["metrics"]["opendataloader_nid"], 0.91);
    assert_eq!(recorded["metrics"]["opendataloader_teds"], 0.52);
    assert_eq!(recorded["metrics"]["opendataloader_mhs"], 0.76);
    assert_eq!(recorded["metrics"]["opendataloader_speed"], 0.015);
    assert!(
        recorded["externalMetrics"]["opendataloader"]["evaluationSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(recorded["runtime"], "doctruth-runtime");
    assert_eq!(recorded["corpus"], stdout_report["corpus"]);
    assert_eq!(recorded["qualityProfile"], "parser-accuracy");
    assert_eq!(recorded["reviewType"], "generated-seed");
    assert_eq!(recorded["cases"][0]["labelId"], "rust-seed-v1-0001");
    assert!(
        recorded["cases"][0]["sourceSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(recorded["cases"][0]["replay"]["sourceRefReplayable"], true);
    assert!(recorded["cases"][0]["actualTrustDocument"].is_object());
    assert!(
        recorded["cases"][0]["actualTrustDocumentSha256"]
            .as_str()
            .unwrap()
            .starts_with("sha256:")
    );
    assert_eq!(
        recorded["cases"][0]["fixtureTypes"],
        json!([
            "simple-single-column",
            "two-column",
            "sidebar-resume",
            "table",
            "borderless-table",
            "scanned-ocr",
            "invoice",
            "mixed-layout"
        ])
    );
    assert_eq!(
        recorded["cases"][0]["behaviors"],
        json!([
            "xy-cut-edge",
            "safety-filter",
            "structure-tree-preference",
            "table-cluster-heuristics"
        ])
    );
    assert_eq!(recorded["cases"][0]["replay"]["quoteReplayable"], true);
    assert_eq!(
        recorded["cases"][0]["replay"]["evidenceSpanReplayable"],
        true
    );
}

#[test]
fn verify_benchmark_report_accepts_recorded_report_artifact() {
    let root = temp_dir("doctruth-runtime-report-verify");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let verified: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(verified["verified"], true);
    assert_eq!(
        verified["reportFormat"],
        "doctruth.parser-benchmark.report.v1"
    );
    assert_eq!(verified["caseCount"], 1);
}

#[test]
fn benchmark_corpus_exports_opendataloader_prediction_artifacts() {
    let root = temp_dir("doctruth-runtime-opendataloader-prediction");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let prediction = root.join("prediction/doctruth");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "opendataloader_prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let report: Value = serde_json::from_slice(&output).unwrap();

    let markdown = prediction.join("markdown/rust-seed-v1-0001.md");
    assert!(markdown.is_file());
    assert!(
        fs::read_to_string(markdown)
            .unwrap()
            .contains("Rust corpus evidence.")
    );
    let summary: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(summary["engine_name"], "doctruth");
    assert_eq!(summary["document_count"], 1);
    assert_eq!(summary["runtime_contract"], "TrustDocument");
    assert_eq!(summary["runtime_profile"], "edge-model");
    assert_eq!(summary["parsed_count"], 1);
    assert_eq!(summary["failed_count"], 0);
    assert_eq!(
        summary["production_residency"]["python_torch_docling"],
        false
    );
    assert_eq!(summary["documents"][0]["document_id"], "rust-seed-v1-0001");
    assert_eq!(summary["documents"][0]["status"], "parsed");
    assert_eq!(summary["documents"][0]["runtimeProfile"], "edge-model");
    assert_eq!(
        summary["documents"][0]["modelRouting"]["route"],
        "deterministic-only"
    );
    assert!(summary["documents"][0]["modelRuntime"].is_null());
    let errors: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("errors.json")).unwrap()).unwrap();
    assert_eq!(errors["documents"], json!([]));
    assert_eq!(
        report["externalArtifacts"]["opendataloaderPrediction"]["engine"],
        "doctruth"
    );
}

#[test]
fn opendataloader_prediction_command_writes_artifacts_from_bench_pdf_dir() {
    let root = temp_dir("doctruth-runtime-opendataloader-direct");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-direct");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("doc-b.pdf"),
        minimal_pdf("Second document evidence."),
    )
    .unwrap();
    fs::write(
        pdf_dir.join("doc-a.pdf"),
        minimal_pdf("First document evidence."),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": root,
                "engine": "doctruth-direct",
                "limit": 1,
                "preset": "lite",
                "runtime_profile": "edge-fast",
                "output_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["runtime"], "doctruth-runtime");
    assert_eq!(report["engine"], "doctruth-direct");
    assert_eq!(report["prediction"]["documentCount"], 1);
    assert_eq!(report["prediction"]["failedCount"], 0);

    let markdown = prediction.join("markdown/doc-a.md");
    assert!(markdown.is_file());
    assert!(
        fs::read_to_string(markdown)
            .unwrap()
            .contains("First document evidence.")
    );
    assert!(!prediction.join("markdown/doc-b.md").exists());

    let summary: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(summary["engine_name"], "doctruth-direct");
    assert_eq!(summary["runtime_contract"], "TrustDocument");
    assert_eq!(summary["runtime_profile"], "edge-fast");
    assert_eq!(summary["document_count"], 1);
    assert_eq!(summary["parsed_count"], 1);
    assert_eq!(summary["failed_count"], 0);
    assert_eq!(summary["documents"][0]["document_id"], "doc-a");
    assert_eq!(summary["documents"][0]["runtimeProfile"], "edge-fast");

    let errors: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("errors.json")).unwrap()).unwrap();
    assert_eq!(errors["documents"], json!([]));
}

#[test]
fn opendataloader_full200_gate_rejects_unbounded_prediction_without_explicit_allow() {
    let root = temp_dir("doctruth-runtime-opendataloader-full200-gate");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-direct");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("doc-a.pdf"),
        minimal_pdf("First document evidence."),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.write_stdin(
        json!({
            "command": "opendataloader_prediction",
            "bench_dir": root,
            "engine": "doctruth-direct",
            "preset": "lite",
            "runtime_profile": "edge-fast",
            "output_dir": prediction
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("FULL200_REQUIRES_EXPLICIT_ALLOW"))
    .stderr(predicate::str::contains(
        "Set allow_full200=true to run the full OpenDataLoader Bench corpus",
    ));
}

#[test]
fn opendataloader_full200_gate_allows_explicit_unbounded_prediction_request() {
    let root = temp_dir("doctruth-runtime-opendataloader-full200-allow");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-direct");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("doc-a.pdf"),
        minimal_pdf("First document evidence."),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": root,
                "engine": "doctruth-direct",
                "preset": "lite",
                "runtime_profile": "edge-fast",
                "output_dir": prediction,
                "allow_full200": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["prediction"]["documentCount"], 1);
    assert_eq!(report["prediction"]["failedCount"], 0);
}

#[test]
fn opendataloader_prediction_summary_counts_blocked_model_runtime_routes() {
    let root = temp_dir("doctruth-runtime-opendataloader-model-coverage");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-model-coverage");
    let (cache_dir, manifest) = ready_mnn_model_manifest();
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("table-doc.pdf"),
        minimal_pdf("Item Qty Price\nA 2 10\nB 4 20\nTotal 6 30"),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.env("DOCTRUTH_MODEL_CACHE", cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", manifest)
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": root,
                "engine": "doctruth-model-coverage",
                "preset": "auto",
                "runtime_profile": "edge-model",
                "limit": 1,
                "output_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success();

    let summary: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(summary["model_routing_coverage"]["documentCount"], 1);
    assert_eq!(summary["model_routing_coverage"]["requiresModelRuntime"], 1);
    assert_eq!(summary["model_routing_coverage"]["startedModelRuntime"], 0);
    assert_eq!(summary["model_routing_coverage"]["blockedModelRuntime"], 1);
    assert_eq!(
        summary["model_routing_coverage"]["routes"]["table-model"],
        1
    );
    assert_eq!(
        summary["documents"][0]["modelRouting"]["blockedReason"],
        "model-runtime-unavailable"
    );
}

#[test]
fn opendataloader_prediction_accepts_request_model_runtime_paths() {
    let root = temp_dir("doctruth-runtime-opendataloader-request-model-runtime");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-request-model-runtime");
    let worker = write_fake_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest();
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("table-doc.pdf"),
        minimal_pdf("Item Qty Price\nA 2 10\nB 4 20\nTotal 6 30"),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.write_stdin(
        json!({
            "command": "opendataloader_prediction",
            "bench_dir": root,
            "engine": "doctruth-request-model-runtime",
            "preset": "table-lite",
            "runtime_profile": "edge-model",
            "model_manifest": manifest,
            "model_cache": cache_dir,
            "model_worker": worker,
            "limit": 1,
            "output_dir": prediction
        })
        .to_string(),
    )
    .assert()
    .success();

    let summary: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(summary["model_routing_coverage"]["documentCount"], 1);
    assert_eq!(summary["model_routing_coverage"]["requiresModelRuntime"], 1);
    assert_eq!(summary["model_routing_coverage"]["startedModelRuntime"], 1);
    assert_eq!(summary["model_routing_coverage"]["blockedModelRuntime"], 0);
    assert_eq!(
        summary["documents"][0]["modelRouting"]["route"],
        "model-runtime"
    );
    assert_eq!(summary["documents"][0]["modelRuntime"]["runtime"], "mnn");
    let markdown = fs::read_to_string(prediction.join("markdown/table-doc.md")).unwrap();
    assert!(
        markdown.contains("Worker corpus evidence."),
        "request-scoped model worker output should be used:\n{markdown}"
    );
    assert!(
        markdown.contains("A 2 10"),
        "table model output should be hybrid-merged with deterministic text-layer markdown:\n{markdown}"
    );
}

#[test]
fn opendataloader_prediction_command_records_per_document_timeout() {
    let root = temp_dir("doctruth-runtime-opendataloader-timeout");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-timeout");
    let worker = write_slow_model_worker();
    let (cache_dir, manifest) = ready_mnn_model_manifest();
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("slow-doc.pdf"),
        minimal_pdf("Slow model evidence."),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", worker)
        .env("DOCTRUTH_MODEL_CACHE", cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", manifest)
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": root,
                "engine": "doctruth-timeout",
                "preset": "table-lite",
                "runtime_profile": "edge-model",
                "timeout_seconds": 0.05,
                "limit": 1,
                "output_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["prediction"]["documentCount"], 1);
    assert_eq!(report["prediction"]["failedCount"], 1);

    let markdown = prediction.join("markdown/slow-doc.md");
    assert!(markdown.is_file());
    assert_eq!(fs::read_to_string(markdown).unwrap(), "");

    let summary: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("summary.json")).unwrap())
            .unwrap();
    assert_eq!(summary["timeout_seconds"], 0.05);
    assert_eq!(summary["parsed_count"], 0);
    assert_eq!(summary["failed_count"], 1);
    assert_eq!(summary["documents"][0]["status"], "failed");
    assert_eq!(summary["documents"][0]["errorCode"], "PARSE_TIMEOUT");
    assert_eq!(summary["documents"][0]["runtimeProfile"], "edge-model");

    let errors: Value =
        serde_json::from_str(&fs::read_to_string(prediction.join("errors.json")).unwrap()).unwrap();
    assert_eq!(errors["documents"][0]["document_id"], "slow-doc");
    assert_eq!(errors["documents"][0]["errorCode"], "PARSE_TIMEOUT");
}

#[test]
fn opendataloader_prediction_timeout_path_handles_large_trust_document_stdout() {
    let root = temp_dir("doctruth-runtime-opendataloader-large-stdout");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-large-stdout");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::copy(
        vendored_opendataloader_pdf("01030000000146.pdf"),
        pdf_dir.join("large-doc.pdf"),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": root,
                "engine": "doctruth-large-stdout",
                "preset": "lite",
                "runtime_profile": "edge-fast",
                "timeout_seconds": 10.0,
                "limit": 1,
                "output_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["prediction"]["documentCount"], 1);
    assert_eq!(report["prediction"]["failedCount"], 0);
    assert!(
        fs::read_to_string(prediction.join("markdown/large-doc.md"))
            .unwrap()
            .contains("Reference frameworks")
    );
}

#[test]
fn opendataloader_prediction_renders_trust_document_tables_as_html() {
    let root = temp_dir("doctruth-runtime-opendataloader-table-markdown");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-table-markdown");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::copy(
        vendored_opendataloader_pdf("01030000000047.pdf"),
        pdf_dir.join("party-table.pdf"),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    cmd.write_stdin(
        json!({
            "command": "opendataloader_prediction",
            "bench_dir": root,
            "engine": "doctruth-table-markdown",
            "preset": "lite",
            "runtime_profile": "edge-fast",
            "timeout_seconds": 10.0,
            "limit": 1,
            "output_dir": prediction
        })
        .to_string(),
    )
    .assert()
    .success();

    let markdown = fs::read_to_string(prediction.join("markdown/party-table.md")).unwrap();
    assert!(markdown.contains("<table>"), "{markdown}");
    assert!(
        markdown.contains("<td rowspan=\"2\">Political party</td>"),
        "{markdown}"
    );
    assert!(!markdown.contains("\nNo.\nPolitical party\n11\n12\n13\n"));
}

#[test]
fn opendataloader_prediction_command_imports_evaluator_metrics_for_promotion_report() {
    let root = temp_dir("doctruth-runtime-opendataloader-direct-promotion");
    let pdf_dir = root.join("pdfs");
    let prediction = root.join("prediction/doctruth-direct");
    fs::create_dir_all(&pdf_dir).unwrap();
    fs::write(
        pdf_dir.join("doc-a.pdf"),
        minimal_pdf("First document evidence."),
    )
    .unwrap();
    write_high_quality_opendataloader_evaluation(&root);

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": root,
                "engine": "doctruth-direct",
                "doc_id": "doc-a",
                "preset": "lite",
                "runtime_profile": "edge-fast",
                "output_dir": prediction,
                "opendataloader_evaluation": "opendataloader-evaluation.json",
                "promotionGates": {
                    "mnn": {
                        "heavyOracleSteadyRssMb": 1400,
                        "qualityMinimums": {
                            "overall": 0.88,
                            "nid": 0.91,
                            "teds": 0.88,
                            "mhs": 0.78
                        }
                    }
                }
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["externalMetrics"]["opendataloader"]["nid"], 0.93);
    assert_eq!(report["metrics"]["opendataloader_teds"], 0.90);
    assert_eq!(report["mnnPromotion"]["evaluated"], true);
    assert_eq!(report["mnnPromotion"]["quality"]["passed"], true);
    assert_eq!(report["mnnPromotion"]["quality"]["overall"], 0.91);
    assert_eq!(report["mnnPromotion"]["resources"]["passed"], false);
    assert_eq!(
        report["mnnPromotion"]["resources"]["modelRuntimePresent"],
        false
    );
}

#[test]
fn opendataloader_promotion_report_uses_existing_prediction_summary_without_reparse() {
    let root = temp_dir("doctruth-runtime-opendataloader-report");
    let prediction = root.join("prediction/doctruth-direct");
    fs::create_dir_all(&prediction).unwrap();
    fs::write(
        prediction.join("summary.json"),
        json!({
            "engine_name": "doctruth-direct",
            "runtime_contract": "TrustDocument",
            "runtime_profile": "edge-model",
            "document_count": 1,
            "parsed_count": 1,
            "failed_count": 0,
            "total_elapsed": 12.0,
            "elapsed_per_doc": 12.0,
            "production_residency": {"python_torch_docling": false},
            "documents": [{
                "document_id": "doc-a",
                "status": "parsed",
                "elapsed": 12.0,
                "markdown_path": "prediction/doctruth-direct/markdown/doc-a.md",
                "error": null,
                "runtimeProfile": "edge-model",
                "modelRuntime": {
                    "runtime": "mnn",
                    "coldStartMs": 8.0,
                    "inferenceMs": 3.0,
                    "peakMemoryMb": 202,
                    "loadedModels": ["slanet-plus:v1"]
                },
                "modelRouting": {
                    "route": "table-model",
                    "startedModelRuntime": true
                }
            }]
        })
        .to_string(),
    )
    .unwrap();
    write_high_quality_opendataloader_evaluation(&root);

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_promotion_report",
                "prediction_dir": prediction,
                "opendataloader_evaluation": root.join("opendataloader-evaluation.json"),
                "promotionGates": {
                    "mnn": {
                        "heavyOracleSteadyRssMb": 1400,
                        "qualityMinimums": {
                            "overall": 0.88,
                            "nid": 0.91,
                            "teds": 0.88,
                            "mhs": 0.78
                        }
                    }
                }
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["prediction"]["engine"], "doctruth-direct");
    assert_eq!(report["prediction"]["documentCount"], 1);
    assert_eq!(report["metrics"]["opendataloader_nid"], 0.93);
    assert_eq!(
        report["resourceProfile"]["modelRuntime"]["peakMemoryMb"],
        202
    );
    assert_eq!(report["mnnPromotion"]["evaluated"], true);
    assert_eq!(report["mnnPromotion"]["accepted"], true);
    assert_eq!(
        report["mnnPromotion"]["resources"]["modelRuntimePresent"],
        true
    );
}

#[test]
fn opendataloader_promotion_report_blocks_when_model_routes_were_not_started() {
    let root = temp_dir("doctruth-runtime-opendataloader-report-blocked-models");
    let prediction = root.join("prediction/doctruth-direct");
    fs::create_dir_all(&prediction).unwrap();
    fs::write(
        prediction.join("summary.json"),
        json!({
            "engine_name": "doctruth-direct",
            "runtime_contract": "TrustDocument",
            "runtime_profile": "edge-model",
            "document_count": 2,
            "parsed_count": 2,
            "failed_count": 0,
            "total_elapsed": 20.0,
            "elapsed_per_doc": 10.0,
            "production_residency": {"python_torch_docling": false},
            "model_routing_coverage": {
                "documentCount": 2,
                "requiresModelRuntime": 2,
                "startedModelRuntime": 1,
                "blockedModelRuntime": 1,
                "routes": {"table-model": 2},
                "blockedReasons": {"model-runtime-unavailable": 1}
            },
            "documents": [{
                "document_id": "doc-a",
                "status": "parsed",
                "elapsed": 12.0,
                "markdown_path": "prediction/doctruth-direct/markdown/doc-a.md",
                "error": null,
                "runtimeProfile": "edge-model",
                "modelRuntime": {
                    "runtime": "mnn",
                    "coldStartMs": 8.0,
                    "inferenceMs": 3.0,
                    "peakMemoryMb": 202,
                    "loadedModels": ["slanet-plus:v1"]
                },
                "modelRouting": {
                    "route": "table-model",
                    "requiresModelRuntime": true,
                    "startedModelRuntime": true
                }
            }, {
                "document_id": "doc-b",
                "status": "parsed",
                "elapsed": 8.0,
                "markdown_path": "prediction/doctruth-direct/markdown/doc-b.md",
                "error": null,
                "runtimeProfile": "edge-model",
                "modelRuntime": null,
                "modelRouting": {
                    "route": "table-model",
                    "requiresModelRuntime": true,
                    "startedModelRuntime": false,
                    "blockedReason": "model-runtime-unavailable"
                }
            }]
        })
        .to_string(),
    )
    .unwrap();
    write_high_quality_opendataloader_evaluation(&root);

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_promotion_report",
                "prediction_dir": prediction,
                "opendataloader_evaluation": root.join("opendataloader-evaluation.json"),
                "promotionGates": {
                    "mnn": {
                        "heavyOracleSteadyRssMb": 1400,
                        "qualityMinimums": {
                            "overall": 0.88,
                            "nid": 0.91,
                            "teds": 0.88,
                            "mhs": 0.78
                        }
                    }
                }
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let report: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(report["mnnPromotion"]["accepted"], false);
    assert_eq!(report["mnnPromotion"]["quality"]["passed"], true);
    assert_eq!(
        report["mnnPromotion"]["resources"]["modelRuntimePresent"],
        true
    );
    assert_eq!(
        report["mnnPromotion"]["resources"]["blockedModelRuntime"],
        1
    );
    assert_eq!(
        report["mnnPromotion"]["resources"]["allRequiredRoutesStarted"],
        false
    );
}

#[test]
fn opendataloader_evaluate_prediction_writes_rust_evaluation_without_python() {
    let root = temp_dir("doctruth-runtime-opendataloader-evaluator");
    let gt = root.join("ground-truth/markdown");
    let prediction = root.join("prediction/doctruth-rust-eval");
    let markdown = prediction.join("markdown");
    fs::create_dir_all(&gt).unwrap();
    fs::create_dir_all(&markdown).unwrap();
    fs::write(
        gt.join("doc-a.md"),
        "# Title\n\nAlpha paragraph.\n\n<table><tr><td>A</td></tr></table>\n",
    )
    .unwrap();
    fs::write(
        markdown.join("doc-a.md"),
        "# Title\n\nAlpha paragraph.\n\n<table><tr><td>A</td></tr></table>\n",
    )
    .unwrap();
    fs::write(gt.join("doc-b.md"), "# Missing\n\nBeta paragraph.\n").unwrap();
    fs::write(
        prediction.join("summary.json"),
        json!({
            "engine_name": "doctruth-rust-eval",
            "parsed_count": 1,
            "failed_count": 0
        })
        .to_string(),
    )
    .unwrap();

    let output_path = prediction.join("evaluation-rust.json");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_evaluate_prediction",
                "ground_truth_dir": gt,
                "prediction_dir": prediction,
                "output_path": output_path
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["summary"]["engine_name"], "doctruth-rust-eval");
    assert_eq!(report["metrics"]["score"]["nid_mean"], 0.5);
    assert_eq!(report["metrics"]["score"]["teds_mean"], 1.0);
    assert_eq!(report["metrics"]["score"]["mhs_mean"], 0.5);
    assert_eq!(report["metrics"]["missing_predictions"], 1);
    assert_eq!(report["documents"][0]["scores"]["overall"], 1.0);
    assert_eq!(report["documents"][0]["prediction_available"], true);
    assert_eq!(report["documents"][1]["scores"]["overall"], 0.0);
    assert_eq!(report["documents"][1]["prediction_available"], false);
    assert!(output_path.is_file());
}

#[test]
fn opendataloader_comparison_reports_missing_inputs_as_success_json() {
    let root = temp_dir("doctruth-runtime-opendataloader-comparison-missing");
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

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["ok"], false);
    assert_eq!(report["status"], "error");
    assert_eq!(report["error_code"], "COMPARISON_INPUT_MISSING");
}

#[test]
fn opendataloader_comparison_reports_deltas_and_bottom_regressions() {
    let root = temp_dir("doctruth-runtime-opendataloader-comparison");
    fs::create_dir_all(&root).unwrap();
    let reference = root.join("reference-evaluation.json");
    let candidate = root.join("candidate-evaluation.json");
    fs::write(
        &reference,
        json!({
            "metrics": {
                "score": {
                    "overall_mean": 0.80,
                    "nid_mean": 0.90,
                    "teds_mean": 0.70,
                    "mhs_mean": 0.80
                }
            },
            "documents": [{
                "document_id": "doc-a",
                "scores": {"overall": 0.90, "nid": 1.0, "teds": 0.80, "mhs": 0.90}
            }, {
                "document_id": "doc-b",
                "scores": {"overall": 0.70, "nid": 0.80, "teds": 0.60, "mhs": 0.70}
            }, {
                "document_id": "doc-ref-only",
                "scores": {"overall": 0.20, "nid": 0.20, "teds": 0.20, "mhs": 0.20}
            }]
        })
        .to_string(),
    )
    .unwrap();
    fs::write(
        &candidate,
        json!({
            "metrics": {
                "score": {
                    "overall_mean": 0.75,
                    "nid_mean": 0.91,
                    "teds_mean": 0.60,
                    "mhs_mean": 0.74
                }
            },
            "documents": [{
                "document_id": "doc-a",
                "scores": {"overall": 0.65, "nid": 0.92, "teds": 0.50, "mhs": 0.53}
            }, {
                "document_id": "doc-b",
                "scores": {"overall": 0.74, "nid": 0.82, "teds": 0.70, "mhs": 0.70}
            }, {
                "document_id": "doc-candidate-only",
                "scores": {"overall": 0.99, "nid": 0.99, "teds": 0.99, "mhs": 0.99}
            }]
        })
        .to_string(),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_compare_reports",
                "reference_evaluation": reference,
                "candidate_evaluation": candidate
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["reference"]["overall"], 0.80);
    assert_eq!(report["candidate"]["teds"], 0.60);
    assert_eq!(report["delta"]["overall"], -0.05);
    assert_eq!(report["delta"]["nid"], 0.01);
    assert_eq!(report["delta"]["teds"], -0.10);
    assert_eq!(report["bottomRegressionCases"][0]["document_id"], "doc-a");
    assert_eq!(
        report["bottomRegressionCases"][0]["delta"]["overall"],
        -0.25
    );
    assert_eq!(report["coverage"]["comparedCount"], 2);
    assert_eq!(report["coverage"]["referenceOnlyCount"], 1);
    assert_eq!(report["coverage"]["candidateOnlyCount"], 1);
    assert_eq!(
        report["coverage"]["referenceOnlyDocumentIds"],
        json!(["doc-ref-only"])
    );
    assert_eq!(
        report["coverage"]["candidateOnlyDocumentIds"],
        json!(["doc-candidate-only"])
    );
    assert_eq!(report["bottomRegressionCases"].as_array().unwrap().len(), 1);
}

#[test]
fn opendataloader_evaluator_matches_upstream_heading_and_table_normalization() {
    let root = temp_dir("doctruth-runtime-opendataloader-evaluator-normalization");
    let gt = root.join("ground-truth/markdown");
    let prediction = root.join("prediction/doctruth-rust-eval");
    let markdown = prediction.join("markdown");
    fs::create_dir_all(&gt).unwrap();
    fs::create_dir_all(&markdown).unwrap();
    fs::write(gt.join("heading.md"), "# Same Heading\n\nBody.\n").unwrap();
    fs::write(markdown.join("heading.md"), "### Same Heading\n\nBody.\n").unwrap();
    fs::write(
        gt.join("table.md"),
        "<table><thead><tr><th>Name</th></tr></thead><tbody><tr><td>Ada</td></tr></tbody></table>\n",
    )
    .unwrap();
    fs::write(
        markdown.join("table.md"),
        "<table><tr><td>Name</td></tr><tr><td>Ada</td></tr></table>\n",
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_evaluate_prediction",
                "ground_truth_dir": gt,
                "prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["metrics"]["score"]["mhs_mean"], 1.0);
    assert_eq!(report["metrics"]["score"]["mhs_s_mean"], 1.0);
    assert_eq!(report["metrics"]["score"]["teds_mean"], 1.0);
    assert_eq!(report["metrics"]["score"]["teds_s_mean"], 1.0);
}

#[test]
fn opendataloader_evaluator_mhs_scores_content_separately_from_structure() {
    let root = temp_dir("doctruth-runtime-opendataloader-evaluator-mhs-content");
    let gt = root.join("ground-truth/markdown");
    let prediction = root.join("prediction/doctruth-rust-eval");
    let markdown = prediction.join("markdown");
    fs::create_dir_all(&gt).unwrap();
    fs::create_dir_all(&markdown).unwrap();
    fs::write(
        gt.join("doc.md"),
        "# Profile\n\nAlpha paragraph.\n\n# Skills\n\nRust and OCR.\n",
    )
    .unwrap();
    fs::write(
        markdown.join("doc.md"),
        "# Profile\n\nChanged paragraph.\n\n# Skills\n\nRust and OCR.\n",
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_evaluate_prediction",
                "ground_truth_dir": gt,
                "prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    let mhs = report["metrics"]["score"]["mhs_mean"].as_f64().unwrap();
    assert!(mhs < 1.0, "{report}");
    assert!(mhs > 0.5, "{report}");
    assert_eq!(report["metrics"]["score"]["mhs_s_mean"], 1.0);
}

#[test]
fn opendataloader_evaluator_teds_scores_content_separately_from_structure() {
    let root = temp_dir("doctruth-runtime-opendataloader-evaluator-teds-tree");
    let gt = root.join("ground-truth/markdown");
    let prediction = root.join("prediction/doctruth-rust-eval");
    let markdown = prediction.join("markdown");
    fs::create_dir_all(&gt).unwrap();
    fs::create_dir_all(&markdown).unwrap();
    fs::write(
        gt.join("content-change.md"),
        "<table><tr><td>Name</td><td>Role</td></tr><tr><td>Ada</td><td>Engineer</td></tr></table>\n",
    )
    .unwrap();
    fs::write(
        markdown.join("content-change.md"),
        "<table><tr><td>Name</td><td>Role</td></tr><tr><td>Ada</td><td>Designer</td></tr></table>\n",
    )
    .unwrap();
    fs::write(
        gt.join("structure-change.md"),
        "<table><tr><td>Name</td><td>Role</td></tr><tr><td>Ada</td><td>Engineer</td></tr></table>\n",
    )
    .unwrap();
    fs::write(
        markdown.join("structure-change.md"),
        "<table><tr><td>Name</td><td>Role</td></tr></table>\n",
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_evaluate_prediction",
                "ground_truth_dir": gt,
                "prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    let content = &report["documents"][0]["scores"];
    let structure = &report["documents"][1]["scores"];
    assert!(content["teds"].as_f64().unwrap() < 1.0, "{report}");
    assert_eq!(content["teds_s"], 1.0);
    assert!(structure["teds_s"].as_f64().unwrap() < 1.0, "{report}");
}

#[test]
fn opendataloader_evaluator_converts_markdown_pipe_tables_for_teds() {
    let root = temp_dir("doctruth-runtime-opendataloader-evaluator-markdown-table");
    let gt = root.join("ground-truth/markdown");
    let prediction = root.join("prediction/doctruth-rust-eval");
    let markdown = prediction.join("markdown");
    fs::create_dir_all(&gt).unwrap();
    fs::create_dir_all(&markdown).unwrap();
    fs::write(
        gt.join("doc.md"),
        "| Name | Role |\n| --- | --- |\n| Ada | Engineer |\n",
    )
    .unwrap();
    fs::write(
        markdown.join("doc.md"),
        "| Name | Role |\n| --- | --- |\n| Ada | Designer |\n",
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_evaluate_prediction",
                "ground_truth_dir": gt,
                "prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    let scores = &report["documents"][0]["scores"];
    assert!(scores["teds"].as_f64().unwrap() < 1.0, "{report}");
    assert_eq!(scores["teds_s"], 1.0);
    assert_eq!(report["metrics"]["teds_count"], 1);
}

#[test]
fn opendataloader_evaluator_keeps_escaped_pipes_inside_markdown_table_cells() {
    let root = temp_dir("doctruth-runtime-opendataloader-evaluator-escaped-pipe");
    let gt = root.join("ground-truth/markdown");
    let prediction = root.join("prediction/doctruth-rust-eval");
    let markdown = prediction.join("markdown");
    fs::create_dir_all(&gt).unwrap();
    fs::create_dir_all(&markdown).unwrap();
    fs::write(
        gt.join("doc.md"),
        "| Field | Value |\n| --- | --- |\n| Formula | A \\| B |\n",
    )
    .unwrap();
    fs::write(
        markdown.join("doc.md"),
        "<table><tr><td>Field</td><td>Value</td></tr><tr><td>Formula</td><td>A | B</td></tr></table>\n",
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_evaluate_prediction",
                "ground_truth_dir": gt,
                "prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(
        report["metrics"]["score"]["teds_mean"], 0.914286,
        "{report}"
    );
    assert_eq!(report["metrics"]["score"]["teds_s_mean"], 1.0, "{report}");
}

#[test]
fn opendataloader_evaluator_normalizes_table_section_and_header_attributes() {
    let root = temp_dir("doctruth-runtime-opendataloader-evaluator-table-attrs");
    let gt = root.join("ground-truth/markdown");
    let prediction = root.join("prediction/doctruth-rust-eval");
    let markdown = prediction.join("markdown");
    fs::create_dir_all(&gt).unwrap();
    fs::create_dir_all(&markdown).unwrap();
    fs::write(
        gt.join("doc.md"),
        "<TABLE class='grid'><THEAD><TR><TH COLSPAN='2'>Profile</TH></TR></THEAD><TBODY><TR><TD>Ada</TD><TD>Engineer</TD></TR></TBODY></TABLE>\n",
    )
    .unwrap();
    fs::write(
        markdown.join("doc.md"),
        "<table><tr><td colspan='2'>Profile</td></tr><tr><td>Ada</td><td>Engineer</td></tr></table>\n",
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_evaluate_prediction",
                "ground_truth_dir": gt,
                "prediction_dir": prediction
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["metrics"]["score"]["teds_mean"], 1.0, "{report}");
    assert_eq!(report["metrics"]["score"]["teds_s_mean"], 1.0, "{report}");
}

#[test]
fn verify_benchmark_report_rejects_tampered_coverage_thresholds() {
    let root = temp_dir("doctruth-runtime-report-verify-tampered");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["minCasesPerTag"]["multi-layout"] = json!(2);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("minCasesPerTag mismatch"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_coverage_satisfaction() {
    let root = temp_dir("doctruth-runtime-report-coverage-satisfaction");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["coverageSatisfied"]["multi-layout"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("coverageSatisfied mismatch"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_fixture_coverage() {
    let root = temp_dir("doctruth-runtime-report-fixture-coverage");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["fixtureCoverageSatisfied"]["invoice"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains(
            "fixtureCoverageSatisfied mismatch",
        ));
}

#[test]
fn verify_benchmark_report_rejects_tampered_fixture_results() {
    let root = temp_dir("doctruth-runtime-report-fixture-results");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["fixtureResults"]["invoice"]["passed"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("fixtureResults mismatch"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_behavior_coverage() {
    let root = temp_dir("doctruth-runtime-report-behavior-coverage");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["behaviorCoverageSatisfied"]["xy-cut-edge"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains(
            "behaviorCoverageSatisfied mismatch",
        ));
}

#[test]
fn verify_benchmark_report_rejects_tampered_validity_inputs() {
    let root = temp_dir("doctruth-runtime-report-validity-inputs");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["validityInputs"]["actualTrustDocument"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("validityInputs mismatch"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_actual_trust_document_hash() {
    let root = temp_dir("doctruth-runtime-report-actual-document-hash");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["cases"][0]["actualTrustDocumentSha256"] = json!("sha256:tampered");
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("actualTrustDocumentSha256"));
}

#[test]
fn verify_benchmark_report_rejects_actual_trust_document_metric_mismatch() {
    let root = temp_dir("doctruth-runtime-report-actual-document-metrics");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["cases"][0]["actualTrustDocument"]["body"]["units"] = json!([]);
    let document_bytes = serde_json::to_vec(&recorded["cases"][0]["actualTrustDocument"]).unwrap();
    recorded["cases"][0]["actualTrustDocumentSha256"] = json!(sha256_bytes(&document_bytes));
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains(
            "actualTrustDocument metrics mismatch",
        ));
}

#[test]
fn verify_benchmark_report_rejects_tampered_case_replay() {
    let root = temp_dir("doctruth-runtime-report-case-replay");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    write_recorded_report(&manifest, &report_path);
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["cases"][0]["replay"]["evidenceSpanReplayable"] = json!(false);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("case replay mismatch"))
        .stderr(predicate::str::contains("evidenceSpanReplayable"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_metrics_below_minimum() {
    let root = temp_dir("doctruth-runtime-report-verify-metric-tampered");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["metrics"]["reading_order_f1"] = json!(0.0);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("aggregate metric mismatch"))
        .stderr(predicate::str::contains("reading_order_f1"));
}

#[test]
fn verify_benchmark_report_rejects_tampered_external_metrics() {
    let root = temp_dir("doctruth-runtime-report-verify-external-tampered");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    write_opendataloader_evaluation(&root);
    fs::write(&manifest, benchmark_manifest_with_external()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["metrics"]["opendataloader_nid"] = json!(0.0);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("external metrics mismatch"))
        .stderr(predicate::str::contains("opendataloader_nid"));
}

#[test]
fn verify_benchmark_report_accepts_case_metric_threshold_fallback() {
    let root = temp_dir("doctruth-runtime-report-verify-case-metric");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["metrics"]
        .as_object_mut()
        .unwrap()
        .remove("quote_anchor_accuracy");
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
}

#[test]
fn verify_benchmark_report_rejects_tampered_case_metric_against_actual_document() {
    let root = temp_dir("doctruth-runtime-report-verify-aggregate-mismatch");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let report_path = root.join("reports/parser-accuracy-report.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    fs::write(&manifest, benchmark_manifest()).unwrap();

    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
    let mut recorded: Value =
        serde_json::from_str(&fs::read_to_string(&report_path).unwrap()).unwrap();
    recorded["cases"][0]["metrics"]["reading_order_f1"] = json!(0.5);
    fs::write(&report_path, serde_json::to_string(&recorded).unwrap()).unwrap();

    let mut verifier = Command::cargo_bin("doctruth-runtime").unwrap();
    verifier
        .write_stdin(
            json!({
                "command": "verify_benchmark_report",
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .failure()
        .stderr(predicate::str::contains("fixtureResults mismatch"));
}

#[test]
fn benchmark_corpus_rejects_parser_accuracy_manifest_without_review_type() {
    let root = temp_dir("doctruth-runtime-bad-corpus");
    fs::create_dir_all(&root).unwrap();
    let manifest = root.join("corpus.json");
    fs::write(
        &manifest,
        json!({
            "name": "bad-corpus",
            "kind": "human-labeled",
            "qualityProfile": "parser-accuracy",
            "labeling": {
                "labelSetVersion": "seed-v1",
                "reviewedAt": "2026-06-13",
                "reviewer": "rust-runtime-test",
                "requiredMetrics": ["reading_order_f1"],
                "requiredTags": ["multi-layout"],
                "minCasesPerTag": 1
            },
            "minimums": {"reading_order_f1": 1.0},
            "cases": []
        })
        .to_string(),
    )
    .unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("reviewType"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_min_total_cases() {
    let root = temp_dir("doctruth-runtime-min-total-corpus");
    fs::create_dir_all(&root).unwrap();
    let manifest = root.join("corpus.json");
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("minTotalCases"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_source_sha() {
    let root = temp_dir("doctruth-runtime-source-pin-corpus");
    fs::create_dir_all(&root).unwrap();
    let manifest = root.join("corpus.json");
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    manifest_json["labeling"]["minTotalCases"] = json!(1);
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("sourceSha256"))
    .stderr(predicate::str::contains("rust-multi-layout"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_metrics() {
    let root = temp_dir("doctruth-runtime-core-metrics-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    manifest_json["labeling"]["minTotalCases"] = json!(1);
    manifest_json["cases"][0]["sourceSha256"] = json!(sha256_bytes(&fs::read(&pdf).unwrap()));
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("requiredMetrics"))
    .stderr(predicate::str::contains("bbox_iou"))
    .stderr(predicate::str::contains("ocr_text_accuracy"));
}

#[test]
fn benchmark_corpus_rejects_human_reviewed_parser_accuracy_without_core_tags() {
    let root = temp_dir("doctruth-runtime-core-tags-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["reviewType"] = json!("human-reviewed");
    manifest_json["labeling"]["minTotalCases"] = json!(1);
    manifest_json["labeling"]["requiredMetrics"] = json!([
        "reading_order_f1",
        "quote_anchor_accuracy",
        "bbox_coverage",
        "bbox_iou",
        "evidence_span_accuracy",
        "table_cell_f1",
        "ocr_text_accuracy"
    ]);
    manifest_json["minimums"] = json!({
        "reading_order_f1": 1.0,
        "quote_anchor_accuracy": 1.0,
        "bbox_coverage": 1.0,
        "bbox_iou": 1.0,
        "evidence_span_accuracy": 1.0,
        "table_cell_f1": 1.0,
        "ocr_text_accuracy": 1.0
    });
    manifest_json["cases"][0]["sourceSha256"] = json!(sha256_bytes(&fs::read(&pdf).unwrap()));
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("PARSER_ACCURACY_LABELING_INVALID"))
    .stderr(predicate::str::contains("requiredTags"))
    .stderr(predicate::str::contains("table"))
    .stderr(predicate::str::contains("source-map"));
}

#[test]
fn benchmark_corpus_rejects_source_sha_mismatch() {
    let root = temp_dir("doctruth-runtime-sha-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["cases"][0]["sourceSha256"] = json!("sha256:not-the-real-hash");
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("SOURCE_SHA256_MISMATCH"))
    .stderr(predicate::str::contains("fixture.pdf"));
}

#[test]
fn benchmark_corpus_rejects_maximum_threshold_failures() {
    let root = temp_dir("doctruth-runtime-maximum-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Rust corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Rust corpus evidence.\n").unwrap();
    fs::write(
        &expected_document,
        json!({"docId": "expected", "body": {"units": []}}).to_string(),
    )
    .unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["maximums"] = json!({"reading_order_f1": 0.0});
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    cmd.write_stdin(
        json!({
            "command": "benchmark_corpus",
            "manifest_path": manifest,
            "offline": true
        })
        .to_string(),
    )
    .assert()
    .failure()
    .stderr(predicate::str::contains("BENCHMARK_THRESHOLDS_FAILED"))
    .stderr(predicate::str::contains("reading_order_f1"))
    .stderr(predicate::str::contains("above allowed maximum"));
}

#[test]
fn benchmark_corpus_uses_case_preset_for_model_worker_cases() {
    let root = temp_dir("doctruth-runtime-model-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let worker = write_fake_model_worker();
    let (cache_dir, model_manifest) = ready_mnn_model_manifest();
    fs::write(&pdf, minimal_pdf("Fallback corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Worker corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["cases"].as_array_mut().unwrap()[0]["preset"] = json!("table-lite");
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", worker)
        .env("DOCTRUTH_MODEL_CACHE", cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", model_manifest)
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(report["passed"], true);
    assert_eq!(report["cases"][0]["preset"], "table-lite");
    assert_eq!(report["cases"][0]["metrics"]["reading_order_f1"], 1.0);
    assert_eq!(
        report["cases"][0]["actualTrustDocument"]["parserRun"]["modelRuntime"]["runtime"],
        "mnn"
    );
    assert_eq!(
        report["resourceProfile"]["modelRuntime"]["coldStartMs"],
        11.0
    );
    assert_eq!(
        report["resourceProfile"]["modelRuntime"]["inferenceMs"],
        4.0
    );
    assert_eq!(
        report["resourceProfile"]["modelRuntime"]["peakMemoryMb"],
        202
    );
    assert_eq!(
        report["resourceProfile"]["modelRuntime"]["loadedModels"],
        json!(["slanet-plus:v1"])
    );
}

#[test]
fn benchmark_corpus_reports_mnn_promotion_gate_for_model_profile() {
    let root = temp_dir("doctruth-runtime-mnn-promotion");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let worker = write_fake_model_worker();
    let (cache_dir, model_manifest) = ready_mnn_model_manifest();
    fs::write(&pdf, minimal_pdf("Fallback corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Worker corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    write_high_quality_opendataloader_evaluation(&root);
    let mut manifest_json: Value =
        serde_json::from_str(&benchmark_manifest_with_external()).unwrap();
    manifest_json["cases"].as_array_mut().unwrap()[0]["preset"] = json!("table-lite");
    manifest_json["promotionGates"] = json!({
        "mnn": {
            "heavyOracleSteadyRssMb": 1400,
            "qualityMinimums": {
                "overall": 0.88,
                "nid": 0.91,
                "teds": 0.88,
                "mhs": 0.78
            }
        }
    });
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", worker)
        .env("DOCTRUTH_MODEL_CACHE", cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", model_manifest)
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(report["mnnPromotion"]["evaluated"], true);
    assert_eq!(report["mnnPromotion"]["accepted"], true);
    assert_eq!(report["mnnPromotion"]["quality"]["passed"], true);
    assert_eq!(report["mnnPromotion"]["quality"]["overall"], 0.91);
    assert_eq!(
        report["mnnPromotion"]["quality"]["thresholds"]["teds"],
        0.88
    );
    assert_eq!(report["mnnPromotion"]["resources"]["passed"], true);
    assert_eq!(
        report["mnnPromotion"]["resources"]["noPythonTorchDoclingResidency"],
        true
    );
    assert_eq!(
        report["mnnPromotion"]["resources"]["lazyModelStartup"],
        true
    );
    assert_eq!(
        report["mnnPromotion"]["resources"]["heavyOracleSteadyRssMb"],
        1400
    );
    assert_eq!(
        report["mnnPromotion"]["resources"]["modelPeakMemoryMb"],
        202
    );
}

#[test]
fn benchmark_corpus_rejects_mnn_promotion_when_quality_gate_fails() {
    let root = temp_dir("doctruth-runtime-mnn-promotion-fail");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    let worker = write_fake_model_worker();
    let (cache_dir, model_manifest) = ready_mnn_model_manifest();
    fs::write(&pdf, minimal_pdf("Fallback corpus evidence.")).unwrap();
    fs::write(&expected_markdown, "Worker corpus evidence.\n").unwrap();
    fs::write(&expected_document, "{}").unwrap();
    write_opendataloader_evaluation(&root);
    let mut manifest_json: Value =
        serde_json::from_str(&benchmark_manifest_with_external()).unwrap();
    manifest_json["cases"].as_array_mut().unwrap()[0]["preset"] = json!("table-lite");
    manifest_json["promotionGates"] = json!({
        "mnn": {
            "heavyOracleSteadyRssMb": 1400,
            "qualityMinimums": {
                "overall": 0.88,
                "nid": 0.91,
                "teds": 0.88,
                "mhs": 0.78
            }
        }
    });
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .env("DOCTRUTH_RUNTIME_MODEL_COMMAND", worker)
        .env("DOCTRUTH_MODEL_CACHE", cache_dir)
        .env("DOCTRUTH_MODEL_MANIFEST", model_manifest)
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let report: Value = serde_json::from_slice(&output).unwrap();

    assert_eq!(report["mnnPromotion"]["evaluated"], true);
    assert_eq!(report["mnnPromotion"]["accepted"], false);
    assert_eq!(report["mnnPromotion"]["quality"]["passed"], false);
    assert_eq!(report["mnnPromotion"]["quality"]["teds"], 0.52);
    assert_eq!(report["mnnPromotion"]["resources"]["passed"], true);
}

#[test]
fn benchmark_corpus_scores_expected_document_quality_metrics() {
    let root = temp_dir("doctruth-runtime-quality-corpus");
    fs::create_dir_all(&root).unwrap();
    let pdf = root.join("fixture.pdf");
    let expected_markdown = root.join("expected.md");
    let expected_document = root.join("expected.json");
    let manifest = root.join("corpus.json");
    fs::write(&pdf, minimal_pdf("Invoice Total 123.")).unwrap();
    fs::write(&expected_markdown, "Invoice Total 123.\n").unwrap();
    fs::write(
        &expected_document,
        json!({
            "docId": "expected",
            "body": {
                "units": [{
                    "unitId": "expected-unit-0001",
                    "kind": "LINE_SPAN",
                    "page": 1,
                    "text": "Invoice Total 123.",
                    "evidenceSpanIds": ["expected-span-0001"],
                    "location": {
                        "page": 1,
                        "readingOrder": 1,
                        "boundingBox": {
                            "x0": 117.6470588235294,
                            "y0": 95.95959595959596,
                            "x1": 324.0000406901042,
                            "y1": 116.16161616161617
                        }
                    },
                    "sourceObjectId": "expected-line-1",
                    "confidence": {"score": 1.0, "rationale": "test label"},
                    "warnings": []
                }]
            }
        })
        .to_string(),
    )
    .unwrap();
    let mut manifest_json: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest_json["labeling"]["requiredMetrics"] = json!([
        "reading_order_f1",
        "bbox_iou",
        "evidence_span_accuracy",
        "table_cell_f1",
        "ocr_text_accuracy"
    ]);
    manifest_json["minimums"] = json!({
        "reading_order_f1": 1.0,
        "bbox_iou": 1.0,
        "evidence_span_accuracy": 1.0,
        "table_cell_f1": 1.0,
        "ocr_text_accuracy": 1.0
    });
    fs::write(&manifest, manifest_json.to_string()).unwrap();

    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();

    let output = cmd
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let report: Value = serde_json::from_slice(&output).unwrap();
    let metrics = &report["cases"][0]["metrics"];
    assert_eq!(metrics["bbox_iou"], 1.0);
    assert_eq!(metrics["evidence_span_accuracy"], 1.0);
    assert_eq!(metrics["table_cell_f1"], 1.0);
    assert_eq!(metrics["ocr_text_accuracy"], 1.0);
}

fn benchmark_manifest() -> String {
    json!({
        "name": "rust-parser-accuracy-seed",
        "kind": "human-labeled",
        "qualityProfile": "parser-accuracy",
        "labeling": {
            "labelSetVersion": "rust-seed-v1",
            "reviewedAt": "2026-06-13",
            "reviewer": "rust-runtime-test",
            "reviewType": "generated-seed",
            "requiredMetrics": [
                "reading_order_f1",
                "quote_anchor_accuracy",
                "bbox_coverage"
            ],
            "requiredTags": ["multi-layout"],
            "minCasesPerTag": 1,
            "requiredFixtureTypes": [
                "simple-single-column",
                "two-column",
                "sidebar-resume",
                "table",
                "borderless-table",
                "scanned-ocr",
                "invoice",
                "mixed-layout"
            ],
            "minCasesPerFixtureType": 1,
            "requiredBehaviors": [
                "xy-cut-edge",
                "safety-filter",
                "structure-tree-preference",
                "table-cluster-heuristics"
            ],
            "minCasesPerBehavior": 1
        },
        "minimums": {
            "reading_order_f1": 1.0,
            "quote_anchor_accuracy": 1.0,
            "bbox_coverage": 1.0
        },
        "cases": [
            {
                "name": "rust-multi-layout",
                "labelId": "rust-seed-v1-0001",
                "tags": ["multi-layout"],
                "fixtureTypes": [
                    "simple-single-column",
                    "two-column",
                    "sidebar-resume",
                    "table",
                    "borderless-table",
                    "scanned-ocr",
                    "invoice",
                    "mixed-layout"
                ],
                "behaviors": [
                    "xy-cut-edge",
                    "safety-filter",
                    "structure-tree-preference",
                    "table-cluster-heuristics"
                ],
                "source": "fixture.pdf",
                "expectedMarkdown": "expected.md",
                "expectedDocument": "expected.json"
            }
        ]
    })
    .to_string()
}

fn benchmark_manifest_with_external() -> String {
    let mut manifest: Value = serde_json::from_str(&benchmark_manifest()).unwrap();
    manifest["minimums"]["opendataloader_nid"] = json!(0.90);
    manifest["minimums"]["opendataloader_teds"] = json!(0.50);
    manifest["minimums"]["opendataloader_mhs"] = json!(0.74);
    manifest["maximums"] = json!({"opendataloader_speed": 0.02});
    manifest["externalEvaluations"] = json!({"opendataloader": "opendataloader-evaluation.json"});
    manifest.to_string()
}

fn write_opendataloader_evaluation(root: &std::path::Path) {
    fs::write(
        root.join("opendataloader-evaluation.json"),
        json!({
            "summary": {
                "engine_name": "doctruth-runtime",
                "engine_version": "test",
                "document_count": 1,
                "elapsed_per_doc": 0.015
            },
            "metrics": {
                "score": {
                    "nid_mean": 0.91,
                    "teds_mean": 0.52,
                    "mhs_mean": 0.76
                }
            }
        })
        .to_string(),
    )
    .unwrap();
}

fn write_high_quality_opendataloader_evaluation(root: &std::path::Path) {
    fs::write(
        root.join("opendataloader-evaluation.json"),
        json!({
            "summary": {
                "engine_name": "doctruth-runtime-mnn",
                "engine_version": "test",
                "document_count": 1,
                "elapsed_per_doc": 0.01
            },
            "metrics": {
                "score": {
                    "nid_mean": 0.93,
                    "teds_mean": 0.90,
                    "mhs_mean": 0.90
                }
            }
        })
        .to_string(),
    )
    .unwrap();
}

fn write_fake_model_worker() -> PathBuf {
    let path = temp_dir("doctruth-runtime-corpus-worker").with_extension("py");
    fs::write(
        &path,
        r#"#!/usr/bin/env python3
import json
import sys

request = json.load(sys.stdin)
assert request["preset"] == "table-lite"
assert request["models"][0]["backend"] == "mnn"
assert request["models"][0]["format"] == "mnn"
assert request["models"][0]["cacheStatus"] == "READY"
print(json.dumps({
    "docId": request["source_hash"],
    "source": {
        "sourceFilename": "worker.pdf",
        "sourceHash": request["source_hash"],
        "metadata": {"sourceFilename": "worker.pdf", "pageCount": 1}
    },
    "body": {
        "pages": [{
            "pageNumber": 1,
            "width": 612.0,
            "height": 792.0,
            "textLayerAvailable": True,
            "imageHash": "sha256:" + "0" * 64
        }],
        "units": [{
            "unitId": "unit-0001",
            "kind": "TABLE_CELL",
            "page": 1,
            "text": "Worker corpus evidence.",
            "evidenceSpanIds": ["span-0001"],
            "location": {
                "page": 1,
                "readingOrder": 1,
                "boundingBox": {"x0": 0.0, "y0": 0.0, "x1": 1000.0, "y1": 1000.0}
            },
            "sourceObjectId": "worker-cell-1",
            "confidence": {"score": 0.93, "rationale": "fake model worker"},
            "warnings": []
        }],
        "tables": []
    },
    "parserRun": {
        "parserVersion": "test-worker",
        "preset": request["preset"],
        "backend": "rust-sidecar+model-worker",
        "models": ["slanet-plus:v1"],
        "warnings": []
    },
    "auditGradeStatus": "AUDIT_GRADE",
    "metrics": {
        "runtime": "mnn",
        "coldStartMs": 11.0,
        "inferenceMs": 4.0,
        "peakMemoryMb": 202,
        "loadedModels": ["slanet-plus:v1"],
        "unload": {"status": "scheduled", "policy": "idle-after-request"}
    }
}))
"#,
    )
    .unwrap();
    let mut permissions = fs::metadata(&path).unwrap().permissions();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        permissions.set_mode(0o755);
        fs::set_permissions(&path, permissions).unwrap();
    }
    path
}

fn write_slow_model_worker() -> PathBuf {
    let path = temp_dir("doctruth-runtime-slow-model-worker").with_extension("py");
    fs::write(
        &path,
        r#"#!/usr/bin/env python3
import time

time.sleep(2)
"#,
    )
    .unwrap();
    let mut permissions = fs::metadata(&path).unwrap().permissions();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        permissions.set_mode(0o755);
        fs::set_permissions(&path, permissions).unwrap();
    }
    path
}

fn ready_mnn_model_manifest() -> (PathBuf, PathBuf) {
    let cache_dir = temp_dir("doctruth-runtime-corpus-mnn-cache");
    fs::create_dir_all(&cache_dir).unwrap();
    let artifact = b"ready mnn model artifact";
    let artifact_sha = sha256_bytes(artifact);
    fs::write(cache_dir.join("slanet-plus-v1.bin"), artifact).unwrap();
    let manifest = temp_dir("doctruth-runtime-corpus-mnn-manifest").with_extension("json");
    fs::write(
        &manifest,
        json!({
            "presets": {
                "table-lite": [
                    {
                        "name": "slanet-plus",
                        "version": "v1",
                        "sha256": artifact_sha,
                        "sizeBytes": artifact.len(),
                        "required": true,
                        "task": "table-structure-recognition",
                        "backend": "mnn",
                        "format": "mnn",
                        "precision": "fp32",
                        "license": "test"
                    }
                ]
            }
        })
        .to_string(),
    )
    .unwrap();
    (cache_dir, manifest)
}

fn temp_dir(prefix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let sequence = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!(
        "{prefix}-{}-{nanos}-{sequence}",
        std::process::id()
    ))
}

fn run_opendataloader_prediction(doc_id: &str, output_dir: &PathBuf) -> Value {
    let bench_dir =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../third_party/opendataloader-bench");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": bench_dir,
                "output_dir": output_dir,
                "engine": "doctruth-opendataloader-parity-contract",
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
    serde_json::from_slice(&output).unwrap()
}

fn run_opendataloader_prediction_with_real_mnn(
    doc_id: &str,
    output_dir: &PathBuf,
    model_cache: &PathBuf,
    model_manifest: &PathBuf,
    model_worker: &PathBuf,
) -> Value {
    let bench_dir =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../third_party/opendataloader-bench");
    let mut cmd = Command::cargo_bin("doctruth-runtime").unwrap();
    let output = cmd
        .write_stdin(
            json!({
                "command": "opendataloader_prediction",
                "bench_dir": bench_dir,
                "output_dir": output_dir,
                "engine": "doctruth-opendataloader-real-mnn-contract",
                "doc_id": doc_id,
                "preset": "table-lite",
                "runtime_profile": "edge-model",
                "timeout_seconds": 30,
                "model_manifest": model_manifest,
                "model_cache": model_cache,
                "model_worker": model_worker
            })
            .to_string(),
        )
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    serde_json::from_slice(&output).unwrap()
}

fn real_opendataloader_mnn_pack() -> Option<(PathBuf, PathBuf, PathBuf)> {
    if !cfg!(all(feature = "mnn-native", feature = "mnn-ocr")) {
        return None;
    }
    let repo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let manifest = repo.join("model-packs/opendataloader-hybrid-models.json");
    let cache = repo.join("target/opendataloader-model-pack-cache");
    let worker = repo.join("runtime/doctruth-runtime/target/debug/doctruth-mnn-model-worker");
    (manifest.is_file() && cache.is_dir() && worker.is_file()).then_some((cache, manifest, worker))
}

fn vendored_opendataloader_pdf(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../third_party/opendataloader-bench/pdfs")
        .join(name)
}

fn write_recorded_report(manifest: &PathBuf, report_path: &PathBuf) {
    let mut writer = Command::cargo_bin("doctruth-runtime").unwrap();
    writer
        .write_stdin(
            json!({
                "command": "benchmark_corpus",
                "manifest_path": manifest,
                "offline": true,
                "report_path": report_path
            })
            .to_string(),
        )
        .assert()
        .success();
}

fn sha256_bytes(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    format!("sha256:{}", hex_lower(&hasher.finalize()))
}

fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn minimal_pdf(text: &str) -> Vec<u8> {
    let escaped = text
        .replace('\\', r"\\")
        .replace('(', r"\(")
        .replace(')', r"\)");
    let stream = format!("BT\n/F1 16 Tf\n72 700 Td\n({escaped}) Tj\nET\n");
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>".to_string(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_string(),
        format!("<< /Length {} >>\nstream\n{}endstream", stream.len(), stream),
    ];
    write_pdf_objects(&objects)
}

fn write_pdf_objects(objects: &[String]) -> Vec<u8> {
    let mut pdf = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", index + 1, object).as_bytes());
    }
    write_xref(&mut pdf, objects.len(), &offsets);
    pdf
}

fn write_xref(pdf: &mut Vec<u8>, object_count: usize, offsets: &[usize]) {
    let xref_offset = pdf.len();
    pdf.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", object_count + 1).as_bytes(),
    );
    for offset in offsets {
        pdf.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    pdf.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{}\n%%EOF\n",
            object_count + 1,
            xref_offset
        )
        .as_bytes(),
    );
}
