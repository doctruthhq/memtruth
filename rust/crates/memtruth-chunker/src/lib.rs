use memtruth_contracts::{Chunk, ChunkLevel, Document, stable_chunk_id};
use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ChunkerError {
    #[error("invalid chunk profile: {0}")]
    InvalidProfile(&'static str),
    #[error("document contract failed: {0}")]
    InvalidDocument(String),
}

pub type Result<T> = std::result::Result<T, ChunkerError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChunkProfile {
    pub parent_min_tokens: usize,
    pub parent_max_tokens: usize,
    pub passage_max_tokens: usize,
    pub passage_overlap_tokens: usize,
    pub rerank_prefix_max_tokens: usize,
}

impl Default for ChunkProfile {
    fn default() -> Self {
        Self {
            parent_min_tokens: 500,
            parent_max_tokens: 1_000,
            passage_max_tokens: 256,
            passage_overlap_tokens: 40,
            rerank_prefix_max_tokens: 48,
        }
    }
}

impl ChunkProfile {
    fn validate(self) -> Result<()> {
        if self.parent_min_tokens == 0 || self.parent_max_tokens == 0 {
            return Err(ChunkerError::InvalidProfile(
                "parent token budget must be positive",
            ));
        }
        if self.parent_min_tokens > self.parent_max_tokens {
            return Err(ChunkerError::InvalidProfile("parent min cannot exceed max"));
        }
        if self.passage_max_tokens == 0 {
            return Err(ChunkerError::InvalidProfile("passage max must be positive"));
        }
        if self.passage_overlap_tokens >= self.passage_max_tokens {
            return Err(ChunkerError::InvalidProfile(
                "passage overlap must be smaller than passage max",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParagraphBlock {
    pub section_path: Vec<String>,
    pub heading: Option<String>,
    pub paragraph_number: Option<u32>,
    pub text: String,
}

impl ParagraphBlock {
    pub fn new(
        section_path: Vec<String>,
        heading: Option<String>,
        paragraph_number: Option<u32>,
        text: String,
    ) -> Self {
        Self {
            section_path,
            heading,
            paragraph_number,
            text,
        }
    }
}

pub struct LegalChunker {
    profile: ChunkProfile,
}

impl LegalChunker {
    pub fn new(profile: ChunkProfile) -> Self {
        Self { profile }
    }

    pub fn chunk_document(
        &self,
        document: &Document,
        blocks: &[ParagraphBlock],
    ) -> Result<Vec<Chunk>> {
        self.profile.validate()?;
        document
            .validate()
            .map_err(|error| ChunkerError::InvalidDocument(error.to_string()))?;

        let mut drafts = self.passage_drafts(blocks);
        let parents = self.assign_parent_chunks(document, &mut drafts);
        let mut chunks = parents;
        for (index, draft) in drafts.into_iter().enumerate() {
            chunks.push(Chunk {
                chunk_id: stable_chunk_id(&document.doc_id, ChunkLevel::Passage, index),
                parent_chunk_id: draft.parent_chunk_id,
                doc_id: document.doc_id.clone(),
                chunk_level: ChunkLevel::Passage,
                chunk_index: index,
                section_path: draft.section_path,
                heading: draft.heading,
                para_start: draft.paragraph_number,
                para_end: draft.paragraph_number,
                char_start: draft.char_start,
                char_end: draft.char_end,
                token_count: count_tokens(&draft.text),
                rerank_text: build_rerank_text(
                    document,
                    &draft.text,
                    &draft.rerank_context,
                    self.profile,
                ),
                text: draft.text,
                metadata: chunk_metadata(),
            });
        }
        Ok(chunks)
    }

    fn passage_drafts(&self, blocks: &[ParagraphBlock]) -> Vec<PassageDraft> {
        let mut drafts = Vec::new();
        let mut char_cursor = 0;

        for block in blocks {
            let tokens = block.text.split_whitespace().collect::<Vec<_>>();
            if tokens.is_empty() {
                continue;
            }
            let block_start = char_cursor;
            let normalized_text = tokens.join(" ");
            char_cursor += normalized_text.chars().count() + 1;

            let mut start = 0;
            while start < tokens.len() {
                let end = (start + self.profile.passage_max_tokens).min(tokens.len());
                let text = tokens[start..end].join(" ");
                let char_start = block_start + token_char_offset(&tokens, start);
                let char_end = char_start + text.chars().count();
                drafts.push(PassageDraft {
                    section_path: block.section_path.clone(),
                    heading: block.heading.clone(),
                    paragraph_number: block.paragraph_number,
                    char_start,
                    char_end,
                    text,
                    rerank_context: block.section_path.clone(),
                    parent_chunk_id: None,
                });
                if end >= tokens.len() {
                    break;
                }
                start = end.saturating_sub(self.profile.passage_overlap_tokens);
            }
        }

        drafts
    }

    fn assign_parent_chunks(&self, document: &Document, drafts: &mut [PassageDraft]) -> Vec<Chunk> {
        let mut parents = Vec::new();
        let mut start = 0;

        while start < drafts.len() {
            let mut end = start;
            let mut token_count = 0;
            let section_path = drafts[start].section_path.clone();
            while end < drafts.len() {
                if end > start && drafts[end].section_path != section_path {
                    break;
                }
                let next = count_tokens(&drafts[end].text);
                if end > start && token_count + next > self.profile.parent_max_tokens {
                    break;
                }
                token_count += next;
                end += 1;
                if token_count >= self.profile.parent_min_tokens {
                    break;
                }
            }
            if end == start {
                end += 1;
            }

            let parent_index = parents.len();
            let parent_id = stable_chunk_id(&document.doc_id, ChunkLevel::Parent, parent_index);
            for draft in &mut drafts[start..end] {
                draft.parent_chunk_id = Some(parent_id.clone());
            }
            let text = drafts[start..end]
                .iter()
                .map(|draft| draft.text.as_str())
                .collect::<Vec<_>>()
                .join("\n");
            let first = &drafts[start];
            let last = &drafts[end - 1];
            parents.push(Chunk {
                chunk_id: parent_id,
                parent_chunk_id: None,
                doc_id: document.doc_id.clone(),
                chunk_level: ChunkLevel::Parent,
                chunk_index: parent_index,
                section_path: first.section_path.clone(),
                heading: first.heading.clone(),
                para_start: first.paragraph_number,
                para_end: last.paragraph_number,
                char_start: first.char_start,
                char_end: last.char_end,
                token_count: count_tokens(&text),
                rerank_text: build_rerank_text(document, &text, &first.section_path, self.profile),
                text,
                metadata: chunk_metadata(),
            });
            start = end;
        }

        parents
    }
}

#[derive(Debug, Clone)]
struct PassageDraft {
    section_path: Vec<String>,
    heading: Option<String>,
    paragraph_number: Option<u32>,
    char_start: usize,
    char_end: usize,
    text: String,
    rerank_context: Vec<String>,
    parent_chunk_id: Option<String>,
}

fn build_rerank_text(
    document: &Document,
    text: &str,
    section_path: &[String],
    profile: ChunkProfile,
) -> String {
    let mut prefix = vec![document.citation.clone()];
    let section = section_path.join(" > ");
    if !section.is_empty() {
        prefix.push(section);
    }
    let prefix_text = prefix.join(". ");
    let bounded_prefix = prefix_text
        .split_whitespace()
        .take(profile.rerank_prefix_max_tokens)
        .collect::<Vec<_>>()
        .join(" ");
    format!("{bounded_prefix}. {text}")
}

fn count_tokens(text: &str) -> usize {
    text.split_whitespace().count()
}

fn token_char_offset(tokens: &[&str], token_index: usize) -> usize {
    tokens
        .iter()
        .take(token_index)
        .map(|token| token.chars().count() + 1)
        .sum()
}

fn chunk_metadata() -> serde_json::Value {
    serde_json::json!({
        "char_offset_basis": "normalized_text",
    })
}
