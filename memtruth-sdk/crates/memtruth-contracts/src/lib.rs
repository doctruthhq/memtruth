use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ContractError {
    #[error("{field} is required")]
    Missing { field: &'static str },
    #[error("{field} must be greater than zero")]
    NonPositive { field: &'static str },
    #[error("{field} range is invalid")]
    InvalidRange { field: &'static str },
}

pub type Result<T> = std::result::Result<T, ContractError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChunkLevel {
    Parent,
    Passage,
}

impl ChunkLevel {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Parent => "parent",
            Self::Passage => "passage",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CoverageStatus {
    Supported,
    Adjacent,
    Planned,
    Unsupported,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceKind {
    Legislation,
    CaseLaw,
    TribunalDecision,
    Bill,
    RegulatorGuidance,
    CorpusDataset,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceParserKind {
    FederalRegisterActV1,
    FederalRegisterBillV1,
    OalcJsonlV1,
    OaicGuidanceV1,
    CourtDecisionHtmlV1,
    NswCaselawV1,
    Unspecified,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CorpusSource {
    pub source_family: String,
    pub display_name: String,
    pub jurisdiction: String,
    pub source_kind: SourceKind,
    pub parser: SourceParserKind,
    pub coverage_status: CoverageStatus,
    pub canonical_url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title_id: Option<String>,
    #[serde(default)]
    pub notes: String,
}

impl CorpusSource {
    pub fn validate(&self) -> Result<()> {
        require_non_empty("source_family", &self.source_family)?;
        require_non_empty("display_name", &self.display_name)?;
        require_non_empty("jurisdiction", &self.jurisdiction)?;
        require_non_empty("canonical_url", &self.canonical_url)?;
        if self.coverage_status == CoverageStatus::Supported
            && self.parser == SourceParserKind::Unspecified
        {
            return Err(ContractError::Missing { field: "parser" });
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LegalCleanText {
    pub text: String,
    pub line_count: usize,
    pub changed: bool,
}

impl LegalCleanText {
    pub fn validate(&self) -> Result<()> {
        require_non_empty("text", &self.text)?;
        if self.line_count == 0 {
            return Err(ContractError::NonPositive {
                field: "line_count",
            });
        }
        Ok(())
    }
}

pub fn clean_legal_text(value: &str) -> LegalCleanText {
    let original = value;
    let decoded = decode_common_html_entities(value.trim_start_matches('\u{feff}'))
        .replace('\u{00a0}', " ")
        .replace(
            [
                '\u{2010}', '\u{2011}', '\u{2012}', '\u{2013}', '\u{2014}', '\u{2212}',
            ],
            "-",
        )
        .replace("\r\n", "\n")
        .replace('\r', "\n");

    let mut lines = Vec::new();
    let mut previous_blank = false;
    for raw_line in decoded.lines() {
        let line = collapse_inline_whitespace(raw_line);
        if line.is_empty() {
            if !previous_blank && !lines.is_empty() {
                lines.push(String::new());
            }
            previous_blank = true;
        } else {
            lines.push(line);
            previous_blank = false;
        }
    }
    while lines.last().is_some_and(|line| line.is_empty()) {
        lines.pop();
    }

    let text = lines.join("\n");
    let line_count = lines.iter().filter(|line| !line.is_empty()).count();
    LegalCleanText {
        changed: text != original,
        text,
        line_count,
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AnchorKind {
    Act,
    SourceId,
    Section,
    App,
    Chapter,
    CaseCitation,
    RegulatorGuidance,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum IntentKind {
    RiskAdvice,
    ComplianceChecklist,
    Explain,
    Summarize,
    RawView,
    LegalVsMarketRisk,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct QueryAnchor {
    pub kind: AnchorKind,
    pub value: String,
    pub confidence: f32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct QuerySignals {
    pub raw_query: String,
    pub semantic_text: String,
    #[serde(default)]
    pub exact_anchors: Vec<QueryAnchor>,
    #[serde(default)]
    pub scope_anchors: Vec<QueryAnchor>,
    #[serde(default)]
    pub inferred_domains: Vec<String>,
    #[serde(default)]
    pub business_context: Vec<String>,
    #[serde(default)]
    pub intents: Vec<IntentKind>,
    #[serde(default)]
    pub expansion_terms: Vec<String>,
    #[serde(default)]
    pub wants_hard_filter: bool,
    pub confidence: f32,
}

impl QuerySignals {
    pub fn fallback(raw_query: impl Into<String>) -> Self {
        let raw_query = raw_query.into();
        Self {
            semantic_text: raw_query.clone(),
            raw_query,
            exact_anchors: vec![],
            scope_anchors: vec![],
            inferred_domains: vec![],
            business_context: vec![],
            intents: vec![IntentKind::Unknown],
            expansion_terms: vec![],
            wants_hard_filter: false,
            confidence: 0.0,
        }
    }

    pub fn validate(&self) -> Result<()> {
        require_non_empty("raw_query", &self.raw_query)?;
        require_non_empty("semantic_text", &self.semantic_text)?;
        require_probability("confidence", self.confidence)?;
        for anchor in &self.exact_anchors {
            anchor.validate("exact_anchors")?;
        }
        for anchor in &self.scope_anchors {
            anchor.validate("scope_anchors")?;
        }
        Ok(())
    }
}

impl QueryAnchor {
    fn validate(&self, field_prefix: &'static str) -> Result<()> {
        require_non_empty(prefixed_field(field_prefix, "value"), &self.value)?;
        require_probability(prefixed_field(field_prefix, "confidence"), self.confidence)?;
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QueryUnderstandingTrace {
    pub rule_extractor_used: bool,
    pub llm_used: bool,
    pub fallback_used: bool,
    pub elapsed_ms: u64,
    #[serde(default)]
    pub warnings: Vec<String>,
}

impl QueryUnderstandingTrace {
    pub fn validate(&self) -> Result<()> {
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SearchQualitySignal {
    pub attempt: u32,
    pub top_k: u32,
    pub evidence_count: u32,
    pub top_score: f32,
    pub coverage: f32,
    pub should_retry: bool,
    pub reason: String,
}

impl SearchQualitySignal {
    pub fn validate(&self) -> Result<()> {
        if self.attempt == 0 {
            return Err(ContractError::NonPositive { field: "attempt" });
        }
        if self.top_k == 0 {
            return Err(ContractError::NonPositive { field: "top_k" });
        }
        require_probability("top_score", self.top_score)?;
        require_probability("coverage", self.coverage)?;
        require_non_empty("reason", &self.reason)?;
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Document {
    pub doc_id: String,
    pub version_id: String,
    pub citation: String,
    pub title: String,
    pub jurisdiction: String,
    pub doc_type: String,
    pub source: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub decision_date: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub language: Option<String>,
    #[serde(default = "empty_metadata")]
    pub metadata: serde_json::Value,
}

impl Document {
    pub fn validate(&self) -> Result<()> {
        require_non_empty("doc_id", &self.doc_id)?;
        require_non_empty("version_id", &self.version_id)?;
        require_non_empty("citation", &self.citation)?;
        require_non_empty("title", &self.title)?;
        require_non_empty("jurisdiction", &self.jurisdiction)?;
        require_non_empty("doc_type", &self.doc_type)?;
        require_non_empty("source", &self.source)?;
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Chunk {
    pub chunk_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_chunk_id: Option<String>,
    pub doc_id: String,
    pub chunk_level: ChunkLevel,
    pub chunk_index: usize,
    #[serde(default)]
    pub section_path: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub heading: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub para_start: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub para_end: Option<u32>,
    pub char_start: usize,
    pub char_end: usize,
    pub token_count: usize,
    pub text: String,
    pub rerank_text: String,
    #[serde(default = "empty_metadata")]
    pub metadata: serde_json::Value,
}

impl Chunk {
    pub fn validate(&self) -> Result<()> {
        require_non_empty("chunk_id", &self.chunk_id)?;
        require_non_empty("doc_id", &self.doc_id)?;
        require_non_empty("text", &self.text)?;
        require_non_empty("rerank_text", &self.rerank_text)?;
        if self.char_end < self.char_start {
            return Err(ContractError::InvalidRange {
                field: "char_range",
            });
        }
        if self.token_count == 0 {
            return Err(ContractError::NonPositive {
                field: "token_count",
            });
        }
        Ok(())
    }
}

pub fn stable_chunk_id(doc_id: &str, level: ChunkLevel, chunk_index: usize) -> String {
    let slug = slugify_identifier(doc_id);
    let stable_doc_id = if slug.is_empty() {
        format!("hash-{}", &stable_hash(doc_id)[..12])
    } else {
        slug
    };
    format!("{}::{}::{chunk_index:04}", stable_doc_id, level.as_str())
}

pub fn stable_hash(value: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(value.as_bytes());
    format!("{:x}", hasher.finalize())
}

fn require_non_empty(field: &'static str, value: &str) -> Result<()> {
    if value.trim().is_empty() {
        Err(ContractError::Missing { field })
    } else {
        Ok(())
    }
}

fn require_probability(field: &'static str, value: f32) -> Result<()> {
    if value.is_finite() && (0.0..=1.0).contains(&value) {
        Ok(())
    } else {
        Err(ContractError::InvalidRange { field })
    }
}

fn prefixed_field(prefix: &'static str, field: &'static str) -> &'static str {
    match (prefix, field) {
        ("exact_anchors", "value") => "exact_anchors.value",
        ("exact_anchors", "confidence") => "exact_anchors.confidence",
        ("scope_anchors", "value") => "scope_anchors.value",
        ("scope_anchors", "confidence") => "scope_anchors.confidence",
        _ => field,
    }
}

fn empty_metadata() -> serde_json::Value {
    serde_json::json!({})
}

fn decode_common_html_entities(value: &str) -> String {
    value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&ndash;", "-")
        .replace("&mdash;", "-")
        .replace("&rsquo;", "'")
        .replace("&lsquo;", "'")
        .replace("&rdquo;", "\"")
        .replace("&ldquo;", "\"")
}

fn collapse_inline_whitespace(value: &str) -> String {
    let mut output = String::new();
    let mut previous_space = false;
    for ch in value.trim().chars() {
        if ch.is_whitespace() {
            if !previous_space {
                output.push(' ');
                previous_space = true;
            }
        } else {
            output.push(ch);
            previous_space = false;
        }
    }
    output
}

fn slugify_identifier(value: &str) -> String {
    let mut output = String::new();
    let mut last_was_dash = false;
    for ch in value.chars().flat_map(char::to_lowercase) {
        if ch.is_ascii_alphanumeric() {
            output.push(ch);
            last_was_dash = false;
        } else if !last_was_dash {
            output.push('-');
            last_was_dash = true;
        }
    }
    output.trim_matches('-').to_string()
}
