use std::env;
use std::fs;
use std::path::PathBuf;

use memtruth_vespa::{
    DiagnosticSeverity, LegalSectionDoc, ProjectionError, VespaDiagnostic, VespaQuerySpec,
    compare_vespa_schema_spec, legal_section_doc_from_value, legal_section_schema_spec,
    parse_vespa_schema, preflight_legal_section_documents, preflight_legal_section_feed_values,
    preflight_vespa_query,
};
use serde::Serialize;
use serde_json::Value;

#[derive(Debug, Default)]
struct Args {
    sections: Option<PathBuf>,
    feed: Option<PathBuf>,
    sd: Option<PathBuf>,
    query: Option<PathBuf>,
    report: Option<PathBuf>,
}

#[derive(Debug, Serialize)]
struct CliReport {
    schema: String,
    parser_scope: String,
    has_errors: bool,
    diagnostics: Vec<VespaDiagnostic>,
    feed_preview_count: usize,
}

fn main() {
    match run() {
        Ok(report) if report.has_errors => {
            print_report_error_summary(&report);
            std::process::exit(1);
        }
        Ok(_) => {}
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(2);
        }
    }
}

fn run() -> Result<CliReport, String> {
    let args = parse_args(env::args().skip(1).collect())?;
    let expected_schema = legal_section_schema_spec();
    let mut diagnostics = Vec::new();
    let mut feed_preview_count = 0;

    if let Some(path) = &args.sd {
        let source = fs::read_to_string(path)
            .map_err(|error| format!("failed to read {}: {error}", path.display()))?;
        match parse_vespa_schema(&source) {
            Ok(actual) => diagnostics.extend(compare_vespa_schema_spec(&expected_schema, &actual)),
            Err(error) => diagnostics.push(VespaDiagnostic::error(
                "E_SCHEMA_PARSE_FAILED",
                "schema",
                error,
                "Pass a Vespa .sd schema file compatible with the legal section preset.",
                None,
            )),
        }
    }

    if let Some(path) = &args.sections {
        let (sections, section_diagnostics) = read_sections(path)?;
        diagnostics.extend(section_diagnostics);
        let report = preflight_legal_section_documents(&sections);
        feed_preview_count += report.feed_preview.len();
        diagnostics.extend(report.diagnostics);
    }

    if let Some(path) = &args.feed {
        let (feed, feed_diagnostics) = read_jsonl_values(path, "feed")?;
        diagnostics.extend(feed_diagnostics);
        let report = preflight_legal_section_feed_values(&feed);
        feed_preview_count += report.feed_preview.len();
        diagnostics.extend(report.diagnostics);
    }

    if let Some(path) = &args.query {
        let source = fs::read_to_string(path)
            .map_err(|error| format!("failed to read {}: {error}", path.display()))?;
        match serde_json::from_str::<VespaQuerySpec>(&source) {
            Ok(query) => diagnostics.extend(preflight_vespa_query(&expected_schema, &query)),
            Err(error) => diagnostics.push(VespaDiagnostic::error(
                "E_QUERY_PARSE_FAILED",
                "query",
                format!("failed to parse query JSON: {error}"),
                "Pass query JSON with fields such as ranking, filters, lanes, and rerank_top_k.",
                None,
            )),
        }
    }

    let has_errors = diagnostics
        .iter()
        .any(|diagnostic| diagnostic.severity == DiagnosticSeverity::Error);
    let report = CliReport {
        schema: expected_schema.name,
        parser_scope: "memtruth-supported Vespa contract surface".to_string(),
        has_errors,
        diagnostics,
        feed_preview_count,
    };
    let json = serde_json::to_string_pretty(&report)
        .map_err(|error| format!("failed to serialize report: {error}"))?;
    if let Some(path) = &args.report {
        write_text(path, &format!("{json}\n"))?;
    } else {
        println!("{json}");
    }
    Ok(report)
}

fn parse_args(argv: Vec<String>) -> Result<Args, String> {
    let mut args = Args::default();
    let mut iter = argv.into_iter();
    while let Some(arg) = iter.next() {
        match arg.as_str() {
            "--sections" => args.sections = next_path(&mut iter, "--sections")?,
            "--feed" => args.feed = next_path(&mut iter, "--feed")?,
            "--sd" => args.sd = next_path(&mut iter, "--sd")?,
            "--query" => args.query = next_path(&mut iter, "--query")?,
            "--report" => args.report = next_path(&mut iter, "--report")?,
            "--help" | "-h" => return Err(usage()),
            _ => return Err(usage()),
        }
    }
    if args.sections.is_none() && args.feed.is_none() && args.sd.is_none() && args.query.is_none() {
        return Err(usage());
    }
    Ok(args)
}

fn next_path(
    iter: &mut impl Iterator<Item = String>,
    flag: &str,
) -> Result<Option<PathBuf>, String> {
    iter.next()
        .map(PathBuf::from)
        .map(Some)
        .ok_or_else(|| format!("missing value for {flag}\n{}", usage()))
}

fn usage() -> String {
    "usage: memtruth-vespa-preflight [--sections sections.jsonl] [--feed feed.jsonl] [--sd legal_doc.sd] [--query query.json] [--report report.json]".to_string()
}

fn read_sections(path: &PathBuf) -> Result<(Vec<LegalSectionDoc>, Vec<VespaDiagnostic>), String> {
    let (values, mut diagnostics) = read_jsonl_values(path, "sections")?;
    let mut sections = Vec::new();
    for (index, value) in values.iter().enumerate() {
        match legal_section_doc_from_value(value) {
            Ok(section) => sections.push(section),
            Err(ProjectionError::MissingSectionField(field)) => {
                diagnostics.push(VespaDiagnostic::error(
                    "E_REQUIRED_FIELD_EMPTY",
                    format!("sections[{index}].{}", section_input_field_path(field)),
                    format!("required section field `{field}` is missing or empty"),
                    format!(
                        "Populate `{}` before generating Vespa feed.",
                        section_input_field_path(field)
                    ),
                    None,
                ));
            }
            Err(error) => {
                diagnostics.push(VespaDiagnostic::error(
                    "E_SECTION_PARSE_FAILED",
                    format!("sections[{index}]"),
                    error.to_string(),
                    "Fix the section JSON fields before generating Vespa feed.",
                    None,
                ));
            }
        }
    }
    Ok((sections, diagnostics))
}

fn read_jsonl_values(
    path: &PathBuf,
    label: &str,
) -> Result<(Vec<Value>, Vec<VespaDiagnostic>), String> {
    let source = fs::read_to_string(path)
        .map_err(|error| format!("failed to read {}: {error}", path.display()))?;
    let mut values = Vec::new();
    let mut diagnostics = Vec::new();
    for (index, line) in source.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        match serde_json::from_str::<Value>(line) {
            Ok(Value::Object(_)) if label == "sections" || label == "feed" => {
                values.push(serde_json::from_str::<Value>(line).expect("already parsed"))
            }
            Ok(value) => diagnostics.push(VespaDiagnostic::error(
                "E_JSONL_PARSE_FAILED",
                format!("{label}[{index}]"),
                format!(
                    "{label} JSONL line {} is {} instead of an object",
                    index + 1,
                    json_type_name(&value)
                ),
                "Use one valid JSON object per JSONL line.",
                None,
            )),
            Err(error) => diagnostics.push(VespaDiagnostic::error(
                "E_JSONL_PARSE_FAILED",
                format!("{label}[{index}]"),
                format!(
                    "failed to parse {label} JSONL at line {} in {}: {error}",
                    index + 1,
                    path.display()
                ),
                "Use one valid JSON object per JSONL line.",
                None,
            )),
        }
    }
    Ok((values, diagnostics))
}

fn section_input_field_path(field: &str) -> &str {
    match field {
        "body_text" => "body_text",
        "doc_type" => "doc_type",
        other => other,
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

fn write_text(path: &PathBuf, text: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("failed to create {}: {error}", parent.display()))?;
    }
    fs::write(path, text).map_err(|error| format!("failed to write {}: {error}", path.display()))
}

fn print_report_error_summary(report: &CliReport) {
    let error_count = report
        .diagnostics
        .iter()
        .filter(|diagnostic| diagnostic.severity == DiagnosticSeverity::Error)
        .count();
    eprintln!("memtruth-vespa-preflight: {error_count} error(s)");
    for diagnostic in report
        .diagnostics
        .iter()
        .filter(|diagnostic| diagnostic.severity == DiagnosticSeverity::Error)
        .take(10)
    {
        eprintln!(
            "{} {}: {}",
            diagnostic.code, diagnostic.path, diagnostic.fix
        );
    }
}
