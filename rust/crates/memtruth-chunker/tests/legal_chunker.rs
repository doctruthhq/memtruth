use memtruth_chunker::{ChunkProfile, LegalChunker, ParagraphBlock};
use memtruth_contracts::{ChunkLevel, Document};

fn doc() -> Document {
    Document {
        doc_id: "fca:2024:123".to_string(),
        version_id: "v1".to_string(),
        citation: "[2024] FCA 123".to_string(),
        title: "Example v Minister".to_string(),
        jurisdiction: "federal".to_string(),
        doc_type: "judgment".to_string(),
        source: "oalc".to_string(),
        decision_date: None,
        url: None,
        language: Some("en".to_string()),
        metadata: serde_json::json!({}),
    }
}

fn words(count: usize, prefix: &str) -> String {
    (0..count)
        .map(|index| format!("{prefix}{index}"))
        .collect::<Vec<_>>()
        .join(" ")
}

#[test]
fn creates_parent_and_passage_chunks_with_legal_structure() {
    let profile = ChunkProfile {
        parent_min_tokens: 40,
        parent_max_tokens: 70,
        passage_max_tokens: 24,
        passage_overlap_tokens: 4,
        rerank_prefix_max_tokens: 12,
    };
    let blocks = vec![
        ParagraphBlock::new(
            vec!["Reasons".to_string(), "Procedural fairness".to_string()],
            Some("Procedural fairness".to_string()),
            Some(81),
            words(55, "fairness"),
        ),
        ParagraphBlock::new(
            vec!["Reasons".to_string(), "Orders".to_string()],
            Some("Orders".to_string()),
            Some(82),
            words(16, "order"),
        ),
    ];

    let chunks = LegalChunker::new(profile)
        .chunk_document(&doc(), &blocks)
        .unwrap();
    let parent_count = chunks
        .iter()
        .filter(|chunk| chunk.chunk_level == ChunkLevel::Parent)
        .count();
    let passages = chunks
        .iter()
        .filter(|chunk| chunk.chunk_level == ChunkLevel::Passage)
        .collect::<Vec<_>>();

    assert!(parent_count >= 1);
    assert!(passages.len() >= 3, "long paragraph should be split");
    assert!(passages.iter().all(|chunk| chunk.token_count <= 24));
    assert!(passages.iter().all(|chunk| chunk.parent_chunk_id.is_some()));
    assert!(
        passages
            .iter()
            .any(|chunk| chunk.section_path == ["Reasons", "Procedural fairness"])
    );
    assert!(
        passages
            .iter()
            .all(|chunk| chunk.rerank_text.starts_with("[2024] FCA 123."))
    );
}

#[test]
fn parent_chunks_do_not_cross_section_boundaries() {
    let profile = ChunkProfile {
        parent_min_tokens: 80,
        parent_max_tokens: 120,
        passage_max_tokens: 40,
        passage_overlap_tokens: 5,
        rerank_prefix_max_tokens: 12,
    };
    let blocks = vec![
        ParagraphBlock::new(
            vec!["Part III".to_string(), "Section 14".to_string()],
            Some("Australian Privacy Principles".to_string()),
            Some(14),
            words(30, "privacy"),
        ),
        ParagraphBlock::new(
            vec!["Part III".to_string(), "Section 15".to_string()],
            Some("Compliance with APPs".to_string()),
            Some(15),
            words(30, "compliance"),
        ),
    ];

    let chunks = LegalChunker::new(profile)
        .chunk_document(&doc(), &blocks)
        .unwrap();
    let parents = chunks
        .iter()
        .filter(|chunk| chunk.chunk_level == ChunkLevel::Parent)
        .collect::<Vec<_>>();

    assert_eq!(parents.len(), 2, "each legal section needs its own parent");
    assert_eq!(
        parents[0].section_path,
        ["Part III".to_string(), "Section 14".to_string()]
    );
    assert_eq!(parents[0].para_start, Some(14));
    assert_eq!(parents[0].para_end, Some(14));
    assert_eq!(
        parents[1].section_path,
        ["Part III".to_string(), "Section 15".to_string()]
    );
    assert_eq!(parents[1].para_start, Some(15));
    assert_eq!(parents[1].para_end, Some(15));
}

#[test]
fn char_offsets_use_normalized_text_basis() {
    let profile = ChunkProfile {
        parent_min_tokens: 10,
        parent_max_tokens: 20,
        passage_max_tokens: 2,
        passage_overlap_tokens: 0,
        rerank_prefix_max_tokens: 12,
    };
    let blocks = vec![ParagraphBlock::new(
        vec!["Reasons".to_string()],
        Some("Reasons".to_string()),
        Some(1),
        "alpha   beta\n\tgamma".to_string(),
    )];

    let chunks = LegalChunker::new(profile)
        .chunk_document(&doc(), &blocks)
        .unwrap();
    let parent = chunks
        .iter()
        .find(|chunk| chunk.chunk_level == ChunkLevel::Parent)
        .unwrap();
    let passages = chunks
        .iter()
        .filter(|chunk| chunk.chunk_level == ChunkLevel::Passage)
        .collect::<Vec<_>>();

    assert_eq!(passages.len(), 2);
    assert_eq!(passages[0].text, "alpha beta");
    assert_eq!((passages[0].char_start, passages[0].char_end), (0, 10));
    assert_eq!(passages[1].text, "gamma");
    assert_eq!((passages[1].char_start, passages[1].char_end), (11, 16));
    assert_eq!((parent.char_start, parent.char_end), (0, 16));
    assert_eq!(parent.metadata["char_offset_basis"], "normalized_text");
    assert!(
        passages
            .iter()
            .all(|chunk| chunk.metadata["char_offset_basis"] == "normalized_text")
    );
}
