"""DocTruth JSON worker adapter for local ONNXRuntime parser model experiments."""

import base64
import json
import math
import pathlib
import resource
import subprocess
import sys
import tempfile
import time


def main() -> int:
    started = time.perf_counter()
    if len(sys.argv) > 1 and sys.argv[1] == "--doctor":
        return doctor()
    try:
        request = json.loads(sys.stdin.read())
    except Exception as exc:
        return emit_failure("worker_protocol_error", f"invalid request JSON: {exc}")
    try:
        model = ready_model(request)
        inference = run_onnx(model, request)
        document = trust_document(request, model, inference)
    except Exception as exc:
        return emit_failure("onnx_worker_failed", str(exc))
    print(json.dumps({
        "ok": True,
        "document": document,
        "metrics": metrics(started, inference),
    }, ensure_ascii=False, separators=(",", ":")))
    return 0


def doctor() -> int:
    try:
        import onnxruntime as ort  # type: ignore
    except Exception as exc:
        return emit_doctor(False, "onnxruntime_unavailable", str(exc), [])
    providers = list(ort.get_available_providers())
    return emit_doctor(True, "ready", "onnxruntime available", providers)


def ready_model(request: dict) -> dict:
    models = request.get("models")
    if not isinstance(models, list) or not models:
        raise ValueError("request has no models")
    for model in models:
        if model.get("backend") == "onnxruntime" and model.get("format") == "onnx":
            if model.get("cacheStatus") != "READY":
                raise ValueError(f"model cache is not READY: {model.get('identity') or model.get('name')}")
            return model
    raise ValueError("no READY onnxruntime/onnx model in request")


def run_onnx(model: dict, request: dict) -> dict:
    import numpy as np  # type: ignore
    import onnxruntime as ort  # type: ignore

    started = time.perf_counter()
    session = ort.InferenceSession(str(pathlib.Path(model["cachePath"])), providers=["CPUExecutionProvider"])
    inputs = {}
    input_sources = []
    for item in session.get_inputs():
        inputs[item.name], source = input_tensor(item, request, np)
        input_sources.append(source)
    output_values = session.run(None, inputs)
    named = {}
    for index, item in enumerate(session.get_outputs()):
        named[item.name] = np.asarray(output_values[index]) if index < len(output_values) else np.asarray([])
    scalar = float(np.asarray(output_values[0]).reshape(-1)[0]) if output_values else 0.0
    return {
        "outputs": named,
        "scalar": scalar,
        "inferenceWallMs": elapsed_ms(started),
        "inputSource": "+".join(input_sources) if input_sources else "none",
    }


def input_tensor(item, request: dict, np):
    shape = input_shape(item.shape)
    if "orig_target_sizes" in item.name:
        return np.asarray([[640, 640]], dtype=np.int64), "orig_target_sizes"
    if len(shape) == 4:
        image = load_page_image(request)
        if image is not None:
            return image_tensor(image, shape, np, item.name == "images"), "rendered_page"
    return np.ones(shape, dtype=np.float32), "synthetic_tensor"


def load_page_image(request: dict):
    try:
        from PIL import Image  # type: ignore
    except Exception:
        return None
    source = pathlib.Path(str(request.get("sourcePath") or ""))
    if source.is_file() and source.suffix.lower() == ".pdf":
        rendered = render_pdf_page(source)
        if rendered is not None:
            image = Image.open(rendered).convert("RGB")
            image.load()
            rendered.unlink(missing_ok=True)
            return image
    if source.is_file() and source.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"}:
        return Image.open(source).convert("RGB")
    encoded = str(request.get("bytesBase64") or "")
    if encoded and str(request.get("sourceFilename") or "").lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
        import io
        return Image.open(io.BytesIO(base64.b64decode(encoded))).convert("RGB")
    return None


def render_pdf_page(source: pathlib.Path):
    with tempfile.TemporaryDirectory(prefix="doctruth-onnx-page-") as tmp:
        prefix = pathlib.Path(tmp) / "page"
        try:
            subprocess.run(
                ["pdftoppm", "-singlefile", "-png", "-r", "96", str(source), str(prefix)],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            return None
        rendered = prefix.with_suffix(".png")
        if not rendered.exists():
            return None
        stable = pathlib.Path(tempfile.mkstemp(prefix="doctruth-onnx-page-", suffix=".png")[1])
        stable.write_bytes(rendered.read_bytes())
        return stable


def image_tensor(image, shape: list[int], np, imagenet_normalize: bool = False):
    _, channels, height, width = shape
    resized = image.resize((width, height))
    array = np.asarray(resized, dtype=np.float32) / 255.0
    if channels == 1:
        array = array.mean(axis=2, keepdims=True)
    elif channels >= 3:
        array = array[:, :, :3]
    array = np.transpose(array, (2, 0, 1))
    if array.shape[0] < channels:
        padding = np.zeros((channels - array.shape[0], height, width), dtype=np.float32)
        array = np.concatenate([array, padding], axis=0)
    array = array[:channels]
    if imagenet_normalize and channels >= 3:
        mean = np.asarray([0.485, 0.456, 0.406], dtype=np.float32)[:, None, None]
        std = np.asarray([0.229, 0.224, 0.225], dtype=np.float32)[:, None, None]
        array[:3] = (array[:3] - mean) / std
    return array[np.newaxis, :, :, :]


def input_shape(raw_shape: list) -> list[int]:
    if len(raw_shape) == 4:
        return [
            positive_dim(raw_shape[0], 1),
            positive_dim(raw_shape[1], 3),
            positive_dim(raw_shape[2], 800),
            positive_dim(raw_shape[3], 800),
        ]
    return [positive_dim(dim, 1) for dim in raw_shape]


def positive_dim(dim, fallback: int) -> int:
    return dim if isinstance(dim, int) and dim > 0 else fallback


def metrics(started: float, inference: dict) -> dict:
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak_mb = peak / (1024 * 1024) if sys.platform == "darwin" else peak / 1024
    return {
        "wallMs": elapsed_ms(started),
        "inferenceWallMs": max(0.001, float(inference.get("inferenceWallMs", 0.0))),
        "inputSource": str(inference.get("inputSource") or "unknown"),
        "rssMb": round(max(0.001, peak_mb), 3),
        "peakMemoryMb": round(max(0.001, peak_mb), 3),
    }


def elapsed_ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000.0, 3)


def trust_document(request: dict, model: dict, inference: dict) -> dict:
    if model.get("task") == "layout-detection":
        return layout_document(request, model, inference)
    if model.get("task") == "table-structure-recognition":
        return table_document(request, model, inference)
    return identity_document(request, model, inference)


def identity_document(request: dict, model: dict, inference: dict) -> dict:
    model_id = model_identity(model)
    scalar = float(inference.get("scalar", 0.0))
    text = "ONNX inference succeeded"
    unit = trust_unit("unit-onnx-1", "TEXT_BLOCK", text, 1, {"x0": 100, "y0": 100, "x1": 500, "y1": 150},
                      f"onnx:{model_id}", ["span-onnx-1"], 1.0, f"onnxruntime output={scalar:g}")
    return base_document(request, model, "sha256:onnx-smoke-page", [unit], [], "UNKNOWN")


def table_document(request: dict, model: dict, inference: dict) -> dict:
    model_id = model_identity(model)
    if table_class_count(inference) >= 7:
        return real_tatr_table_document(request, model, inference)
    detections = detections_from_outputs(inference, ["table", "cell"])
    return synthetic_tatr_table_document(request, model, model_id, detections)


def synthetic_tatr_table_document(request: dict, model: dict, model_id: str, detections: list[dict]) -> dict:
    table = next((item for item in detections if item["label"] == "table"), None)
    cells = [item for item in detections if item["label"] == "cell"]
    if table is None and cells:
        table = {"bbox": union_bbox([cell["bbox"] for cell in cells]), "score": min(cell["score"] for cell in cells)}
    units = []
    table_cells = []
    for index, cell in enumerate(cells, start=1):
        cell_id = f"cell-{index:04d}"
        text = f"model cell {index}"
        table_cells.append({
            "cellId": cell_id,
            "rowRange": {"start": 0, "end": 0},
            "columnRange": {"start": index - 1, "end": index - 1},
            "boundingBox": cell["bbox"],
            "text": text,
        })
        units.append(trust_unit(f"unit-{index:04d}", "TABLE_CELL", text, index, cell["bbox"],
                                f"onnx:{model_id}#{cell_id}", [f"span-{index:04d}"],
                                cell["score"], "onnxruntime tatr cell"))
    tables = []
    if table is not None:
        tables.append({
            "tableId": "table-0001",
            "pageNumber": 1,
            "boundingBox": table["bbox"],
            "confidence": {"score": table["score"], "rationale": "onnxruntime tatr table"},
            "cells": table_cells,
        })
    warnings = table_warnings([table] + cells)
    status = "NOT_AUDIT_GRADE" if warnings else "UNKNOWN"
    return base_document(request, model, "sha256:onnx-tatr-page", units, tables, status, warnings)


def real_tatr_table_document(request: dict, model: dict, inference: dict) -> dict:
    model_id = model_identity(model)
    labels = [
        "table",
        "table column",
        "table row",
        "table column header",
        "table projected row header",
        "table spanning cell",
    ]
    detections = detections_from_outputs(inference, labels)
    table = best_detection(detections, "table")
    columns = sorted(detections_by_label(detections, "table column"), key=lambda item: item["bbox"]["x0"])
    rows = sorted(detections_by_label(detections, "table row"), key=lambda item: item["bbox"]["y0"])
    if table is None and rows and columns:
        table = {"label": "table", "bbox": union_bbox([item["bbox"] for item in rows + columns]),
                 "score": min(item["score"] for item in rows + columns)}
    table_cells, units = real_tatr_cells(model_id, table, rows, columns)
    tables = []
    if table is not None:
        tables.append({
            "tableId": "table-0001",
            "pageNumber": 1,
            "boundingBox": table["bbox"],
            "confidence": {"score": table["score"], "rationale": "onnxruntime tatr table"},
            "cells": table_cells,
        })
    warnings = table_warnings([table] + rows + columns)
    if table is not None and not table_cells:
        warnings.append({
            "code": "table_structure_low_confidence",
            "severity": "SEVERE",
            "message": "table structure confidence below 0.85: missing row/column grid",
        })
    status = "NOT_AUDIT_GRADE" if warnings else "AUDIT_GRADE"
    return base_document(request, model, "sha256:onnx-tatr-page", units, tables, status, warnings)


def real_tatr_cells(model_id: str, table: dict | None, rows: list[dict], columns: list[dict]) -> tuple[list[dict], list[dict]]:
    if table is None or not rows or not columns:
        return [], []
    table_cells = []
    units = []
    order = 1
    for row_index, row in enumerate(rows):
        for column_index, column in enumerate(columns):
            bbox = clipped_intersection(row["bbox"], column["bbox"], table["bbox"])
            if bbox is None:
                continue
            cell_id = f"cell-{order:04d}"
            text = f"model cell {row_index + 1},{column_index + 1}"
            score = min(float(table["score"]), float(row["score"]), float(column["score"]))
            table_cells.append({
                "cellId": cell_id,
                "rowRange": {"start": row_index, "end": row_index},
                "columnRange": {"start": column_index, "end": column_index},
                "boundingBox": bbox,
                "text": text,
            })
            units.append(trust_unit(f"unit-{order:04d}", "TABLE_CELL", text, order, bbox,
                                    f"onnx:{model_id}#{cell_id}", [f"span-{order:04d}"],
                                    score, "onnxruntime tatr row-column cell"))
            order += 1
    return table_cells, units


def layout_document(request: dict, model: dict, inference: dict) -> dict:
    model_id = model_identity(model)
    detections = sorted(layout_detections(inference), key=reading_order_key)
    units = []
    low_confidence = False
    for index, item in enumerate(detections, start=1):
        label = item["label"]
        text = "model heading region" if label == "heading" else "model list region" if label == "list" else "model body region"
        warnings = []
        if item["score"] < 0.85:
            low_confidence = True
            warnings.append(layout_low_confidence_warning(item["score"]))
        units.append(trust_unit(f"unit-layout-{index:04d}", "TEXT_BLOCK", text, index, item["bbox"],
                                f"onnx:{model_id}#layout-{index:04d}", [f"span-layout-{index:04d}"],
                                item["score"], f"onnxruntime layout {label}", warnings))
    status = "NOT_AUDIT_GRADE" if low_confidence else "AUDIT_GRADE"
    return base_document(request, model, "sha256:onnx-layout-page", units, [], status)


def layout_detections(inference: dict) -> list[dict]:
    if tensor(inference.get("outputs", {}), "labels") is not None and tensor(inference.get("outputs", {}), "scores") is not None:
        return rtdetr_layout_detections(inference)
    return detections_from_outputs(inference, ["heading", "body", "list"])


def base_document(request: dict, model: dict, image_hash: str, units: list[dict],
                  tables: list[dict], status: str, warnings: list[dict] | None = None) -> dict:
    source_name = pathlib.Path(request.get("sourcePath") or request.get("sourceFilename") or "source.pdf").name
    source_hash = str(request.get("sourceHash") or "sha256:unknown")
    model_id = model_identity(model)
    return {
        "docId": source_hash,
        "source": {"sourceFilename": source_name, "sourceHash": source_hash,
                   "metadata": {"sourceFilename": source_name, "pageCount": 1}},
        "body": {
            "pages": [{
                "pageNumber": 1, "width": 612, "height": 792,
                "textLayerAvailable": True, "imageHash": image_hash,
            }],
            "units": units,
            "tables": tables,
        },
        "parserRun": {
            "parserVersion": "1.0.0", "preset": request.get("preset") or "model",
            "backend": "pdfbox+model-worker", "models": [model_id], "warnings": warnings or [],
        },
        "auditGradeStatus": status,
    }


def trust_unit(unit_id: str, kind: str, text: str, order: int, bbox: dict,
               source_object: str, spans: list[str], score: float, rationale: str,
               warnings: list[dict] | None = None) -> dict:
    return {
        "unitId": unit_id, "kind": kind, "page": 1, "text": text, "evidenceSpanIds": spans,
        "location": {"page": 1, "readingOrder": order, "boundingBox": bbox},
        "sourceObjectId": source_object,
        "confidence": {"score": score, "rationale": rationale},
        "warnings": warnings or [],
    }


def model_identity(model: dict) -> str:
    return f"{model['name']}:{model['version']}"


def layout_low_confidence_warning(score: float) -> dict:
    return {
        "code": "layout_low_confidence",
        "severity": "SEVERE",
        "message": f"layout confidence below 0.85: {score:.3f}",
    }


def table_warnings(items: list[dict | None]) -> list[dict]:
    scores = [float(item["score"]) for item in items if item is not None]
    weak = [score for score in scores if score < 0.85]
    if not weak:
        return []
    return [{
        "code": "table_structure_low_confidence",
        "severity": "SEVERE",
        "message": f"table structure confidence below 0.85: {min(weak):.3f}",
    }]


def detections_from_outputs(inference: dict, labels: list[str]) -> list[dict]:
    outputs = inference.get("outputs", {})
    logits = tensor(outputs, "logits")
    boxes = tensor(outputs, "boxes")
    if logits is None or boxes is None:
        raise ValueError("model must output logits and boxes")
    logits = logits.reshape(-1, logits.shape[-1])
    boxes = boxes.reshape(-1, 4)
    detections = []
    for index, scores in enumerate(logits):
        if index >= len(boxes):
            break
        best = int(scores[:-1].argmax()) if len(scores) > 1 else int(scores.argmax())
        score = softmax_score(scores, best)
        if score < 0.50:
            continue
        if best < len(labels):
            detections.append({"label": labels[best], "score": round(score, 6), "bbox": normalize_box(boxes[index])})
    return detections


def rtdetr_layout_detections(inference: dict) -> list[dict]:
    outputs = inference.get("outputs", {})
    label_values = tensor(outputs, "labels")
    box_values = tensor(outputs, "boxes")
    score_values = tensor(outputs, "scores")
    if label_values is None or box_values is None or score_values is None:
        raise ValueError("RT-DETR model must output labels, boxes, and scores")
    labels = label_values.reshape(-1)
    boxes = box_values.reshape(-1, 4)
    scores = score_values.reshape(-1)
    detections = []
    for index, score_value in enumerate(scores):
        if index >= len(labels) or index >= len(boxes):
            break
        score = float(score_value)
        if score < 0.20:
            continue
        label = layout_label(int(labels[index]))
        detections.append({
            "label": label,
            "score": round(score, 6),
            "bbox": normalize_xyxy_box(boxes[index], 640.0, 640.0),
        })
    return detections


def layout_label(index: int) -> str:
    classes = [
        "Caption",
        "Footnote",
        "Formula",
        "ListItem",
        "PageFooter",
        "PageHeader",
        "Picture",
        "SectionHeader",
        "Table",
        "Text",
        "Title",
        "DocumentIndex",
        "Code",
        "CheckboxSelected",
        "CheckboxUnselected",
        "Form",
        "KeyValueRegion",
    ]
    label = classes[index] if 0 <= index < len(classes) else "Text"
    if label in {"Title", "SectionHeader", "PageHeader"}:
        return "heading"
    if label == "ListItem":
        return "list"
    return "body"


def table_class_count(inference: dict) -> int:
    logits = tensor(inference.get("outputs", {}), "logits")
    return int(logits.shape[-1]) if logits is not None and len(logits.shape) > 0 else 0


def best_detection(detections: list[dict], label: str) -> dict | None:
    matches = detections_by_label(detections, label)
    if not matches:
        return None
    return max(matches, key=lambda item: float(item["score"]))


def detections_by_label(detections: list[dict], label: str) -> list[dict]:
    return [item for item in detections if item["label"] == label]


def reading_order_key(item: dict) -> tuple[float, float]:
    box = item["bbox"]
    return (box["y0"], box["x0"])


def tensor(outputs: dict, token: str):
    for name, value in outputs.items():
        if token in name:
            return value
    return None


def softmax_score(values, index: int) -> float:
    exps = [math.exp(float(value) - max(float(item) for item in values)) for value in values]
    total = sum(exps)
    return exps[index] / total if total else 0.0


def normalize_box(box) -> dict:
    cx, cy, width, height = [float(item) for item in box]
    return {
        "x0": round((cx - width / 2.0) * 1000.0, 3),
        "y0": round((cy - height / 2.0) * 1000.0, 3),
        "x1": round((cx + width / 2.0) * 1000.0, 3),
        "y1": round((cy + height / 2.0) * 1000.0, 3),
    }


def normalize_xyxy_box(box, width: float, height: float) -> dict:
    x0, y0, x1, y1 = [float(item) for item in box]
    return {
        "x0": round(max(0.0, min(1000.0, x0 / width * 1000.0)), 3),
        "y0": round(max(0.0, min(1000.0, y0 / height * 1000.0)), 3),
        "x1": round(max(0.0, min(1000.0, x1 / width * 1000.0)), 3),
        "y1": round(max(0.0, min(1000.0, y1 / height * 1000.0)), 3),
    }


def union_bbox(boxes: list[dict]) -> dict:
    return {
        "x0": min(box["x0"] for box in boxes),
        "y0": min(box["y0"] for box in boxes),
        "x1": max(box["x1"] for box in boxes),
        "y1": max(box["y1"] for box in boxes),
    }


def clipped_intersection(first: dict, second: dict, clip: dict) -> dict | None:
    box = {
        "x0": max(first["x0"], second["x0"], clip["x0"], 0.0),
        "y0": max(first["y0"], second["y0"], clip["y0"], 0.0),
        "x1": min(first["x1"], second["x1"], clip["x1"], 1000.0),
        "y1": min(first["y1"], second["y1"], clip["y1"], 1000.0),
    }
    if box["x1"] <= box["x0"] or box["y1"] <= box["y0"]:
        return None
    return {key: round(value, 3) for key, value in box.items()}


def emit_failure(code: str, message: str) -> int:
    print(json.dumps({"ok": False, "code": code, "message": message}, ensure_ascii=False, separators=(",", ":")))
    return 0


def emit_doctor(ok: bool, code: str, message: str, providers: list[str]) -> int:
    print(json.dumps({
        "ok": ok,
        "code": code,
        "runtime": "onnxruntime",
        "engine": "onnxruntime",
        "message": message,
        "providers": providers,
        "loadedModels": [],
        "rssMb": 0,
        "peakMemoryMb": 0,
    }, ensure_ascii=False, separators=(",", ":")))
    return 0
