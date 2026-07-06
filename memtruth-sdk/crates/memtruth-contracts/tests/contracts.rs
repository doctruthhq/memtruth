use memtruth_contracts::{
    AnchorKind, Chunk, ChunkLevel, CorpusSource, CoverageStatus, Document, IntentKind,
    LegalCleanText, QueryAnchor, QuerySignals, QueryUnderstandingTrace, SearchQualitySignal,
    SourceKind, SourceParserKind, clean_legal_text, stable_chunk_id, stable_hash,
};

#[test]
fn validates_structured_document_and_chunk_identity() {
    let doc = Document {
        doc_id: "fca:2024:123".to_string(),
        version_id: "v1".to_string(),
        citation: "[2024] FCA 123".to_string(),
        title: "Example v Minister".to_string(),
        jurisdiction: "federal".to_string(),
        doc_type: "judgment".to_string(),
        source: "oalc".to_string(),
        decision_date: Some("2024-01-01".to_string()),
        url: Some("https://example.test/fca/2024/123".to_string()),
        language: Some("en".to_string()),
        metadata: serde_json::json!({"court": "FCA"}),
    };

    doc.validate().expect("valid document");

    let chunk_id = stable_chunk_id(&doc.doc_id, ChunkLevel::Passage, 7);
    let chunk = Chunk {
        chunk_id: chunk_id.clone(),
        parent_chunk_id: Some(stable_chunk_id(&doc.doc_id, ChunkLevel::Parent, 1)),
        doc_id: doc.doc_id.clone(),
        chunk_level: ChunkLevel::Passage,
        chunk_index: 7,
        section_path: vec!["Reasons".to_string(), "Procedural fairness".to_string()],
        heading: Some("Procedural fairness".to_string()),
        para_start: Some(81),
        para_end: Some(84),
        char_start: 1200,
        char_end: 1800,
        token_count: 220,
        text: "The tribunal denied procedural fairness.".to_string(),
        rerank_text: "[2024] FCA 123. Reasons > Procedural fairness. The tribunal denied procedural fairness.".to_string(),
        metadata: serde_json::json!({"court": "FCA"}),
    };

    chunk.validate().expect("valid chunk");
    assert_eq!(chunk_id, "fca-2024-123::passage::0007");
}

#[test]
fn defaults_omitted_metadata_to_empty_object() {
    let doc: Document = serde_json::from_value(serde_json::json!({
        "doc_id": "fca:2024:123",
        "version_id": "v1",
        "citation": "[2024] FCA 123",
        "title": "Example v Minister",
        "jurisdiction": "federal",
        "doc_type": "judgment",
        "source": "oalc"
    }))
    .unwrap();
    assert_eq!(doc.metadata, serde_json::json!({}));

    let chunk: Chunk = serde_json::from_value(serde_json::json!({
        "chunk_id": "fca-2024-123::passage::0007",
        "doc_id": "fca:2024:123",
        "chunk_level": "passage",
        "chunk_index": 7,
        "char_start": 1200,
        "char_end": 1800,
        "token_count": 220,
        "text": "The tribunal denied procedural fairness.",
        "rerank_text": "[2024] FCA 123. The tribunal denied procedural fairness."
    }))
    .unwrap();
    assert_eq!(chunk.metadata, serde_json::json!({}));
}

#[test]
fn stable_chunk_id_uses_hash_fallback_for_empty_slug() {
    let non_ascii_doc_id = "隐私法";
    let expected_non_ascii_prefix = format!("hash-{}", &stable_hash(non_ascii_doc_id)[..12]);
    assert_eq!(
        stable_chunk_id(non_ascii_doc_id, ChunkLevel::Passage, 3),
        format!("{expected_non_ascii_prefix}::passage::0003")
    );

    let punctuation_doc_id = "!!!";
    let expected_punctuation_prefix = format!("hash-{}", &stable_hash(punctuation_doc_id)[..12]);
    assert_eq!(
        stable_chunk_id(punctuation_doc_id, ChunkLevel::Parent, 1),
        format!("{expected_punctuation_prefix}::parent::0001")
    );
}

#[test]
fn cleans_legal_text_without_losing_section_structure() {
    let raw = "\u{feff}  Part 1&nbsp;&nbsp;Preliminary\r\n\r\n\r\n6\u{00a0}Personal information &amp; records\u{2014}meaning  ";
    let cleaned = clean_legal_text(raw);

    assert_eq!(
        cleaned.text,
        "Part 1 Preliminary\n\n6 Personal information & records-meaning"
    );
    assert_eq!(cleaned.line_count, 2);
    assert!(cleaned.changed);
    cleaned.validate().unwrap();
}

#[test]
fn rejects_clean_text_without_content() {
    let cleaned = LegalCleanText {
        text: " ".into(),
        line_count: 0,
        changed: false,
    };

    let error = cleaned.validate().unwrap_err();
    assert_eq!(error.to_string(), "text is required");
}

#[test]
fn validates_corpus_source_contract_for_planned_prd_sources() {
    let source = CorpusSource {
        source_family: "family-law".into(),
        display_name: "Family Law Act 1975".into(),
        jurisdiction: "commonwealth".into(),
        source_kind: SourceKind::Legislation,
        parser: SourceParserKind::FederalRegisterActV1,
        coverage_status: CoverageStatus::Planned,
        canonical_url: "https://www.legislation.gov.au/".into(),
        title_id: Some("C2004A00275".into()),
        notes: "Planned PRD expansion; must pass parser smoke before becoming supported.".into(),
    };

    source.validate().unwrap();
    assert_eq!(
        serde_json::to_string(&source.source_kind).unwrap(),
        "\"legislation\""
    );
    assert_eq!(
        serde_json::to_string(&source.coverage_status).unwrap(),
        "\"planned\""
    );
}

#[test]
fn rejects_supported_corpus_source_without_parser_or_url() {
    let source = CorpusSource {
        source_family: "broken".into(),
        display_name: "Broken source".into(),
        jurisdiction: "commonwealth".into(),
        source_kind: SourceKind::Legislation,
        parser: SourceParserKind::Unspecified,
        coverage_status: CoverageStatus::Supported,
        canonical_url: " ".into(),
        title_id: None,
        notes: "".into(),
    };

    let error = source.validate().unwrap_err();
    assert_eq!(error.to_string(), "canonical_url is required");
}

#[test]
fn validates_query_signals_contract() {
    let signals = QuerySignals {
        raw_query: "I run an HR AI SaaS; what privacy and encryption risks matter?".into(),
        semantic_text: "HR AI SaaS privacy encryption security claims legal risk".into(),
        exact_anchors: vec![QueryAnchor {
            kind: AnchorKind::Act,
            value: "Privacy Act 1988".into(),
            confidence: 0.95,
        }],
        scope_anchors: vec![],
        inferred_domains: vec!["privacy".into(), "consumer_law".into()],
        business_context: vec!["HR AI SaaS".into(), "security claims".into()],
        intents: vec![IntentKind::RiskAdvice, IntentKind::ComplianceChecklist],
        expansion_terms: vec!["APP 11".into(), "misleading or deceptive conduct".into()],
        wants_hard_filter: false,
        confidence: 0.86,
    };

    signals.validate().unwrap();

    let trace = QueryUnderstandingTrace {
        rule_extractor_used: true,
        llm_used: true,
        fallback_used: false,
        elapsed_ms: 42,
        warnings: vec![],
    };
    trace.validate().unwrap();

    let quality = SearchQualitySignal {
        attempt: 1,
        top_k: 30,
        evidence_count: 8,
        top_score: 0.87,
        coverage: 0.76,
        should_retry: false,
        reason: "enough evidence coverage".into(),
    };
    quality.validate().unwrap();
}

#[test]
fn rejects_query_signals_without_raw_query() {
    let signals = QuerySignals::fallback("");
    let error = signals.validate().unwrap_err();
    assert_eq!(error.to_string(), "raw_query is required");
}

#[test]
fn fallback_query_signals_preserve_raw_query_defaults() {
    let signals = QuerySignals::fallback("privacy risk for HR AI SaaS");

    assert_eq!(signals.raw_query, "privacy risk for HR AI SaaS");
    assert_eq!(signals.semantic_text, "privacy risk for HR AI SaaS");
    assert_eq!(signals.intents, vec![IntentKind::Unknown]);
    assert_eq!(signals.confidence, 0.0);
}

#[test]
fn rejects_query_signals_without_semantic_text() {
    let mut signals = QuerySignals::fallback("privacy risk");
    signals.semantic_text = " ".into();

    let error = signals.validate().unwrap_err();
    assert_eq!(error.to_string(), "semantic_text is required");
}

#[test]
fn rejects_invalid_query_signal_ranges_and_anchor_values() {
    let mut signals = QuerySignals::fallback("privacy risk");
    signals.confidence = 1.1;
    let error = signals.validate().unwrap_err();
    assert_eq!(error.to_string(), "confidence range is invalid");

    let mut signals = QuerySignals::fallback("privacy risk");
    signals.exact_anchors.push(QueryAnchor {
        kind: AnchorKind::Act,
        value: " ".into(),
        confidence: 0.9,
    });
    let error = signals.validate().unwrap_err();
    assert_eq!(error.to_string(), "exact_anchors.value is required");

    let mut signals = QuerySignals::fallback("privacy risk");
    signals.scope_anchors.push(QueryAnchor {
        kind: AnchorKind::Section,
        value: "APP 11".into(),
        confidence: f32::NAN,
    });
    let error = signals.validate().unwrap_err();
    assert_eq!(
        error.to_string(),
        "scope_anchors.confidence range is invalid"
    );
}

#[test]
fn rejects_invalid_search_quality_signal_fields() {
    let mut quality = valid_search_quality_signal();
    quality.attempt = 0;
    let error = quality.validate().unwrap_err();
    assert_eq!(error.to_string(), "attempt must be greater than zero");

    let mut quality = valid_search_quality_signal();
    quality.top_k = 0;
    let error = quality.validate().unwrap_err();
    assert_eq!(error.to_string(), "top_k must be greater than zero");

    let mut quality = valid_search_quality_signal();
    quality.reason = " ".into();
    let error = quality.validate().unwrap_err();
    assert_eq!(error.to_string(), "reason is required");

    let mut quality = valid_search_quality_signal();
    quality.top_score = -0.1;
    let error = quality.validate().unwrap_err();
    assert_eq!(error.to_string(), "top_score range is invalid");

    let mut quality = valid_search_quality_signal();
    quality.coverage = f32::INFINITY;
    let error = quality.validate().unwrap_err();
    assert_eq!(error.to_string(), "coverage range is invalid");
}

#[test]
fn serializes_query_signal_enums_as_snake_case() {
    assert_eq!(
        serde_json::to_string(&AnchorKind::SourceId).unwrap(),
        "\"source_id\""
    );
    assert_eq!(
        serde_json::to_string(&AnchorKind::CaseCitation).unwrap(),
        "\"case_citation\""
    );
    assert_eq!(
        serde_json::to_string(&AnchorKind::RegulatorGuidance).unwrap(),
        "\"regulator_guidance\""
    );
    assert_eq!(
        serde_json::to_string(&IntentKind::RiskAdvice).unwrap(),
        "\"risk_advice\""
    );
    assert_eq!(
        serde_json::to_string(&IntentKind::ComplianceChecklist).unwrap(),
        "\"compliance_checklist\""
    );
    assert_eq!(
        serde_json::to_string(&IntentKind::LegalVsMarketRisk).unwrap(),
        "\"legal_vs_market_risk\""
    );
}

#[test]
fn deserializes_query_signal_enums_from_snake_case() {
    assert_eq!(
        serde_json::from_str::<AnchorKind>("\"source_id\"").unwrap(),
        AnchorKind::SourceId
    );
    assert_eq!(
        serde_json::from_str::<AnchorKind>("\"case_citation\"").unwrap(),
        AnchorKind::CaseCitation
    );
    assert_eq!(
        serde_json::from_str::<AnchorKind>("\"regulator_guidance\"").unwrap(),
        AnchorKind::RegulatorGuidance
    );
    assert_eq!(
        serde_json::from_str::<IntentKind>("\"risk_advice\"").unwrap(),
        IntentKind::RiskAdvice
    );
    assert_eq!(
        serde_json::from_str::<IntentKind>("\"compliance_checklist\"").unwrap(),
        IntentKind::ComplianceChecklist
    );
    assert_eq!(
        serde_json::from_str::<IntentKind>("\"legal_vs_market_risk\"").unwrap(),
        IntentKind::LegalVsMarketRisk
    );
}

#[test]
fn deserializes_query_signals_with_snake_case_enum_spellings() {
    let signals: QuerySignals = serde_json::from_value(serde_json::json!({
        "raw_query": "privacy risk",
        "semantic_text": "privacy risk",
        "exact_anchors": [{
            "kind": "case_citation",
            "value": "[2024] FCA 123",
            "confidence": 0.7
        }],
        "intents": ["legal_vs_market_risk", "compliance_checklist"],
        "confidence": 0.8
    }))
    .unwrap();

    assert_eq!(signals.exact_anchors[0].kind, AnchorKind::CaseCitation);
    assert_eq!(
        signals.intents,
        vec![
            IntentKind::LegalVsMarketRisk,
            IntentKind::ComplianceChecklist
        ]
    );
    signals.validate().unwrap();
}

fn valid_search_quality_signal() -> SearchQualitySignal {
    SearchQualitySignal {
        attempt: 1,
        top_k: 30,
        evidence_count: 8,
        top_score: 0.87,
        coverage: 0.76,
        should_retry: false,
        reason: "enough evidence coverage".into(),
    }
}
