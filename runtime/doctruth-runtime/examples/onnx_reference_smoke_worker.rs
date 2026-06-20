use serde_json::{Value, json};
use std::io::{self, Read};
use std::path::Path;

fn main() {
    let mut input = String::new();
    io::stdin()
        .read_to_string(&mut input)
        .expect("read worker request");
    let request: Value = serde_json::from_str(&input).expect("parse worker request");
    assert_eq!(request["profile"], "benchmark-oracle");
    assert_eq!(request["runtime_profile"], "benchmark-oracle");
    assert_eq!(request["modelRuntime"]["runtime"], "onnxruntime");
    assert_eq!(request["modelRuntime"]["referenceOnly"], true);
    let model = request
        .get("models")
        .and_then(Value::as_array)
        .and_then(|models| models.first())
        .expect("model metadata");
    assert_eq!(model["backend"], "onnxruntime");
    assert_eq!(model["format"], "onnx");
    assert_eq!(model["cacheStatus"], "READY");
    assert!(
        Path::new(model["cachePath"].as_str().expect("cachePath")).is_file(),
        "{}",
        model["cachePath"]
    );
    let source_hash = request["source_hash"].as_str().unwrap_or("sha256:unknown");
    let preset = request["preset"].as_str().unwrap_or("table-lite");
    let model_identity = model["identity"]
        .as_str()
        .unwrap_or("onnx-reference:unknown");

    println!(
        "{}",
        json!({
            "ok": true,
            "document": {
                "docId": source_hash,
                "source": {
                    "sourceFilename": "onnx-reference-smoke.pdf",
                    "sourceHash": source_hash,
                    "metadata": {
                        "sourceFilename": "onnx-reference-smoke.pdf",
                        "pageCount": 1
                    }
                },
                "body": {
                    "pages": [{
                        "pageNumber": 1,
                        "width": 612.0,
                        "height": 792.0,
                        "textLayerAvailable": true,
                        "imageHash": format!("sha256:{}", "0".repeat(64))
                    }],
                    "units": [{
                        "unitId": "unit-onnx-0001",
                        "kind": "TABLE_CELL",
                        "page": 1,
                        "text": "ONNX reference smoke worker evidence",
                        "evidenceSpanIds": ["span-onnx-0001"],
                        "location": {
                            "page": 1,
                            "readingOrder": 1,
                            "boundingBox": {
                                "x0": 72.0,
                                "y0": 90.0,
                                "x1": 540.0,
                                "y1": 132.0
                            }
                        },
                        "sourceObjectId": "onnx-reference-worker-cell-1",
                        "confidence": {
                            "score": 0.99,
                            "rationale": "rust onnx reference smoke worker"
                        },
                        "warnings": []
                    }],
                    "tables": []
                },
                "parserRun": {
                    "parserRunId": "onnx-reference-smoke",
                    "parserVersion": "rust-smoke-worker",
                    "preset": preset,
                    "profile": "benchmark-oracle",
                    "backend": "rust-sidecar+model-worker",
                    "models": [model_identity],
                    "runtime": "doctruth-runtime",
                    "warnings": []
                },
                "auditGradeStatus": "AUDIT_GRADE"
            },
            "metrics": {
                "runtime": "onnxruntime",
                "referenceOnly": true,
                "coldStartMs": 1.0,
                "inferenceMs": 1.0,
                "rssMb": 64.0,
                "peakMemoryMb": 80.0,
                "loadedModels": [model_identity],
                "unload": {"status": "scheduled", "policy": "idle-after-request"}
            }
        })
    );
}
