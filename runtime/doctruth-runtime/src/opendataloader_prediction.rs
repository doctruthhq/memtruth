use std::fs;
use std::path::{Path, PathBuf};

use serde_json::Value;

use crate::{error_json, opendataloader_report, pretty_json};

pub(crate) struct PredictionPackage {
    root: PathBuf,
    markdown_dir: PathBuf,
    cases_dir: PathBuf,
    failures_dir: PathBuf,
}

impl PredictionPackage {
    pub(crate) fn prepare(root: &Path) -> Result<Self, String> {
        let package = Self {
            root: root.to_path_buf(),
            markdown_dir: root.join("markdown"),
            cases_dir: root.join("cases"),
            failures_dir: root.join("failures"),
        };
        package.create_dir(&package.markdown_dir)?;
        package.create_dir(&package.cases_dir)?;
        package.create_dir(&package.failures_dir)?;
        Ok(package)
    }

    pub(crate) fn markdown_dir(&self) -> &Path {
        &self.markdown_dir
    }

    pub(crate) fn write_markdown(
        &self,
        document_id: &str,
        markdown: &str,
    ) -> Result<PathBuf, String> {
        let path = self.markdown_dir.join(format!("{document_id}.md"));
        fs::write(&path, markdown).map_err(write_error)?;
        Ok(path)
    }

    pub(crate) fn write_case(&self, document_id: &str, value: &Value) -> Result<(), String> {
        self.write_json(&self.cases_dir.join(format!("{document_id}.json")), value)
    }

    pub(crate) fn write_failure(&self, document_id: &str, value: &Value) -> Result<(), String> {
        self.write_json(
            &self.failures_dir.join(format!("{document_id}.json")),
            value,
        )
    }

    pub(crate) fn write_summary(&self, summary: &Value) -> Result<(), String> {
        self.write_json(&self.root.join("summary.json"), summary)
    }

    pub(crate) fn write_resources(&self, resources: &Value) -> Result<(), String> {
        self.write_json(&self.root.join("resources.json"), resources)
    }

    pub(crate) fn write_reference_comparison(&self, comparison: &Value) -> Result<(), String> {
        self.write_json(&self.root.join("reference-comparison.json"), comparison)?;
        fs::write(
            self.root.join("reference-comparison.md"),
            opendataloader_report::reference_comparison_markdown(comparison),
        )
        .map_err(write_error)
    }

    fn create_dir(&self, path: &Path) -> Result<(), String> {
        fs::create_dir_all(path).map_err(write_error)
    }

    fn write_json(&self, path: &Path, value: &Value) -> Result<(), String> {
        fs::write(path, pretty_json(value)?).map_err(write_error)
    }
}

fn write_error(error: std::io::Error) -> String {
    error_json("BENCHMARK_REPORT_WRITE_FAILED", &error.to_string()).to_string()
}
