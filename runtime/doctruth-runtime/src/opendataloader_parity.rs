use serde_json::{Value, json};

pub fn opendataloader_parity_matrix_json() -> Value {
    json!({
        "source": {
            "name": "OpenDataLoader PDF",
            "path": "third_party/opendataloader-pdf-reference",
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
            processor("CaptionProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#captionprocessor"),
            processor("LevelProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#levelprocessor"),
            processor("HeaderFooterProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#headerfooterprocessor"),
            processor("ContentFilterProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#contentfilterprocessor"),
            processor("TextDecorationProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#textdecorationprocessor"),
            processor("TableBorderProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#tableborderprocessor"),
            processor("ClusterTableProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#clustertableprocessor"),
            processor("SpecialTableProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#specialtableprocessor"),
            processor("TableStructureNormalizer", "partial", "docs/parser/opendataloader-parity-matrix.md#tablestructurenormalizer"),
            processor("HiddenTextProcessor", "partial", "docs/parser/opendataloader-parity-matrix.md#hiddentextprocessor"),
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
