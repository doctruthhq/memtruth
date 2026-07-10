use memtruth_vespa::{
    LegalSectionDoc, VespaQueryFilter, VespaQuerySpec, compare_vespa_schema_spec,
    legal_section_doc_from_value, legal_section_doc_put, legal_section_schema_spec,
    parse_vespa_schema, preflight_legal_section_documents, preflight_legal_section_feed_values,
    preflight_vespa_query, validate_vespa_schema_spec,
};

#[test]
fn projects_section_level_legal_docs_to_fts_compatible_vespa_puts() {
    let section = LegalSectionDoc {
        doc_id: "federal_register_of_legislation:C2026C00227:section:0056-14".to_string(),
        parent_doc_id: "federal_register_of_legislation:C2026C00227".to_string(),
        version_id: "C2026C00227".to_string(),
        section_ordinal: 56,
        section_number: "14".to_string(),
        section_heading: "Australian Privacy Principles".to_string(),
        part_label: "Part III—Information privacy".to_string(),
        division_label: "Division 2—Australian Privacy Principles".to_string(),
        subdivision_label: None,
        citation: "Privacy Act 1988 (Cth) C2026C00227 s 14".to_string(),
        title: "Privacy Act 1988 s 14 - Australian Privacy Principles".to_string(),
        jurisdiction: "commonwealth".to_string(),
        doc_type: "act".to_string(),
        source: "federal_register_of_legislation".to_string(),
        authority_type: "legislation".to_string(),
        authority_rank: 90,
        court_name: None,
        court_rank: None,
        instrument_status: "current".to_string(),
        is_current: true,
        version_date_days: Some(20260604),
        effective_from_days: None,
        effective_to_days: None,
        decision_date: Some("2026-06-04".to_string()),
        url: Some("https://www.legislation.gov.au/C2004A03712/latest".to_string()),
        source_locator: Some("pp. 56-57".to_string()),
        page_start: Some(56),
        page_end: Some(57),
        page_numbers: vec![56, 57],
        char_start: Some(1000),
        char_end: Some(1300),
        source_format: Some("pdf".to_string()),
        locator_confidence: Some("exact".to_string()),
        body_text: "14 Australian Privacy Principles\nThe Australian Privacy Principles are set out in Schedule 1.".to_string(),
        dense_embedding: None,
    };

    let section_put = legal_section_doc_put(&section).unwrap();

    assert_eq!(
        section_put["put"],
        "id:legal:legal_doc::federal_register_of_legislation:C2026C00227:section:0056-14"
    );
    assert_eq!(
        section_put["fields"]["parent_doc_id"],
        section.parent_doc_id
    );
    assert_eq!(section_put["fields"]["chunk_type"], "section");
    assert_eq!(section_put["fields"]["section_ordinal"], 56);
    assert_eq!(section_put["fields"]["section_number"], "14");
    assert_eq!(section_put["fields"]["section_number_attr"], "14");
    assert_eq!(
        section_put["fields"]["section_tokens"],
        serde_json::json!(["14"])
    );
    assert_eq!(
        section_put["fields"]["heading_tokens"],
        serde_json::json!(["australian", "privacy", "principles"])
    );
    assert_eq!(
        section_put["fields"]["citation_tokens"],
        serde_json::json!(["privacy", "act", "1988", "cth", "c2026c00227", "s", "14"])
    );
    assert_eq!(section_put["fields"]["source_locator"], "pp. 56-57");
    assert_eq!(section_put["fields"]["page_start"], 56);
    assert_eq!(section_put["fields"]["page_end"], 57);
    assert_eq!(
        section_put["fields"]["page_numbers"],
        serde_json::json!([56, 57])
    );
    assert_eq!(section_put["fields"]["char_start"], 1000);
    assert_eq!(section_put["fields"]["char_end"], 1300);
    assert_eq!(section_put["fields"]["source_format"], "pdf");
    assert_eq!(section_put["fields"]["locator_confidence"], "exact");
    assert_eq!(section_put["fields"]["authority_type"], "legislation");
    assert_eq!(section_put["fields"]["authority_rank"], 90);
    assert_eq!(section_put["fields"]["instrument_status"], "current");
    assert_eq!(section_put["fields"]["is_current"], 1);
    assert_eq!(section_put["fields"]["version_date_days"], 20260604);
    assert!(section_put["fields"].get("court_name").is_none());
    assert!(section_put["fields"].get("court_rank").is_none());
    assert_eq!(section_put["fields"]["body_text"], section.body_text);
}

#[test]
fn builds_section_doc_from_doc_truth_section_json() {
    let section = legal_section_doc_from_value(&serde_json::json!({
        "doc_id": "federal_register_of_legislation:C2026C00227:section:0056-14",
        "parent_doc_id": "federal_register_of_legislation:C2026C00227",
        "version_id": "C2026C00227",
        "section_ordinal": 56,
        "section_number": "14",
        "section_heading": "Australian Privacy Principles",
        "part_label": "Part III—Information privacy",
        "division_label": "Division 2—Australian Privacy Principles",
        "type": "act",
        "jurisdiction": "commonwealth",
        "source": "federal_register_of_legislation",
        "authority_type": "legislation",
        "authority_rank": 90,
        "instrument_status": "current",
        "is_current": true,
        "version_date_days": 20260604,
        "citation": "Privacy Act 1988 (Cth) C2026C00227 s 14",
        "title": "Privacy Act 1988 s 14 - Australian Privacy Principles",
        "date": "2026-06-04",
        "url": "https://www.legislation.gov.au/C2004A03712/latest",
        "source_format": "docx",
        "locator_confidence": "unavailable",
        "char_start": 2048,
        "char_end": 2300,
        "text": "14 Australian Privacy Principles\nThe Australian Privacy Principles are set out in Schedule 1."
    }))
    .unwrap();

    assert_eq!(section.doc_type, "act");
    assert_eq!(section.decision_date.as_deref(), Some("2026-06-04"));
    assert_eq!(
        section.body_text,
        "14 Australian Privacy Principles\nThe Australian Privacy Principles are set out in Schedule 1."
    );
    assert_eq!(section.source_format.as_deref(), Some("docx"));
    assert_eq!(section.locator_confidence.as_deref(), Some("unavailable"));
    assert_eq!(section.page_start, None);
    assert_eq!(section.page_end, None);
    assert!(section.page_numbers.is_empty());
    assert_eq!(section.char_start, Some(2048));
    assert_eq!(section.char_end, Some(2300));
    assert_eq!(section.authority_type, "legislation");
    assert_eq!(section.authority_rank, 90);
    assert_eq!(section.instrument_status, "current");
    assert!(section.is_current);
    assert_eq!(section.version_date_days, Some(20260604));

    let section_put = legal_section_doc_put(&section).unwrap();
    assert_eq!(section_put["fields"]["body_text"], section.body_text);
    assert_eq!(section_put["fields"]["source_format"], "docx");
    assert_eq!(section_put["fields"]["locator_confidence"], "unavailable");
    assert!(section_put["fields"].get("page_start").is_none());
    assert!(section_put["fields"].get("page_end").is_none());
    assert!(section_put["fields"].get("page_numbers").is_none());
    assert_eq!(
        section_put["fields"]["heading_tokens"],
        serde_json::json!(["australian", "privacy", "principles"])
    );
}

#[test]
fn cleans_section_body_text_before_feed_projection() {
    let section = legal_section_doc_from_value(&serde_json::json!({
        "doc_id": "federal_register_of_legislation:C2025C00341:section:0001-4",
        "parent_doc_id": "federal_register_of_legislation:C2025C00341",
        "version_id": "C2025C00341",
        "section_ordinal": 1,
        "section_number": "4",
        "section_heading": "Definitions",
        "type": "act",
        "jurisdiction": "commonwealth",
        "source": "federal_register_of_legislation",
        "instrument_status": "current",
        "citation": "Family Law Act 1975 (Cth) C2025C00341 s 4",
        "title": "Family Law Act 1975 s 4 - Definitions",
        "text": "\u{feff}4\u{00a0}Definitions\r\n\r\n\r\nchild &amp; parent\u{2014}meaning"
    }))
    .unwrap();

    assert_eq!(section.body_text, "4 Definitions\n\nchild & parent-meaning");
}

#[test]
fn legal_section_schema_spec_preflights_vespa_roles() {
    let spec = legal_section_schema_spec();
    let diagnostics = validate_vespa_schema_spec(&spec);

    assert!(diagnostics.is_empty(), "{diagnostics:#?}");
    assert_eq!(spec.name, "legal_doc");
    assert!(spec.default_fieldset.contains(&"body_text".to_string()));

    let body_text = spec.field("body_text").unwrap();
    assert!(body_text.indexed);
    assert!(body_text.enable_bm25);
    assert!(body_text.summary);

    let jurisdiction = spec.field("jurisdiction").unwrap();
    assert!(jurisdiction.attribute);
    assert!(jurisdiction.fast_search);

    let heading_tokens = spec.field("heading_tokens").unwrap();
    assert!(heading_tokens.attribute);
    assert!(heading_tokens.fast_search);
    assert!(!heading_tokens.indexed);

    let page_start = spec.field("page_start").unwrap();
    assert!(page_start.attribute);
    assert!(!page_start.required);

    let page_numbers = spec.field("page_numbers").unwrap();
    assert!(page_numbers.attribute);
    assert!(!page_numbers.fast_search);
    assert!(!page_numbers.required);

    let authority_type = spec.field("authority_type").unwrap();
    assert!(authority_type.attribute);
    assert!(authority_type.fast_search);
    assert!(authority_type.required);

    let authority_rank = spec.field("authority_rank").unwrap();
    assert_eq!(authority_rank.vespa_type, "int");
    assert!(authority_rank.attribute);
    assert!(authority_rank.required);

    let court_rank = spec.field("court_rank").unwrap();
    assert!(court_rank.attribute);
    assert!(!court_rank.required);

    let instrument_status = spec.field("instrument_status").unwrap();
    assert!(instrument_status.attribute);
    assert!(instrument_status.fast_search);
    assert!(instrument_status.required);

    let is_current = spec.field("is_current").unwrap();
    assert_eq!(is_current.vespa_type, "int");
    assert!(is_current.attribute);
    assert!(is_current.required);

    let version_date_days = spec.field("version_date_days").unwrap();
    assert_eq!(version_date_days.vespa_type, "int");
    assert!(version_date_days.attribute);
    assert!(!version_date_days.fast_search);
    assert!(!version_date_days.required);

    let locator_confidence = spec.field("locator_confidence").unwrap();
    assert!(locator_confidence.attribute);
    assert!(!locator_confidence.required);

    let dense_embedding = spec.field("dense_embedding_v1_256_native").unwrap();
    assert_eq!(dense_embedding.vespa_type, "tensor<bfloat16>(x[256])");
    assert!(dense_embedding.indexed);
    assert!(dense_embedding.attribute);
    assert!(!dense_embedding.summary);
    assert!(!dense_embedding.required);

    assert!(
        spec.rank_profiles
            .iter()
            .any(|profile| profile.name == "dense_v1_256_native_recall")
    );
    assert!(
        spec.rank_profiles
            .iter()
            .any(|profile| profile.name == "legal_answer_candidate_v1")
    );
}

#[test]
fn projects_dense_embeddings_to_vespa_tensor_feed() {
    let mut section = privacy_act_section();
    section.dense_embedding = Some(unit_embedding_256(0));

    let section_put = legal_section_doc_put(&section).unwrap();
    assert!(section_put["fields"].get("dense_embedding").is_none());
    let values = section_put["fields"]["dense_embedding_v1_256_native"]
        .as_array()
        .expect("dense embedding must use Vespa indexed tensor short form");

    assert_eq!(values.len(), 256);
    assert_eq!(values[0], 1.0);
    assert_eq!(values[255], 0.0);
}

#[test]
fn dense_embedding_preflight_rejects_bad_dimension_and_norm() {
    let mut short = privacy_act_section();
    short.dense_embedding = Some(vec![0.0; 255]);
    let mut unnormalised = privacy_act_section();
    unnormalised.doc_id = "federal_register_of_legislation:C2026C00227:section:0057-15".to_string();
    unnormalised.section_ordinal = 57;
    unnormalised.section_number = "15".to_string();
    unnormalised.dense_embedding = Some(vec![0.5; 256]);

    let report = preflight_legal_section_documents(&[short, unnormalised]);

    assert!(report.has_errors());
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_DENSE_VECTOR_DIMENSION_MISMATCH"
            && diagnostic.path == "sections[0].dense_embedding"
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_DENSE_VECTOR_NORM_OUT_OF_RANGE"
            && diagnostic.path == "sections[1].dense_embedding"
    }));
}

#[test]
fn legal_section_preflight_reports_explicit_failures_before_feed() {
    let mut valid = privacy_act_section();
    let duplicate = valid.clone();
    valid.body_text = " ".to_string();
    valid.section_ordinal = 0;

    let report = preflight_legal_section_documents(&[valid, duplicate.clone(), duplicate]);

    assert!(report.has_errors());
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_REQUIRED_FIELD_EMPTY"
            && diagnostic.path == "sections[0].body_text"
            && diagnostic.fix.contains("body_text")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "sections[0].section_ordinal"
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_DUPLICATE_DOCUMENT_ID"
            && diagnostic.source_id.as_deref()
                == Some("federal_register_of_legislation:C2026C00227:section:0056-14")
    }));
}

#[test]
fn feed_value_preflight_reports_type_mismatches_and_unknown_fields() {
    let feed = serde_json::json!({
        "put": "id:legal:legal_doc::bad-section",
        "fields": {
            "doc_id": "bad-section",
            "parent_doc_id": "parent",
            "version_id": "v1",
            "chunk_type": "section",
            "section_ordinal": "56",
            "section_number": "14",
            "section_tokens": ["14"],
            "section_heading": "Australian Privacy Principles",
            "heading_tokens": "privacy",
            "citation": "Privacy Act 1988 s 14",
            "citation_tokens": ["privacy", "act", "1988", "s", "14"],
            "title": "Privacy Act 1988 s 14",
            "title_tokens": ["privacy", "act", "1988", "s", "14"],
            "jurisdiction": "commonwealth",
            "doc_type": "act",
            "source": "federal_register_of_legislation",
            "authority_type": 12,
            "authority_rank": "90",
            "instrument_status": "current",
            "is_current": "true",
            "version_date_days": "20260604",
            "source_format": "pdf",
            "source_locator": "p. 56",
            "page_start": "56",
            "page_numbers": ["56"],
            "locator_confidence": "exact",
            "body_text": "body",
            "dense_embedding_v1_256_native": [0.0, 1.0],
            "surprise_field": true
        }
    });

    let report = preflight_legal_section_feed_values(&[feed]);

    assert!(report.has_errors());
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.section_ordinal"
            && diagnostic.fix.contains("int")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.heading_tokens"
            && diagnostic.fix.contains("array<string>")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.page_start"
            && diagnostic.fix.contains("int")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.page_numbers"
            && diagnostic.fix.contains("array<int>")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.authority_type"
            && diagnostic.fix.contains("string")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.authority_rank"
            && diagnostic.fix.contains("int")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.is_current"
            && diagnostic.fix.contains("int")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FEED_TYPE_MISMATCH"
            && diagnostic.path == "feed[0].fields.dense_embedding_v1_256_native"
            && diagnostic.fix.contains("tensor<bfloat16>(x[256])")
    }));
    assert!(report.diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "W_UNKNOWN_FEED_FIELD"
            && diagnostic.path == "feed[0].fields.surprise_field"
    }));
}

#[test]
fn compares_real_sd_schema_against_memtruth_preset() {
    let source = include_str!("testdata/legal_doc.sd");
    let actual = parse_vespa_schema(source).unwrap();
    let diagnostics = compare_vespa_schema_spec(&legal_section_schema_spec(), &actual);

    assert!(diagnostics.is_empty(), "{diagnostics:#?}");
}

#[test]
fn schema_compare_reports_drift_with_explicit_fix() {
    let drifted = r#"
schema legal_doc {
    document legal_doc {
        field body_text type string {
            indexing: summary | attribute
        }
    }

    fieldset default {
        fields: body_text
    }
}
"#;

    let actual = parse_vespa_schema(drifted).unwrap();
    let diagnostics = compare_vespa_schema_spec(&legal_section_schema_spec(), &actual);

    assert!(diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_SCHEMA_FIELD_MISSING"
            && diagnostic.path == "schema.legal_doc.fields.doc_id"
    }));
    assert!(diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_SCHEMA_FIELD_ROLE_MISMATCH"
            && diagnostic.path == "schema.legal_doc.fields.body_text.indexed"
            && diagnostic.fix.contains("indexing")
    }));
    assert!(diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_SCHEMA_RANK_PROFILE_MISSING"
            && diagnostic.path == "schema.legal_doc.rank_profiles.bm25_recall"
    }));
}

#[test]
fn query_preflight_reports_rank_filter_lane_and_rerank_misuse() {
    let query = VespaQuerySpec {
        yql: Some("select * from legal_doc where userQuery()".to_string()),
        ranking: "missing_rank".to_string(),
        filters: vec![VespaQueryFilter {
            field: "url".to_string(),
            operator: "equals".to_string(),
        }],
        lanes: vec!["bm25".to_string(), "sparse".to_string()],
        rerank_top_k: Some(50),
        total_target_hits: Some(10),
    };

    let diagnostics = preflight_vespa_query(&legal_section_schema_spec(), &query);

    assert!(diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_QUERY_RANK_PROFILE_MISSING" && diagnostic.path == "query.ranking"
    }));
    assert!(diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_FILTER_FIELD_NOT_ATTRIBUTE"
            && diagnostic.path == "query.filters[0].field"
    }));
    assert!(diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_QUERY_USES_DISABLED_LANE" && diagnostic.path == "query.lanes[1]"
    }));
    assert!(diagnostics.iter().any(|diagnostic| {
        diagnostic.code == "E_RERANK_TOPK_EXCEEDS_PROFILE"
            && diagnostic.path == "query.rerank_top_k"
    }));
}

fn privacy_act_section() -> LegalSectionDoc {
    LegalSectionDoc {
        doc_id: "federal_register_of_legislation:C2026C00227:section:0056-14".to_string(),
        parent_doc_id: "federal_register_of_legislation:C2026C00227".to_string(),
        version_id: "C2026C00227".to_string(),
        section_ordinal: 56,
        section_number: "14".to_string(),
        section_heading: "Australian Privacy Principles".to_string(),
        part_label: "Part III-Information privacy".to_string(),
        division_label: "Division 2-Australian Privacy Principles".to_string(),
        subdivision_label: None,
        citation: "Privacy Act 1988 (Cth) C2026C00227 s 14".to_string(),
        title: "Privacy Act 1988 s 14 - Australian Privacy Principles".to_string(),
        jurisdiction: "commonwealth".to_string(),
        doc_type: "act".to_string(),
        source: "federal_register_of_legislation".to_string(),
        authority_type: "legislation".to_string(),
        authority_rank: 90,
        court_name: None,
        court_rank: None,
        instrument_status: "current".to_string(),
        is_current: true,
        version_date_days: Some(20260604),
        effective_from_days: None,
        effective_to_days: None,
        decision_date: Some("2026-06-04".to_string()),
        url: Some("https://www.legislation.gov.au/C2004A03712/latest".to_string()),
        source_locator: None,
        page_start: None,
        page_end: None,
        page_numbers: Vec::new(),
        char_start: None,
        char_end: None,
        source_format: None,
        locator_confidence: None,
        body_text: "14 Australian Privacy Principles\nThe Australian Privacy Principles are set out in Schedule 1.".to_string(),
        dense_embedding: None,
    }
}

fn unit_embedding_256(active_dimension: usize) -> Vec<f32> {
    let mut embedding = vec![0.0; 256];
    embedding[active_dimension] = 1.0;
    embedding
}
