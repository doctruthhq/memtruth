use memtruth_projector::{
    FtsCollectionMappingSpec, FtsFieldMapping, FtsFieldRole, FtsMappingSpec, SourceLocator,
    SourceSystem, project_json_collection, project_json_record,
};
use serde_json::json;

#[test]
fn projects_nested_user_schema_into_fts_fields_without_mutating_source_shape() {
    let record = json!({
        "case": {
            "id": "FCA-2024-123",
            "citation": "[2024] FCA 123",
            "court": "FCA",
            "date": "2024-01-01"
        },
        "document": {
            "title": "Example v Minister",
            "sections": [
                {
                    "heading": "Procedural fairness",
                    "paragraphs": [
                        {"number": 81, "text": "The tribunal refused late evidence."},
                        {"number": 82, "text": "That refusal denied procedural fairness."}
                    ]
                },
                {
                    "heading": "Orders",
                    "paragraphs": [
                        {"number": 90, "text": "The application is allowed."}
                    ]
                }
            ]
        },
        "storage": {
            "r2_key": "oalc/fca/2024/123.json"
        }
    });
    let spec = FtsMappingSpec {
        mappings: vec![
            FtsFieldMapping::full_text("title", "document.title"),
            FtsFieldMapping::full_text("body", "document.sections.paragraphs.text"),
            FtsFieldMapping::filter("court", "case.court"),
            FtsFieldMapping::filter("citation", "case.citation"),
            FtsFieldMapping::metadata("source_key", "storage.r2_key"),
        ],
    };

    let projected = project_json_record(
        SourceLocator {
            system: SourceSystem::R2,
            uri: "r2://legal-corpus/oalc/fca/2024/123.json".to_string(),
            object_id: "FCA-2024-123".to_string(),
        },
        &record,
        &spec,
    )
    .unwrap();

    assert_eq!(projected.source.system, SourceSystem::R2);
    assert_eq!(
        projected.full_text_fields["body"],
        "The tribunal refused late evidence. That refusal denied procedural fairness. The application is allowed."
    );
    assert_eq!(projected.filter_fields["court"], "FCA");
    assert_eq!(projected.filter_fields["citation"], "[2024] FCA 123");
    assert_eq!(
        projected.metadata["source_key"],
        json!("oalc/fca/2024/123.json")
    );
}

#[test]
fn projects_nested_array_items_into_section_level_fts_records() {
    let record = json!({
        "document": {
            "title": "Privacy Act 1988",
            "sections": [
                {
                    "number": "14",
                    "heading": "Australian Privacy Principles",
                    "part": "Part III—Information privacy",
                    "paragraphs": [
                        {"text": "The Australian Privacy Principles are set out in Schedule 1."}
                    ]
                },
                {
                    "number": "26WE",
                    "heading": "Eligible data breach",
                    "part": "Part IIIC—Notification of eligible data breaches",
                    "paragraphs": [
                        {"text": "There are reasonable grounds to believe that there has been an eligible data breach."}
                    ]
                }
            ]
        }
    });
    let spec = FtsCollectionMappingSpec {
        item_path: "document.sections".to_string(),
        mappings: vec![
            FtsFieldMapping::filter("section_number", "number"),
            FtsFieldMapping::filter("section_heading", "heading"),
            FtsFieldMapping::filter("part_label", "part"),
            FtsFieldMapping::full_text("body", "paragraphs.text"),
        ],
    };

    let projected = project_json_collection(
        SourceLocator {
            system: SourceSystem::R2,
            uri: "r2://legal-corpus/privacy-act.json".to_string(),
            object_id: "C2026C00227".to_string(),
        },
        &record,
        &spec,
    )
    .unwrap();

    assert_eq!(projected.len(), 2);
    assert_eq!(projected[0].source.object_id, "C2026C00227::0000");
    assert_eq!(projected[0].filter_fields["section_number"], "14");
    assert_eq!(
        projected[0].filter_fields["section_heading"],
        "Australian Privacy Principles"
    );
    assert_eq!(
        projected[1].full_text_fields["body"],
        "There are reasonable grounds to believe that there has been an eligible data breach."
    );
}

#[test]
fn missing_optional_paths_project_to_empty_strings() {
    let record = json!({"id": "local-1", "body": {"text": "Local file text"}});
    let spec = FtsMappingSpec {
        mappings: vec![
            FtsFieldMapping {
                target_field: "body".to_string(),
                source_path: "body.text".to_string(),
                role: FtsFieldRole::FullText,
                required: true,
            },
            FtsFieldMapping::filter("court", "missing.court"),
        ],
    };

    let projected = project_json_record(
        SourceLocator {
            system: SourceSystem::FileSystem,
            uri: "file:///tmp/local-1.json".to_string(),
            object_id: "local-1".to_string(),
        },
        &record,
        &spec,
    )
    .unwrap();

    assert_eq!(projected.full_text_fields["body"], "Local file text");
    assert_eq!(projected.filter_fields["court"], "");
}
