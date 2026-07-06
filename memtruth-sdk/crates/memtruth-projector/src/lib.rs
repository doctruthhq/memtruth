use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ProjectorError {
    #[error("required mapping path `{path}` did not resolve")]
    RequiredPathMissing { path: String },
    #[error("target field is required")]
    MissingTargetField,
    #[error("source path is required")]
    MissingSourcePath,
    #[error("source locator uri is required")]
    MissingSourceUri,
    #[error("source locator object_id is required")]
    MissingObjectId,
    #[error("item path is required")]
    MissingItemPath,
}

pub type Result<T> = std::result::Result<T, ProjectorError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceSystem {
    Database,
    FileSystem,
    R2,
    S3,
    Http,
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SourceLocator {
    pub system: SourceSystem,
    pub uri: String,
    pub object_id: String,
}

impl SourceLocator {
    fn validate(&self) -> Result<()> {
        if self.uri.trim().is_empty() {
            return Err(ProjectorError::MissingSourceUri);
        }
        if self.object_id.trim().is_empty() {
            return Err(ProjectorError::MissingObjectId);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FtsFieldRole {
    FullText,
    Filter,
    Sort,
    Metadata,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FtsFieldMapping {
    pub target_field: String,
    pub source_path: String,
    pub role: FtsFieldRole,
    pub required: bool,
}

impl FtsFieldMapping {
    pub fn full_text(target_field: &str, source_path: &str) -> Self {
        Self::new(target_field, source_path, FtsFieldRole::FullText, false)
    }

    pub fn filter(target_field: &str, source_path: &str) -> Self {
        Self::new(target_field, source_path, FtsFieldRole::Filter, false)
    }

    pub fn metadata(target_field: &str, source_path: &str) -> Self {
        Self::new(target_field, source_path, FtsFieldRole::Metadata, false)
    }

    fn new(target_field: &str, source_path: &str, role: FtsFieldRole, required: bool) -> Self {
        Self {
            target_field: target_field.to_string(),
            source_path: source_path.to_string(),
            role,
            required,
        }
    }

    fn validate(&self) -> Result<()> {
        if self.target_field.trim().is_empty() {
            return Err(ProjectorError::MissingTargetField);
        }
        if self.source_path.trim().is_empty() {
            return Err(ProjectorError::MissingSourcePath);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FtsMappingSpec {
    pub mappings: Vec<FtsFieldMapping>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FtsCollectionMappingSpec {
    pub item_path: String,
    pub mappings: Vec<FtsFieldMapping>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FtsProjection {
    pub source: SourceLocator,
    pub full_text_fields: BTreeMap<String, String>,
    pub filter_fields: BTreeMap<String, String>,
    pub sort_fields: BTreeMap<String, String>,
    pub metadata: Value,
}

pub fn project_json_collection(
    source: SourceLocator,
    record: &Value,
    spec: &FtsCollectionMappingSpec,
) -> Result<Vec<FtsProjection>> {
    source.validate()?;
    if spec.item_path.trim().is_empty() {
        return Err(ProjectorError::MissingItemPath);
    }

    let items = extract_path_items(record, &spec.item_path);
    let mut projections = Vec::with_capacity(items.len());
    for (index, item) in items.iter().enumerate() {
        let item_source = SourceLocator {
            system: source.system,
            uri: source.uri.clone(),
            object_id: format!("{}::{index:04}", source.object_id),
        };
        projections.push(project_json_record(
            item_source,
            item,
            &FtsMappingSpec {
                mappings: spec.mappings.clone(),
            },
        )?);
    }
    Ok(projections)
}

pub fn project_json_record(
    source: SourceLocator,
    record: &Value,
    spec: &FtsMappingSpec,
) -> Result<FtsProjection> {
    source.validate()?;
    let mut projection = FtsProjection {
        source,
        full_text_fields: BTreeMap::new(),
        filter_fields: BTreeMap::new(),
        sort_fields: BTreeMap::new(),
        metadata: Value::Object(Default::default()),
    };

    for mapping in &spec.mappings {
        mapping.validate()?;
        let values = extract_path_values(record, &mapping.source_path);
        if values.is_empty() && mapping.required {
            return Err(ProjectorError::RequiredPathMissing {
                path: mapping.source_path.clone(),
            });
        }
        let joined = join_scalar_values(&values);
        match mapping.role {
            FtsFieldRole::FullText => {
                projection
                    .full_text_fields
                    .insert(mapping.target_field.clone(), joined);
            }
            FtsFieldRole::Filter => {
                projection
                    .filter_fields
                    .insert(mapping.target_field.clone(), joined);
            }
            FtsFieldRole::Sort => {
                projection
                    .sort_fields
                    .insert(mapping.target_field.clone(), joined);
            }
            FtsFieldRole::Metadata => {
                projection.metadata[&mapping.target_field] = if values.len() == 1 {
                    values[0].clone()
                } else {
                    Value::Array(values)
                };
            }
        }
    }

    Ok(projection)
}

fn extract_path_items(record: &Value, path: &str) -> Vec<Value> {
    let segments = path
        .split('.')
        .filter(|segment| !segment.is_empty())
        .collect::<Vec<_>>();
    let mut output = Vec::new();
    collect_path_items(record, &segments, &mut output);
    output
}

fn collect_path_items(value: &Value, segments: &[&str], output: &mut Vec<Value>) {
    if segments.is_empty() {
        match value {
            Value::Array(items) => output.extend(items.iter().cloned()),
            Value::Object(_) => output.push(value.clone()),
            _ => {}
        }
        return;
    }

    match value {
        Value::Object(map) => {
            if let Some(next) = map.get(segments[0]) {
                collect_path_items(next, &segments[1..], output);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_path_items(item, segments, output);
            }
        }
        _ => {}
    }
}

fn extract_path_values(record: &Value, path: &str) -> Vec<Value> {
    let segments = path
        .split('.')
        .filter(|segment| !segment.is_empty())
        .collect::<Vec<_>>();
    let mut output = Vec::new();
    collect_path_values(record, &segments, &mut output);
    output
}

fn collect_path_values(value: &Value, segments: &[&str], output: &mut Vec<Value>) {
    if segments.is_empty() {
        collect_leaf_values(value, output);
        return;
    }

    match value {
        Value::Object(map) => {
            if let Some(next) = map.get(segments[0]) {
                collect_path_values(next, &segments[1..], output);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_path_values(item, segments, output);
            }
        }
        _ => {}
    }
}

fn collect_leaf_values(value: &Value, output: &mut Vec<Value>) {
    match value {
        Value::Array(items) => {
            for item in items {
                collect_leaf_values(item, output);
            }
        }
        Value::Object(_) | Value::Null => {}
        _ => output.push(value.clone()),
    }
}

fn join_scalar_values(values: &[Value]) -> String {
    values
        .iter()
        .filter_map(scalar_to_string)
        .filter(|value| !value.trim().is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

fn scalar_to_string(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        Value::Bool(value) => Some(value.to_string()),
        _ => None,
    }
}
