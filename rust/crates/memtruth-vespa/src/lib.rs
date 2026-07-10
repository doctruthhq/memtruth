use std::collections::{HashMap, HashSet};

use memtruth_contracts::clean_legal_text;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ProjectionError {
    #[error("invalid section: {0}")]
    InvalidSection(String),
    #[error("section field `{0}` is required")]
    MissingSectionField(&'static str),
}

pub type Result<T> = std::result::Result<T, ProjectionError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DiagnosticSeverity {
    Error,
    Warning,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VespaDiagnostic {
    pub code: String,
    pub severity: DiagnosticSeverity,
    pub path: String,
    pub message: String,
    pub fix: String,
    pub source_id: Option<String>,
}

impl VespaDiagnostic {
    pub fn error(
        code: &str,
        path: impl Into<String>,
        message: impl Into<String>,
        fix: impl Into<String>,
        source_id: Option<String>,
    ) -> Self {
        Self {
            code: code.to_string(),
            severity: DiagnosticSeverity::Error,
            path: path.into(),
            message: message.into(),
            fix: fix.into(),
            source_id,
        }
    }

    pub fn warning(
        code: &str,
        path: impl Into<String>,
        message: impl Into<String>,
        fix: impl Into<String>,
        source_id: Option<String>,
    ) -> Self {
        Self {
            code: code.to_string(),
            severity: DiagnosticSeverity::Warning,
            path: path.into(),
            message: message.into(),
            fix: fix.into(),
            source_id,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PreflightReport {
    pub schema: VespaSchemaSpec,
    pub feed_preview: Vec<Value>,
    pub diagnostics: Vec<VespaDiagnostic>,
}

impl PreflightReport {
    pub fn has_errors(&self) -> bool {
        self.diagnostics
            .iter()
            .any(|diagnostic| diagnostic.severity == DiagnosticSeverity::Error)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VespaSchemaSpec {
    pub name: String,
    pub fields: Vec<VespaFieldSpec>,
    pub default_fieldset: Vec<String>,
    pub rank_profiles: Vec<VespaRankProfileSpec>,
}

impl VespaSchemaSpec {
    pub fn field(&self, name: &str) -> Option<&VespaFieldSpec> {
        self.fields.iter().find(|field| field.name == name)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VespaFieldSpec {
    pub name: String,
    pub vespa_type: String,
    pub summary: bool,
    pub indexed: bool,
    pub attribute: bool,
    pub fast_search: bool,
    pub enable_bm25: bool,
    pub required: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VespaRankProfileSpec {
    pub name: String,
    pub bm25_fields: Vec<String>,
    pub global_phase_rerank_count: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VespaQuerySpec {
    #[serde(default)]
    pub yql: Option<String>,
    #[serde(default = "default_ranking")]
    pub ranking: String,
    #[serde(default)]
    pub filters: Vec<VespaQueryFilter>,
    #[serde(default)]
    pub lanes: Vec<String>,
    #[serde(default)]
    pub rerank_top_k: Option<usize>,
    #[serde(default)]
    pub total_target_hits: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VespaQueryFilter {
    pub field: String,
    #[serde(default = "default_filter_operator")]
    pub operator: String,
}

fn default_ranking() -> String {
    "bm25_recall".to_string()
}

fn default_filter_operator() -> String {
    "equals".to_string()
}

#[derive(Debug, Clone, PartialEq)]
pub struct LegalSectionDoc {
    pub doc_id: String,
    pub parent_doc_id: String,
    pub version_id: String,
    pub section_ordinal: i64,
    pub section_number: String,
    pub section_heading: String,
    pub part_label: String,
    pub division_label: String,
    pub subdivision_label: Option<String>,
    pub citation: String,
    pub title: String,
    pub jurisdiction: String,
    pub doc_type: String,
    pub source: String,
    pub authority_type: String,
    pub authority_rank: i64,
    pub court_name: Option<String>,
    pub court_rank: Option<i64>,
    pub instrument_status: String,
    pub is_current: bool,
    pub version_date_days: Option<i64>,
    pub effective_from_days: Option<i64>,
    pub effective_to_days: Option<i64>,
    pub decision_date: Option<String>,
    pub url: Option<String>,
    pub source_locator: Option<String>,
    pub page_start: Option<i64>,
    pub page_end: Option<i64>,
    pub page_numbers: Vec<i64>,
    pub char_start: Option<i64>,
    pub char_end: Option<i64>,
    pub source_format: Option<String>,
    pub locator_confidence: Option<String>,
    pub body_text: String,
    pub dense_embedding: Option<Vec<f32>>,
}

impl LegalSectionDoc {
    fn validate(&self) -> Result<()> {
        require_non_empty("doc_id", &self.doc_id)?;
        require_non_empty("parent_doc_id", &self.parent_doc_id)?;
        require_non_empty("version_id", &self.version_id)?;
        require_positive("section_ordinal", self.section_ordinal)?;
        require_non_empty("section_number", &self.section_number)?;
        require_non_empty("section_heading", &self.section_heading)?;
        require_non_empty("citation", &self.citation)?;
        require_non_empty("title", &self.title)?;
        require_non_empty("jurisdiction", &self.jurisdiction)?;
        require_non_empty("doc_type", &self.doc_type)?;
        require_non_empty("source", &self.source)?;
        require_non_empty("authority_type", &self.authority_type)?;
        require_non_empty("instrument_status", &self.instrument_status)?;
        require_non_negative("authority_rank", self.authority_rank)?;
        require_non_empty("body_text", &self.body_text)?;
        Ok(())
    }
}

pub fn legal_section_schema_spec() -> VespaSchemaSpec {
    VespaSchemaSpec {
        name: "legal_doc".to_string(),
        fields: vec![
            string_field("doc_id", true, false, true, true, false, true),
            string_field("parent_doc_id", true, false, true, true, false, true),
            string_field("version_id", true, false, true, false, false, true),
            string_field("chunk_type", true, false, true, true, false, true),
            int_field("section_ordinal", true, true),
            string_field("section_number", true, false, true, true, false, true),
            string_field("section_number_attr", true, false, true, true, false, false),
            array_string_field("section_tokens", true, false, true, true, false, true),
            string_field("section_heading", true, true, false, false, true, true),
            array_string_field("heading_tokens", true, false, true, true, false, true),
            string_field("part_label", true, false, true, true, false, false),
            string_field("division_label", true, false, true, true, false, false),
            string_field("subdivision_label", true, false, true, false, false, false),
            string_field("citation", true, true, false, false, true, true),
            string_field("citation_attr", true, false, true, true, false, false),
            array_string_field("citation_tokens", true, false, true, true, false, true),
            string_field("title", true, true, false, false, true, true),
            array_string_field("title_tokens", true, false, true, true, false, true),
            string_field("jurisdiction", true, false, true, true, false, true),
            string_field("doc_type", true, false, true, true, false, true),
            string_field("source", true, false, true, true, false, true),
            string_field("authority_type", true, false, true, true, false, true),
            int_field("authority_rank", true, true),
            string_field("court_name", true, false, true, true, false, false),
            int_field("court_rank", true, false),
            string_field("instrument_status", true, false, true, true, false, true),
            int_field("is_current", true, true),
            int_field("version_date_days", true, false),
            int_field("effective_from_days", true, false),
            int_field("effective_to_days", true, false),
            string_field("decision_date", true, false, true, false, false, false),
            string_field("url", true, false, false, false, false, false),
            string_field("source_locator", true, false, false, false, false, false),
            int_field("page_start", true, false),
            int_field("page_end", true, false),
            array_int_field("page_numbers", true, false, true, false, false, false),
            int_field("char_start", true, false),
            int_field("char_end", true, false),
            string_field("source_format", true, false, true, false, false, false),
            string_field("locator_confidence", true, false, true, false, false, false),
            string_field("body_text", true, true, false, false, true, true),
            tensor_field("dense_embedding_v1_256_native", false, true, true, false),
        ],
        default_fieldset: vec![
            "citation".to_string(),
            "title".to_string(),
            "section_heading".to_string(),
            "body_text".to_string(),
        ],
        rank_profiles: vec![
            VespaRankProfileSpec {
                name: "bm25_recall".to_string(),
                bm25_fields: bm25_fields(),
                global_phase_rerank_count: None,
            },
            VespaRankProfileSpec {
                name: "dense_v1_256_native_recall".to_string(),
                bm25_fields: Vec::new(),
                global_phase_rerank_count: None,
            },
            VespaRankProfileSpec {
                name: "neural_sparse_recall".to_string(),
                bm25_fields: Vec::new(),
                global_phase_rerank_count: None,
            },
            VespaRankProfileSpec {
                name: "legal_answer_candidate_v1".to_string(),
                bm25_fields: bm25_fields(),
                global_phase_rerank_count: Some(200),
            },
        ],
    }
}

pub fn validate_vespa_schema_spec(spec: &VespaSchemaSpec) -> Vec<VespaDiagnostic> {
    let mut diagnostics = Vec::new();
    let field_names: HashSet<&str> = spec
        .fields
        .iter()
        .map(|field| field.name.as_str())
        .collect();

    for field in &spec.fields {
        let path = format!("schema.{}.fields.{}", spec.name, field.name);
        if field.enable_bm25 && !field.indexed {
            diagnostics.push(VespaDiagnostic::error(
                "E_BM25_FIELD_NOT_INDEXED",
                path.clone(),
                format!("field `{}` enables BM25 but is not indexed", field.name),
                "Add `indexing: summary | index` and `index: enable-bm25`, or remove BM25 from the rank profile.",
                None,
            ));
        }
        if field.fast_search && !field.attribute {
            diagnostics.push(VespaDiagnostic::error(
                "E_FILTER_FIELD_NOT_ATTRIBUTE",
                path,
                format!(
                    "field `{}` has fast-search without attribute indexing",
                    field.name
                ),
                "Add `indexing: summary | attribute` before using `attribute: fast-search`.",
                None,
            ));
        }
    }

    for field_name in &spec.default_fieldset {
        if !field_names.contains(field_name.as_str()) {
            diagnostics.push(VespaDiagnostic::error(
                "E_FIELDSET_FIELD_MISSING",
                format!("schema.{}.fieldset.default.{}", spec.name, field_name),
                format!("default fieldset references missing field `{field_name}`"),
                "Add the field to the schema or remove it from the default fieldset.",
                None,
            ));
        }
    }

    for profile in &spec.rank_profiles {
        for field_name in &profile.bm25_fields {
            match spec.field(field_name) {
                Some(field) if field.indexed && field.enable_bm25 => {}
                Some(_) => diagnostics.push(VespaDiagnostic::error(
                    "E_BM25_FIELD_NOT_INDEXED",
                    format!(
                        "schema.{}.rank_profiles.{}.bm25({})",
                        spec.name, profile.name, field_name
                    ),
                    format!("rank profile `{}` uses bm25({field_name}) without an indexed BM25 field", profile.name),
                    "Use BM25 only on string fields with `indexing: index` and `index: enable-bm25`.",
                    None,
                )),
                None => diagnostics.push(VespaDiagnostic::error(
                    "E_RANK_PROFILE_FIELD_MISSING",
                    format!(
                        "schema.{}.rank_profiles.{}.bm25({})",
                        spec.name, profile.name, field_name
                    ),
                    format!("rank profile `{}` references missing field `{field_name}`", profile.name),
                    "Add the field to the schema or remove it from the rank expression.",
                    None,
                )),
            }
        }
    }

    diagnostics
}

pub fn parse_vespa_schema(source: &str) -> std::result::Result<VespaSchemaSpec, String> {
    let lines: Vec<&str> = source.lines().collect();
    let name = lines
        .iter()
        .find_map(|line| parse_name_after(line.trim(), "schema "))
        .ok_or_else(|| "missing `schema NAME {` declaration".to_string())?;
    let mut fields = Vec::new();
    let mut default_fieldset = Vec::new();
    let mut rank_profiles = Vec::new();
    let mut index = 0;

    while index < lines.len() {
        let line = lines[index].trim();
        if line.starts_with("field ") && line.contains(" type ") {
            let (field, next_index) = parse_field_block(&lines, index)?;
            fields.push(field);
            index = next_index;
            continue;
        }
        if line.starts_with("fieldset default") {
            let (fields_in_set, next_index) = parse_fieldset_block(&lines, index);
            default_fieldset = fields_in_set;
            index = next_index;
            continue;
        }
        if line.starts_with("rank-profile ") {
            let (profile, next_index) = parse_rank_profile_block(&lines, index)?;
            rank_profiles.push(profile);
            index = next_index;
            continue;
        }
        index += 1;
    }

    Ok(VespaSchemaSpec {
        name,
        fields,
        default_fieldset,
        rank_profiles,
    })
}

pub fn compare_vespa_schema_spec(
    expected: &VespaSchemaSpec,
    actual: &VespaSchemaSpec,
) -> Vec<VespaDiagnostic> {
    let mut diagnostics = validate_vespa_schema_spec(actual);
    if expected.name != actual.name {
        diagnostics.push(VespaDiagnostic::error(
            "E_SCHEMA_NAME_MISMATCH",
            "schema.name",
            format!(
                "expected schema `{}` but found `{}`",
                expected.name, actual.name
            ),
            format!(
                "Rename the schema to `{}` or select the matching Memtruth preset.",
                expected.name
            ),
            None,
        ));
    }

    for expected_field in &expected.fields {
        let path = format!("schema.{}.fields.{}", expected.name, expected_field.name);
        let Some(actual_field) = actual.field(&expected_field.name) else {
            diagnostics.push(VespaDiagnostic::error(
                "E_SCHEMA_FIELD_MISSING",
                path,
                format!("schema is missing required field `{}`", expected_field.name),
                format!(
                    "Add `{}` to the Vespa schema or update the Memtruth preset.",
                    expected_field.name
                ),
                None,
            ));
            continue;
        };
        if expected_field.vespa_type != actual_field.vespa_type {
            diagnostics.push(VespaDiagnostic::error(
                "E_SCHEMA_FIELD_TYPE_MISMATCH",
                format!("{path}.type"),
                format!(
                    "field `{}` expects type `{}` but schema has `{}`",
                    expected_field.name, expected_field.vespa_type, actual_field.vespa_type
                ),
                format!(
                    "Change `{}` to type `{}`.",
                    expected_field.name, expected_field.vespa_type
                ),
                None,
            ));
        }
        compare_field_role(
            &mut diagnostics,
            &path,
            &expected_field.name,
            "summary",
            expected_field.summary,
            actual_field.summary,
        );
        compare_field_role(
            &mut diagnostics,
            &path,
            &expected_field.name,
            "indexed",
            expected_field.indexed,
            actual_field.indexed,
        );
        compare_field_role(
            &mut diagnostics,
            &path,
            &expected_field.name,
            "attribute",
            expected_field.attribute,
            actual_field.attribute,
        );
        compare_field_role(
            &mut diagnostics,
            &path,
            &expected_field.name,
            "fast_search",
            expected_field.fast_search,
            actual_field.fast_search,
        );
        compare_field_role(
            &mut diagnostics,
            &path,
            &expected_field.name,
            "enable_bm25",
            expected_field.enable_bm25,
            actual_field.enable_bm25,
        );
    }

    let actual_fieldset: HashSet<&str> =
        actual.default_fieldset.iter().map(String::as_str).collect();
    for field in &expected.default_fieldset {
        if !actual_fieldset.contains(field.as_str()) {
            diagnostics.push(VespaDiagnostic::error(
                "E_FIELDSET_FIELD_MISSING",
                format!("schema.{}.fieldset.default.{}", expected.name, field),
                format!("default fieldset is missing `{field}`"),
                "Add the field to `fieldset default` so userQuery() searches the expected legal text fields.",
                None,
            ));
        }
    }

    for expected_profile in &expected.rank_profiles {
        let Some(actual_profile) = actual
            .rank_profiles
            .iter()
            .find(|profile| profile.name == expected_profile.name)
        else {
            diagnostics.push(VespaDiagnostic::error(
                "E_SCHEMA_RANK_PROFILE_MISSING",
                format!(
                    "schema.{}.rank_profiles.{}",
                    expected.name, expected_profile.name
                ),
                format!("rank profile `{}` is missing", expected_profile.name),
                "Add the rank profile or update queries to use an existing profile.",
                None,
            ));
            continue;
        };
        let actual_bm25: HashSet<&str> = actual_profile
            .bm25_fields
            .iter()
            .map(String::as_str)
            .collect();
        for field in &expected_profile.bm25_fields {
            if !actual_bm25.contains(field.as_str()) {
                diagnostics.push(VespaDiagnostic::error(
                    "E_RANK_PROFILE_FIELD_MISSING",
                    format!(
                        "schema.{}.rank_profiles.{}.bm25({})",
                        expected.name, expected_profile.name, field
                    ),
                    format!("rank profile `{}` is missing bm25({field})", expected_profile.name),
                    "Add the BM25 field to the rank expression and summary-features, or update the preset.",
                    None,
                ));
            }
        }
    }

    diagnostics
}

pub fn preflight_vespa_query(
    schema: &VespaSchemaSpec,
    query: &VespaQuerySpec,
) -> Vec<VespaDiagnostic> {
    let mut diagnostics = Vec::new();
    let rank_profile = schema
        .rank_profiles
        .iter()
        .find(|profile| profile.name == query.ranking);
    if rank_profile.is_none() {
        diagnostics.push(VespaDiagnostic::error(
            "E_QUERY_RANK_PROFILE_MISSING",
            "query.ranking",
            format!("query uses missing rank profile `{}`", query.ranking),
            "Use an existing rank profile or add it to the Vespa schema preset.",
            None,
        ));
    }

    for (index, filter) in query.filters.iter().enumerate() {
        let path = format!("query.filters[{index}].field");
        match schema.field(&filter.field) {
            Some(field) if field.attribute => {
                if matches!(filter.operator.as_str(), "prefix" | "fuzzy") && !field.fast_search {
                    diagnostics.push(VespaDiagnostic::error(
                        "E_FILTER_FIELD_NOT_ATTRIBUTE",
                        path,
                        format!(
                            "filter `{}` uses `{}` but field is not fast-search",
                            filter.field, filter.operator
                        ),
                        "Use a field with `indexing: attribute` and `attribute: fast-search`, or change the operator.",
                        None,
                    ));
                }
            }
            Some(_) => diagnostics.push(VespaDiagnostic::error(
                "E_FILTER_FIELD_NOT_ATTRIBUTE",
                path,
                format!(
                    "query filters on `{}` but it is not an attribute",
                    filter.field
                ),
                "Move filters to fields with `indexing: attribute`, or update the schema preset.",
                None,
            )),
            None => diagnostics.push(VespaDiagnostic::error(
                "E_QUERY_FIELD_MISSING",
                path,
                format!("query references missing field `{}`", filter.field),
                "Use an existing schema field or add it to the Vespa schema preset.",
                None,
            )),
        }
    }

    for (index, lane) in query.lanes.iter().enumerate() {
        if !matches!(lane.as_str(), "bm25" | "fuzzy" | "prefix" | "dense") {
            diagnostics.push(VespaDiagnostic::error(
                "E_QUERY_USES_DISABLED_LANE",
                format!("query.lanes[{index}]"),
                format!("retrieval lane `{lane}` is not enabled by the legal section preset"),
                "Enable the lane explicitly in the schema/rank profile before routing queries to it.",
                None,
            ));
        }
    }

    if let Some(rerank_top_k) = query.rerank_top_k {
        let configured = rank_profile.and_then(|profile| profile.global_phase_rerank_count);
        if configured.is_none_or(|count| rerank_top_k > count) {
            diagnostics.push(VespaDiagnostic::error(
                "E_RERANK_TOPK_EXCEEDS_PROFILE",
                "query.rerank_top_k",
                format!(
                    "query requests rerank_top_k={rerank_top_k}, but the selected rank profile does not allow that many global-phase hits"
                ),
                "Add a global-phase rerank-count to the rank profile or lower rerank_top_k.",
                None,
            ));
        }
        if let Some(total_target_hits) = query.total_target_hits
            && total_target_hits < rerank_top_k
        {
            diagnostics.push(VespaDiagnostic::error(
                "E_RERANK_TOPK_EXCEEDS_PROFILE",
                "query.total_target_hits",
                format!(
                    "total_target_hits={total_target_hits} is lower than rerank_top_k={rerank_top_k}"
                ),
                "Make total_target_hits at least rerank_top_k so Vespa has enough candidates to rerank.",
                None,
            ));
        }
    }

    diagnostics
}

pub fn preflight_legal_section_documents(sections: &[LegalSectionDoc]) -> PreflightReport {
    let schema = legal_section_schema_spec();
    let mut diagnostics = validate_vespa_schema_spec(&schema);
    let mut feed_preview = Vec::new();
    let mut seen_ids: HashMap<&str, usize> = HashMap::new();

    for (index, section) in sections.iter().enumerate() {
        let source_id = Some(section.doc_id.clone());
        push_required_text_diagnostics(&mut diagnostics, index, section);
        push_dense_embedding_diagnostics(&mut diagnostics, index, section);
        if section.section_ordinal <= 0 {
            diagnostics.push(VespaDiagnostic::error(
                "E_FEED_TYPE_MISMATCH",
                format!("sections[{index}].section_ordinal"),
                "section_ordinal must be a positive Vespa int",
                "Set section_ordinal to a stable positive integer preserving source order.",
                source_id.clone(),
            ));
        }

        if let Some(first_index) = seen_ids.insert(section.doc_id.as_str(), index) {
            diagnostics.push(VespaDiagnostic::error(
                "E_DUPLICATE_DOCUMENT_ID",
                format!("sections[{index}].doc_id"),
                format!(
                    "doc_id `{}` duplicates sections[{first_index}] and would overwrite the same Vespa document",
                    section.doc_id
                ),
                "Use a stable section-level id including source/version/section identity.",
                source_id.clone(),
            ));
        }

        if section.validate().is_ok() {
            match legal_section_doc_put(section) {
                Ok(feed) => feed_preview.push(feed),
                Err(error) => diagnostics.push(VespaDiagnostic::error(
                    "E_FEED_PROJECTION_FAILED",
                    format!("sections[{index}]"),
                    error.to_string(),
                    "Fix the section record before generating Vespa feed JSON.",
                    source_id,
                )),
            }
        }
    }

    PreflightReport {
        schema,
        feed_preview,
        diagnostics,
    }
}

pub fn preflight_legal_section_feed_values(feed: &[Value]) -> PreflightReport {
    let schema = legal_section_schema_spec();
    let mut diagnostics = validate_vespa_schema_spec(&schema);
    let expected_fields: HashMap<&str, &VespaFieldSpec> = schema
        .fields
        .iter()
        .map(|field| (field.name.as_str(), field))
        .collect();

    for (index, value) in feed.iter().enumerate() {
        let source_id = value
            .get("put")
            .and_then(Value::as_str)
            .map(strip_vespa_document_id);
        let Some(fields) = value.get("fields").and_then(Value::as_object) else {
            diagnostics.push(VespaDiagnostic::error(
                "E_FEED_TYPE_MISMATCH",
                format!("feed[{index}].fields"),
                "Vespa feed operation must contain an object at `fields`",
                "Emit feed JSON as `{ \"put\": \"id:...\", \"fields\": { ... } }`.",
                source_id,
            ));
            continue;
        };

        for field in &schema.fields {
            let path = format!("feed[{index}].fields.{}", field.name);
            match fields.get(&field.name) {
                Some(field_value) => validate_feed_value_type(
                    &mut diagnostics,
                    &path,
                    field,
                    field_value,
                    source_id.clone(),
                ),
                None if field.required => diagnostics.push(VespaDiagnostic::error(
                    "E_REQUIRED_FIELD_EMPTY",
                    path,
                    format!("required field `{}` is missing from Vespa feed", field.name),
                    format!("Populate `{}` before feeding legal_doc.", field.name),
                    source_id.clone(),
                )),
                None => {}
            }
        }

        for field_name in fields.keys() {
            if !expected_fields.contains_key(field_name.as_str()) {
                diagnostics.push(VespaDiagnostic::warning(
                    "W_UNKNOWN_FEED_FIELD",
                    format!("feed[{index}].fields.{field_name}"),
                    format!("field `{field_name}` is not in the legal_doc schema preset"),
                    "Remove the field or add it to the Memtruth Vespa schema preset before deploy/feed.",
                    source_id.clone(),
                ));
            }
        }
    }

    PreflightReport {
        schema,
        feed_preview: feed.to_vec(),
        diagnostics,
    }
}

pub fn legal_section_doc_from_value(value: &Value) -> Result<LegalSectionDoc> {
    let has_page_locator = optional_value(value, "source_locator").is_some()
        || optional_value(value, "locator").is_some()
        || optional_value(value, "page_locator").is_some()
        || optional_integer_value(value, "page_start").is_some()
        || optional_integer_value(value, "page_end").is_some()
        || !optional_integer_array(value, "page_numbers").is_empty();
    let doc_type = first_value(value, &["doc_type", "type", "document_type"])
        .ok_or(ProjectionError::MissingSectionField("doc_type"))?;
    let source = required_value(value, "source")?;
    let instrument_status = first_value(value, &["instrument_status", "status"])
        .unwrap_or_else(|| "unknown".to_string());
    Ok(LegalSectionDoc {
        doc_id: required_value(value, "doc_id")?,
        parent_doc_id: required_value(value, "parent_doc_id")?,
        version_id: required_value(value, "version_id")?,
        section_ordinal: integer_value(value, "section_ordinal")?,
        section_number: required_value(value, "section_number")?,
        section_heading: required_value(value, "section_heading")?,
        part_label: optional_value(value, "part_label").unwrap_or_default(),
        division_label: optional_value(value, "division_label").unwrap_or_default(),
        subdivision_label: optional_value(value, "subdivision_label"),
        citation: required_value(value, "citation")?,
        title: required_value(value, "title")?,
        jurisdiction: required_value(value, "jurisdiction")?,
        doc_type: doc_type.clone(),
        source: source.clone(),
        authority_type: first_value(value, &["authority_type", "legal_authority_type"])
            .unwrap_or_else(|| default_authority_type(&doc_type, &source).to_string()),
        authority_rank: optional_integer_value(value, "authority_rank")
            .unwrap_or_else(|| default_authority_rank(&doc_type, &source)),
        court_name: optional_value(value, "court_name"),
        court_rank: optional_integer_value(value, "court_rank"),
        instrument_status: instrument_status.clone(),
        is_current: optional_bool_value(value, "is_current")
            .unwrap_or_else(|| instrument_status.eq_ignore_ascii_case("current")),
        version_date_days: optional_integer_value(value, "version_date_days").or_else(|| {
            date_to_yyyymmdd(
                first_value(
                    value,
                    &["version_date", "decision_date", "date", "published_date"],
                )
                .as_deref(),
            )
        }),
        effective_from_days: optional_integer_value(value, "effective_from_days")
            .or_else(|| date_to_yyyymmdd(optional_value(value, "effective_from").as_deref())),
        effective_to_days: optional_integer_value(value, "effective_to_days")
            .or_else(|| date_to_yyyymmdd(optional_value(value, "effective_to").as_deref())),
        decision_date: first_value(value, &["decision_date", "date", "published_date"]),
        url: optional_value(value, "url"),
        source_locator: first_value(value, &["source_locator", "locator", "page_locator"]),
        page_start: optional_integer_value(value, "page_start"),
        page_end: optional_integer_value(value, "page_end"),
        page_numbers: optional_integer_array(value, "page_numbers"),
        char_start: optional_integer_value(value, "char_start"),
        char_end: optional_integer_value(value, "char_end"),
        source_format: optional_value(value, "source_format"),
        locator_confidence: optional_value(value, "locator_confidence")
            .or_else(|| (!has_page_locator).then(|| "unavailable".to_string())),
        body_text: clean_legal_text(
            &first_value(value, &["body_text", "text", "body", "content"])
                .ok_or(ProjectionError::MissingSectionField("body_text"))?,
        )
        .text,
        dense_embedding: optional_float_array(value, "dense_embedding_v1_256_native")
            .or_else(|| optional_float_array(value, "dense_embedding")),
    })
}

pub fn legal_section_doc_put(section: &LegalSectionDoc) -> Result<Value> {
    section.validate()?;
    let mut fields = json!({
        "doc_id": section.doc_id,
        "parent_doc_id": section.parent_doc_id,
        "version_id": section.version_id,
        "chunk_type": "section",
        "section_ordinal": section.section_ordinal,
        "section_number": section.section_number,
        "section_number_attr": section.section_number.to_lowercase(),
        "section_tokens": search_tokens(&section.section_number),
        "section_heading": section.section_heading,
        "heading_tokens": search_tokens(&section.section_heading),
        "part_label": section.part_label,
        "division_label": section.division_label,
        "subdivision_label": section.subdivision_label.clone().unwrap_or_default(),
        "citation": section.citation,
        "citation_attr": section.citation.to_lowercase(),
        "citation_tokens": search_tokens(&section.citation),
        "title": section.title,
        "title_tokens": search_tokens(&section.title),
        "jurisdiction": section.jurisdiction,
        "doc_type": section.doc_type,
        "source": section.source,
        "authority_type": section.authority_type,
        "authority_rank": section.authority_rank,
        "instrument_status": section.instrument_status,
        "is_current": if section.is_current { 1 } else { 0 },
        "decision_date": section.decision_date.clone().unwrap_or_default(),
        "url": section.url.clone().unwrap_or_default(),
        "body_text": section.body_text
    });
    let fields_object = fields
        .as_object_mut()
        .expect("section feed fields must be a JSON object");
    insert_optional_string(fields_object, "source_locator", &section.source_locator);
    insert_optional_i64(fields_object, "page_start", section.page_start);
    insert_optional_i64(fields_object, "page_end", section.page_end);
    if !section.page_numbers.is_empty() {
        fields_object.insert("page_numbers".to_string(), json!(section.page_numbers));
    }
    insert_optional_i64(fields_object, "char_start", section.char_start);
    insert_optional_i64(fields_object, "char_end", section.char_end);
    insert_optional_string(fields_object, "source_format", &section.source_format);
    insert_optional_string(fields_object, "court_name", &section.court_name);
    insert_optional_i64(fields_object, "court_rank", section.court_rank);
    insert_optional_i64(
        fields_object,
        "version_date_days",
        section.version_date_days,
    );
    insert_optional_i64(
        fields_object,
        "effective_from_days",
        section.effective_from_days,
    );
    insert_optional_i64(
        fields_object,
        "effective_to_days",
        section.effective_to_days,
    );
    insert_optional_string(
        fields_object,
        "locator_confidence",
        &section.locator_confidence,
    );
    if let Some(embedding) = &section.dense_embedding {
        fields_object.insert(
            "dense_embedding_v1_256_native".to_string(),
            json!(embedding),
        );
    }

    Ok(json!({
        "put": format!("id:legal:legal_doc::{}", section.doc_id),
        "fields": fields
    }))
}

fn required_value(value: &Value, field: &'static str) -> Result<String> {
    optional_value(value, field).ok_or(ProjectionError::MissingSectionField(field))
}

fn string_field(
    name: &str,
    summary: bool,
    indexed: bool,
    attribute: bool,
    fast_search: bool,
    enable_bm25: bool,
    required: bool,
) -> VespaFieldSpec {
    VespaFieldSpec {
        name: name.to_string(),
        vespa_type: "string".to_string(),
        summary,
        indexed,
        attribute,
        fast_search,
        enable_bm25,
        required,
    }
}

fn array_string_field(
    name: &str,
    summary: bool,
    indexed: bool,
    attribute: bool,
    fast_search: bool,
    enable_bm25: bool,
    required: bool,
) -> VespaFieldSpec {
    VespaFieldSpec {
        name: name.to_string(),
        vespa_type: "array<string>".to_string(),
        summary,
        indexed,
        attribute,
        fast_search,
        enable_bm25,
        required,
    }
}

fn array_int_field(
    name: &str,
    summary: bool,
    indexed: bool,
    attribute: bool,
    fast_search: bool,
    enable_bm25: bool,
    required: bool,
) -> VespaFieldSpec {
    VespaFieldSpec {
        name: name.to_string(),
        vespa_type: "array<int>".to_string(),
        summary,
        indexed,
        attribute,
        fast_search,
        enable_bm25,
        required,
    }
}

fn int_field(name: &str, summary: bool, required: bool) -> VespaFieldSpec {
    VespaFieldSpec {
        name: name.to_string(),
        vespa_type: "int".to_string(),
        summary,
        indexed: false,
        attribute: true,
        fast_search: false,
        enable_bm25: false,
        required,
    }
}

fn tensor_field(
    name: &str,
    summary: bool,
    indexed: bool,
    attribute: bool,
    required: bool,
) -> VespaFieldSpec {
    VespaFieldSpec {
        name: name.to_string(),
        vespa_type: "tensor<bfloat16>(x[256])".to_string(),
        summary,
        indexed,
        attribute,
        fast_search: false,
        enable_bm25: false,
        required,
    }
}

fn bm25_fields() -> Vec<String> {
    vec![
        "body_text".to_string(),
        "section_heading".to_string(),
        "citation".to_string(),
        "title".to_string(),
    ]
}

fn parse_name_after(line: &str, prefix: &str) -> Option<String> {
    let rest = line.strip_prefix(prefix)?;
    let name = rest
        .split_whitespace()
        .next()
        .unwrap_or_default()
        .trim_end_matches('{');
    if name.is_empty() {
        None
    } else {
        Some(name.to_string())
    }
}

fn parse_field_block(
    lines: &[&str],
    start: usize,
) -> std::result::Result<(VespaFieldSpec, usize), String> {
    let header = lines[start].trim();
    let tokens: Vec<&str> = header.split_whitespace().collect();
    if tokens.len() < 5 || tokens[0] != "field" || tokens[2] != "type" {
        return Err(format!("invalid field declaration: {header}"));
    }
    let name = tokens[1].to_string();
    let vespa_type = tokens[3].trim_end_matches('{').to_string();
    let mut block = Vec::new();
    let mut depth = brace_delta(header);
    let mut index = start + 1;
    while index < lines.len() {
        let line = lines[index].trim();
        depth += brace_delta(line);
        if depth <= 0 {
            break;
        }
        block.push(line.to_string());
        index += 1;
    }
    if index >= lines.len() {
        return Err(format!("unterminated field block `{name}`"));
    }
    let block_text = block.join("\n");
    Ok((
        VespaFieldSpec {
            name,
            vespa_type,
            summary: indexing_contains(&block_text, "summary"),
            indexed: indexing_contains(&block_text, "index"),
            attribute: indexing_contains(&block_text, "attribute"),
            fast_search: block_text.contains("attribute: fast-search"),
            enable_bm25: block_text.contains("index: enable-bm25"),
            required: false,
        },
        index + 1,
    ))
}

fn parse_fieldset_block(lines: &[&str], start: usize) -> (Vec<String>, usize) {
    let mut fields = Vec::new();
    let mut index = start + 1;
    while index < lines.len() {
        let line = lines[index].trim();
        if line == "}" {
            break;
        }
        if let Some(rest) = line.strip_prefix("fields:") {
            fields.extend(
                rest.split(',')
                    .map(|field| field.trim())
                    .filter(|field| !field.is_empty())
                    .map(str::to_string),
            );
        }
        index += 1;
    }
    (fields, index + 1)
}

fn parse_rank_profile_block(
    lines: &[&str],
    start: usize,
) -> std::result::Result<(VespaRankProfileSpec, usize), String> {
    let header = lines[start].trim();
    let name = parse_name_after(header, "rank-profile ")
        .ok_or_else(|| format!("invalid rank-profile declaration: {header}"))?;
    let mut block = Vec::new();
    let mut depth = brace_delta(header);
    let mut index = start + 1;
    while index < lines.len() {
        let line = lines[index].trim();
        depth += brace_delta(line);
        block.push(line.to_string());
        index += 1;
        if depth <= 0 {
            break;
        }
    }
    let block_text = block.join("\n");
    let bm25_fields = extract_bm25_fields(&block_text);
    let global_phase_rerank_count = block_text
        .lines()
        .find_map(|line| line.trim().strip_prefix("rerank-count:"))
        .and_then(|value| value.trim().parse::<usize>().ok());
    Ok((
        VespaRankProfileSpec {
            name,
            bm25_fields,
            global_phase_rerank_count,
        },
        index,
    ))
}

fn indexing_contains(block_text: &str, role: &str) -> bool {
    block_text
        .lines()
        .filter_map(|line| line.trim().strip_prefix("indexing:"))
        .any(|rest| rest.split('|').any(|part| part.trim() == role))
}

fn brace_delta(line: &str) -> i32 {
    line.chars().fold(0, |delta, ch| match ch {
        '{' => delta + 1,
        '}' => delta - 1,
        _ => delta,
    })
}

fn extract_bm25_fields(text: &str) -> Vec<String> {
    let mut fields = Vec::new();
    let mut rest = text;
    while let Some(start) = rest.find("bm25(") {
        let after = &rest[start + 5..];
        let Some(end) = after.find(')') else {
            break;
        };
        let field = after[..end].trim();
        if !field.is_empty() && !fields.iter().any(|existing| existing == field) {
            fields.push(field.to_string());
        }
        rest = &after[end + 1..];
    }
    fields
}

fn compare_field_role(
    diagnostics: &mut Vec<VespaDiagnostic>,
    path: &str,
    field_name: &str,
    role: &str,
    expected: bool,
    actual: bool,
) {
    if expected == actual {
        return;
    }
    let fix = match role {
        "indexed" => "Set `indexing:` to include `index`, or update the Memtruth preset.",
        "attribute" => "Set `indexing:` to include `attribute`, or update the Memtruth preset.",
        "summary" => "Set `indexing:` to include `summary`, or update the Memtruth preset.",
        "fast_search" => "Add or remove `attribute: fast-search` to match the preset.",
        "enable_bm25" => "Add or remove `index: enable-bm25` to match the preset.",
        _ => "Update the schema role or the Memtruth preset.",
    };
    diagnostics.push(VespaDiagnostic::error(
        "E_SCHEMA_FIELD_ROLE_MISMATCH",
        format!("{path}.{role}"),
        format!("field `{field_name}` role `{role}` expected {expected} but found {actual}"),
        fix,
        None,
    ));
}

fn push_required_text_diagnostics(
    diagnostics: &mut Vec<VespaDiagnostic>,
    index: usize,
    section: &LegalSectionDoc,
) {
    let required_text_fields = [
        ("doc_id", section.doc_id.as_str()),
        ("parent_doc_id", section.parent_doc_id.as_str()),
        ("version_id", section.version_id.as_str()),
        ("section_number", section.section_number.as_str()),
        ("section_heading", section.section_heading.as_str()),
        ("citation", section.citation.as_str()),
        ("title", section.title.as_str()),
        ("jurisdiction", section.jurisdiction.as_str()),
        ("doc_type", section.doc_type.as_str()),
        ("source", section.source.as_str()),
        ("authority_type", section.authority_type.as_str()),
        ("instrument_status", section.instrument_status.as_str()),
        ("body_text", section.body_text.as_str()),
    ];
    for (field, value) in required_text_fields {
        if value.trim().is_empty() {
            diagnostics.push(VespaDiagnostic::error(
                "E_REQUIRED_FIELD_EMPTY",
                format!("sections[{index}].{field}"),
                format!("required field `{field}` is empty"),
                format!("Populate `{field}` before generating Vespa feed."),
                if section.doc_id.trim().is_empty() {
                    None
                } else {
                    Some(section.doc_id.clone())
                },
            ));
        }
    }
}

fn push_dense_embedding_diagnostics(
    diagnostics: &mut Vec<VespaDiagnostic>,
    index: usize,
    section: &LegalSectionDoc,
) {
    let Some(embedding) = &section.dense_embedding else {
        return;
    };
    let source_id = Some(section.doc_id.clone());
    if embedding.len() != 256 {
        diagnostics.push(VespaDiagnostic::error(
            "E_DENSE_VECTOR_DIMENSION_MISMATCH",
            format!("sections[{index}].dense_embedding"),
            format!(
                "dense_embedding must contain exactly 256 values, found {}",
                embedding.len()
            ),
            "Generate Nomic v1.5 Matryoshka embeddings with dimension=256 before feeding Vespa.",
            source_id.clone(),
        ));
        return;
    }
    if embedding.iter().any(|value| !value.is_finite()) {
        diagnostics.push(VespaDiagnostic::error(
            "E_DENSE_VECTOR_NON_FINITE",
            format!("sections[{index}].dense_embedding"),
            "dense_embedding contains NaN or infinite values",
            "Regenerate the embedding and reject non-finite vector values before feed.",
            source_id.clone(),
        ));
        return;
    }
    let norm = embedding
        .iter()
        .map(|value| f64::from(*value) * f64::from(*value))
        .sum::<f64>()
        .sqrt();
    if !(0.99..=1.01).contains(&norm) {
        diagnostics.push(VespaDiagnostic::error(
            "E_DENSE_VECTOR_NORM_OUT_OF_RANGE",
            format!("sections[{index}].dense_embedding"),
            format!(
                "dense_embedding must be unit-normalized for prenormalized-angular, norm={norm:.4}"
            ),
            "L2-normalize the 256D embedding before projection into Vespa.",
            source_id,
        ));
    }
}

fn validate_feed_value_type(
    diagnostics: &mut Vec<VespaDiagnostic>,
    path: &str,
    field: &VespaFieldSpec,
    value: &Value,
    source_id: Option<String>,
) {
    let valid = match field.vespa_type.as_str() {
        "string" => value.is_string(),
        "int" => value.as_i64().is_some(),
        "bool" => value.is_boolean(),
        "array<string>" => value
            .as_array()
            .is_some_and(|items| items.iter().all(Value::is_string)),
        "array<int>" => value
            .as_array()
            .is_some_and(|items| items.iter().all(|item| item.as_i64().is_some())),
        "tensor<bfloat16>(x[256])" => value.as_array().is_some_and(|items| {
            items.len() == 256
                && items
                    .iter()
                    .all(|item| item.as_f64().is_some_and(|value| value.is_finite()))
        }),
        _ => true,
    };

    if !valid {
        diagnostics.push(VespaDiagnostic::error(
            "E_FEED_TYPE_MISMATCH",
            path,
            format!(
                "field `{}` expects Vespa type `{}` but received {}",
                field.name,
                field.vespa_type,
                json_type_name(value)
            ),
            format!(
                "Emit `{}` as {} in the Vespa feed JSON.",
                field.name, field.vespa_type
            ),
            source_id,
        ));
    }
}

fn json_type_name(value: &Value) -> &'static str {
    match value {
        Value::Null => "null",
        Value::Bool(_) => "bool",
        Value::Number(_) => "number",
        Value::String(_) => "string",
        Value::Array(_) => "array",
        Value::Object(_) => "object",
    }
}

fn strip_vespa_document_id(value: &str) -> String {
    value
        .rsplit_once("::")
        .map(|(_, id)| id.to_string())
        .unwrap_or_else(|| value.to_string())
}

fn first_value(value: &Value, fields: &[&'static str]) -> Option<String> {
    fields.iter().find_map(|field| optional_value(value, field))
}

fn optional_value(value: &Value, field: &str) -> Option<String> {
    match value.get(field) {
        Some(Value::String(text)) if !text.trim().is_empty() => Some(clean_string(text)),
        Some(Value::Number(number)) => Some(number.to_string()),
        Some(Value::Bool(value)) => Some(value.to_string()),
        _ => None,
    }
}

fn integer_value(value: &Value, field: &'static str) -> Result<i64> {
    match value.get(field) {
        Some(Value::Number(number)) => number
            .as_i64()
            .ok_or(ProjectionError::MissingSectionField(field)),
        Some(Value::String(text)) => text
            .trim()
            .parse::<i64>()
            .map_err(|_| ProjectionError::MissingSectionField(field)),
        _ => Err(ProjectionError::MissingSectionField(field)),
    }
}

fn optional_integer_value(value: &Value, field: &str) -> Option<i64> {
    match value.get(field) {
        Some(Value::Number(number)) => number.as_i64(),
        Some(Value::String(text)) => text.trim().parse::<i64>().ok(),
        _ => None,
    }
}

fn optional_bool_value(value: &Value, field: &str) -> Option<bool> {
    match value.get(field) {
        Some(Value::Bool(value)) => Some(*value),
        Some(Value::String(text)) => match text.trim().to_ascii_lowercase().as_str() {
            "true" | "1" | "yes" => Some(true),
            "false" | "0" | "no" => Some(false),
            _ => None,
        },
        _ => None,
    }
}

fn optional_integer_array(value: &Value, field: &str) -> Vec<i64> {
    let Some(items) = value.get(field).and_then(Value::as_array) else {
        return Vec::new();
    };
    items
        .iter()
        .filter_map(|item| match item {
            Value::Number(number) => number.as_i64(),
            Value::String(text) => text.trim().parse::<i64>().ok(),
            _ => None,
        })
        .collect()
}

fn optional_float_array(value: &Value, field: &str) -> Option<Vec<f32>> {
    let items = value.get(field)?.as_array()?;
    Some(
        items
            .iter()
            .filter_map(|item| match item {
                Value::Number(number) => number.as_f64().map(|value| value as f32),
                Value::String(text) => text.trim().parse::<f32>().ok(),
                _ => None,
            })
            .collect(),
    )
}

fn insert_optional_string(
    fields: &mut serde_json::Map<String, Value>,
    name: &str,
    value: &Option<String>,
) {
    if let Some(value) = value.as_ref().filter(|value| !value.trim().is_empty()) {
        fields.insert(name.to_string(), json!(value));
    }
}

fn insert_optional_i64(
    fields: &mut serde_json::Map<String, Value>,
    name: &str,
    value: Option<i64>,
) {
    if let Some(value) = value {
        fields.insert(name.to_string(), json!(value));
    }
}

fn require_non_empty(field: &'static str, value: &str) -> Result<()> {
    if value.trim().is_empty() {
        Err(ProjectionError::InvalidSection(format!(
            "{field} is required"
        )))
    } else {
        Ok(())
    }
}

fn require_positive(field: &'static str, value: i64) -> Result<()> {
    if value <= 0 {
        Err(ProjectionError::InvalidSection(format!(
            "{field} must be greater than zero"
        )))
    } else {
        Ok(())
    }
}

fn require_non_negative(field: &'static str, value: i64) -> Result<()> {
    if value < 0 {
        Err(ProjectionError::InvalidSection(format!(
            "{field} must be zero or greater"
        )))
    } else {
        Ok(())
    }
}

fn clean_string(value: &str) -> String {
    value.trim().to_string()
}

fn default_authority_type(doc_type: &str, source: &str) -> &'static str {
    let doc_type = doc_type.to_ascii_lowercase();
    let source = source.to_ascii_lowercase();
    if doc_type.contains("act") || doc_type.contains("legislation") {
        "legislation"
    } else if source.contains("oaic")
        || source.contains("regulator")
        || doc_type.contains("guideline")
    {
        "regulator_guidance"
    } else if doc_type.contains("case") || doc_type.contains("judgment") {
        "case_law"
    } else {
        "other"
    }
}

fn default_authority_rank(doc_type: &str, source: &str) -> i64 {
    match default_authority_type(doc_type, source) {
        "legislation" => 90,
        "case_law" => 80,
        "regulator_guidance" => 70,
        _ => 10,
    }
}

fn date_to_yyyymmdd(value: Option<&str>) -> Option<i64> {
    let text = value?.trim();
    let digits: String = text.chars().filter(char::is_ascii_digit).collect();
    if digits.len() >= 8 {
        digits[..8].parse::<i64>().ok()
    } else {
        None
    }
}

fn search_tokens(value: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    for token in value
        .to_lowercase()
        .split(|ch: char| !ch.is_ascii_alphanumeric())
        .filter(|token| !token.is_empty())
    {
        if !tokens.iter().any(|existing| existing == token) {
            tokens.push(token.to_string());
        }
    }
    tokens
}
