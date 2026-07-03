use serde_json::{Value, json};
use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    io::stdin()
        .read_to_string(&mut input)
        .expect("read worker request");
    let request: Value = serde_json::from_str(&input).expect("parse worker request");
    assert_eq!(request["modelRuntime"]["runtime"], "mnn");
    assert_eq!(request["modelRuntime"]["loadPolicy"], "lazy");
    assert_eq!(
        request["modelRuntime"]["unloadPolicy"],
        "idle-after-request"
    );
    assert_eq!(request["runtime_profile"], "edge-model");
    let model = request
        .get("models")
        .or_else(|| request.get("requiredModels"))
        .and_then(Value::as_array)
        .and_then(|models| models.first())
        .expect("model metadata");
    assert_eq!(model["backend"], "mnn");
    assert_eq!(model["format"], "mnn");
    let source_hash = request["source_hash"].as_str().unwrap_or("sha256:unknown");
    let preset = request["preset"].as_str().unwrap_or("table-lite");
    println!(
        "{}",
        json!({
            "ok": true,
            "document": {
                "docId": source_hash,
                "source": {
                    "sourceFilename": "mnn-promotion-smoke.pdf",
                    "sourceHash": source_hash,
                    "metadata": {
                        "sourceFilename": "mnn-promotion-smoke.pdf",
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
                        "unitId": "unit-0001",
                        "kind": "TEXT_BLOCK",
                        "page": 1,
                        "text": "MNN promotion smoke worker evidence",
                        "evidenceSpanIds": ["span-0001"],
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
                        "sourceObjectId": "mnn-worker-block-1",
                        "confidence": {
                            "score": 0.99,
                            "rationale": "rust mnn promotion smoke worker"
                        },
                        "warnings": []
                    }],
                    "tables": []
                },
                "parserRun": {
                    "parserRunId": "mnn-promotion-smoke",
                    "parserVersion": "rust-smoke-worker",
                    "preset": preset,
                    "backend": "rust-sidecar+model-worker",
                    "models": ["slanet-plus:v1"],
                    "runtime": "doctruth-runtime",
                    "warnings": []
                },
                "auditGradeStatus": "AUDIT_GRADE"
            },
            "metrics": {
                "coldStartMs": 12.0,
                "inferenceMs": 5.0,
                "rssMb": 111.0,
                "peakMemoryMb": 123.0,
                "loadedModels": ["slanet-plus:v1"],
                "unload": "idle-after-request"
            }
        })
    );
}
