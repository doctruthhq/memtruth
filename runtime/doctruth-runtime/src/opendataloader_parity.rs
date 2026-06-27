use serde_json::{Value, json};

pub fn opendataloader_parity_matrix_json() -> Value {
    json!({
        "source": {
            "name": "OpenDataLoader PDF",
            "path": "third_party/opendataloader-pdf-reference",
            "license": "Apache-2.0"
        },
        "pipeline_stages": pipeline_stages(),
        "heuristic_owners": heuristic_owners(),
        "processors": [
            processor("DocumentProcessor", "partial", "document_parse", "benchmark_corpus_contract"),
            processor("TaggedDocumentProcessor", "partial", "structure_tree", "benchmark_corpus_contract"),
            processor("TextProcessor", "partial", "text_filter", "opendataloader_text_processor_contract"),
            processor("TextLineProcessor", "partial", "line_grouping", "opendataloader_line_paragraph_contract"),
            processor("ParagraphProcessor", "partial", "paragraph_merge", "opendataloader_line_paragraph_contract"),
            processor("HeadingProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
            processor("ListProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
            processor("CaptionProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
            processor("LevelProcessor", "partial", "structure_probe", "opendataloader_structure_contract"),
            processor("HeaderFooterProcessor", "partial", "header_footer", "PdfDocumentParserTest"),
            processor("ContentFilterProcessor", "partial", "content_filter_probe", "opendataloader_content_filter_probe"),
            processor("TextDecorationProcessor", "partial", "text_decoration", "opendataloader_text_processor_contract"),
            processor("TableBorderProcessor", "partial", "table_border_probe", "opendataloader_table_processor_contract"),
            processor("ClusterTableProcessor", "partial", "table_cluster", "opendataloader_table_processor_contract"),
            processor("SpecialTableProcessor", "partial", "table_special_cases", "opendataloader_table_processor_contract"),
            processor("TableStructureNormalizer", "partial", "table_normalizer", "opendataloader_table_processor_contract"),
            processor("HiddenTextProcessor", "partial", "content_filter_probe", "opendataloader_content_filter_probe"),
            processor("HybridDocumentProcessor", "partial", "java_core_auto_mnn", "benchmark_corpus_contract"),
            processor("TriageProcessor", "partial", "triage_probe", "opendataloader_triage_probe"),
            processor("DoclingSchemaTransformer", "oracle_only", "docling_schema_reference", "opendataloader_parity_matrix_contract"),
            processor("OcrStrategy", "partial", "ocr_routing", "model_worker_contract")
        ]
    })
}

fn heuristic_owners() -> Vec<Value> {
    vec![
        heuristic(
            "hidden_offpage_tiny_duplicate_text_filter",
            "ContentFilterProcessor",
            "content_filter_probe",
            "opendataloader_content_filter_probe",
        ),
        heuristic(
            "right_aligned_paragraph_precedence",
            "ParagraphProcessor",
            "paragraph_merge",
            "opendataloader_line_paragraph_contract",
        ),
        heuristic(
            "wrapped_list_continuation",
            "ListProcessor",
            "structure_probe",
            "opendataloader_structure_contract",
        ),
        heuristic(
            "nested_list_hierarchy",
            "ListProcessor",
            "structure_probe",
            "opendataloader_structure_contract",
        ),
        heuristic(
            "caption_marker_classification",
            "CaptionProcessor",
            "structure_probe",
            "opendataloader_structure_contract",
        ),
        heuristic(
            "survey_chart_table_rejection",
            "SpecialTableProcessor",
            "table_classifier_probe",
            "opendataloader_table_processor_contract",
        ),
        heuristic(
            "borderless_cluster_table_reconstruction",
            "ClusterTableProcessor",
            "table_cluster",
            "opendataloader_table_processor_contract",
        ),
        heuristic(
            "ocr_rescue_sparse_java_output_only",
            "HybridDocumentProcessor",
            "java_core_auto_mnn",
            "benchmark_corpus_contract",
        ),
        heuristic(
            "prediction_markdown_repair",
            "DocumentProcessor",
            "prediction_export",
            "opendataloader_prediction_contract",
        ),
    ]
}

fn heuristic(name: &str, processor: &str, owner: &str, test: &str) -> Value {
    json!({
        "heuristic": name,
        "processor": processor,
        "owner": owner,
        "focused_test": test
    })
}

fn pipeline_stages() -> Vec<Value> {
    vec![
        stage("pdf_text_extraction", "DocumentProcessor"),
        stage("text_normalization", "TextProcessor"),
        stage("content_filtering", "ContentFilterProcessor"),
        stage("line_grouping", "TextLineProcessor"),
        stage("paragraph_merge", "ParagraphProcessor"),
        stage("heading_hierarchy", "HeadingProcessor"),
        stage("list_grouping", "ListProcessor"),
        stage("caption_binding", "CaptionProcessor"),
        stage("table_border_detection", "TableBorderProcessor"),
        stage("borderless_table_clustering", "ClusterTableProcessor"),
        stage("table_structure_normalization", "TableStructureNormalizer"),
        stage("chart_table_gate", "SpecialTableProcessor"),
        stage("ocr_table_model_routing", "HybridDocumentProcessor"),
        stage("reading_order", "TaggedDocumentProcessor"),
        stage("trust_document_export", "DocumentProcessor"),
    ]
}

fn stage(name: &str, owner: &str) -> Value {
    json!({
        "name": name,
        "owner": owner,
        "canonical_output": "TrustDocument intermediate block stream"
    })
}

fn processor(upstream: &str, status: &str, owner: &str, test: &str) -> Value {
    let anchor = upstream.to_ascii_lowercase();
    json!({
        "upstream": upstream,
        "status": status,
        "doc_truth_owner": owner,
        "focused_test": test,
        "doc": format!("docs/parser/opendataloader-parity-matrix.md#{anchor}"),
        "full200_evidence": "",
        "remaining_gap": "tracked in docs/parser/opendataloader-processor-gap-report.md"
    })
}
