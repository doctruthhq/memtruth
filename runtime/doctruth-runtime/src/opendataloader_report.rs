use serde_json::{Value, json};

#[derive(Debug, Clone, Copy)]
pub(crate) struct MemorySnapshot {
    pub(crate) rss_mb: u64,
    pub(crate) peak_memory_mb: u64,
}

pub(crate) fn resources_json(
    backend: &str,
    java_backend_startup_ms: &Value,
    java_backend_command: &Value,
    document_count: usize,
    parsed_count: usize,
    failed_count: usize,
    total_elapsed_ms: f64,
    start_memory: MemorySnapshot,
    end_memory: MemorySnapshot,
) -> Value {
    json!({
        "backend": backend,
        "documentCount": document_count,
        "parsedCount": parsed_count,
        "failedCount": failed_count,
        "totalElapsedMs": total_elapsed_ms,
        "javaBackendStartupMs": java_backend_startup_ms,
        "javaBackendCommand": java_backend_command,
        "rssSamples": {
            "measurement": "process-rss",
            "startMb": start_memory.rss_mb,
            "endMb": end_memory.rss_mb,
            "peakMb": end_memory.peak_memory_mb.max(start_memory.peak_memory_mb)
        }
    })
}

pub(crate) fn reference_comparison_placeholder(
    engine: &str,
    backend: &str,
    document_count: usize,
    parsed_count: usize,
    failed_count: usize,
) -> Value {
    json!({
        "status": "not-run",
        "reason": "reference evaluation was not provided for this prediction run",
        "candidate": {
            "engine": engine,
            "backend": backend,
            "documentCount": document_count,
            "parsedCount": parsed_count,
            "failedCount": failed_count
        }
    })
}

pub(crate) fn reference_comparison_markdown(comparison: &Value) -> String {
    let engine = comparison
        .pointer("/candidate/engine")
        .and_then(Value::as_str)
        .unwrap_or("unknown");
    let backend = comparison
        .pointer("/candidate/backend")
        .and_then(Value::as_str)
        .unwrap_or("unknown");
    format!(
        "# Reference comparison not run\n\nCandidate engine: `{engine}`\n\nBackend: `{backend}`\n\nRun `opendataloader_compare_reports` with a reference evaluation to produce score deltas.\n"
    )
}
