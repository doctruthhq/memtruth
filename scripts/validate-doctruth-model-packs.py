#!/usr/bin/env python3
"""Validate DocTruth model pack provenance and preprocessing parity contracts."""

from __future__ import annotations

import json
import pathlib
import sys
from typing import Any


REQUIRED_ARTIFACT_FIELDS = {
    "name",
    "version",
    "sha256",
    "sizeBytes",
    "required",
    "task",
    "backend",
    "format",
    "license",
    "url",
    "preprocessing",
    "parity",
}

REQUIRED_PREPROCESSING_FIELDS = {
    "inputLayout",
    "dtype",
    "colorSpace",
    "channelOrder",
    "resize",
    "resample",
    "scale",
    "mean",
    "std",
}

REQUIRED_PARITY_FIELDS = {
    "referenceEngine",
    "candidateEngine",
    "tensorDumpRequired",
    "firstTensorValuesRequired",
    "maxAbsDiff",
}


def main() -> int:
    failures: list[str] = []
    for raw_path in sys.argv[1:]:
        path = pathlib.Path(raw_path)
        failures.extend(validate_pack(path))
    if failures:
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1
    print(json.dumps({"ok": True, "packs": len(sys.argv) - 1}, separators=(",", ":")))
    return 0


def validate_pack(path: pathlib.Path) -> list[str]:
    if not path.is_file():
        return [f"{path}: missing model pack"]
    try:
        pack = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return [f"{path}: invalid JSON: {exc}"]
    failures: list[str] = []
    if not pack.get("packId"):
        failures.append(f"{path}: missing packId")
    source = pack.get("source")
    if not isinstance(source, dict) or not source.get("repository") or not source.get("license"):
        failures.append(f"{path}: missing source.repository/license")
    artifacts = iter_artifacts(pack)
    if not artifacts:
        failures.append(f"{path}: no model artifacts")
    for preset, index, artifact in artifacts:
        failures.extend(validate_artifact(path, preset, index, artifact))
    return failures


def iter_artifacts(pack: dict[str, Any]) -> list[tuple[str, int, dict[str, Any]]]:
    artifacts: list[tuple[str, int, dict[str, Any]]] = []
    presets = pack.get("presets")
    if isinstance(presets, dict):
        for preset, models in presets.items():
            if isinstance(models, list):
                for index, artifact in enumerate(models):
                    if isinstance(artifact, dict):
                        artifacts.append((str(preset), index, artifact))
    return artifacts


def validate_artifact(path: pathlib.Path, preset: str, index: int, artifact: dict[str, Any]) -> list[str]:
    label = f"{path}: presets.{preset}[{index}]"
    failures: list[str] = []
    missing = sorted(REQUIRED_ARTIFACT_FIELDS - artifact.keys())
    if missing:
        failures.append(f"{label}: missing fields {missing}")
        return failures
    failures.extend(validate_sha_size_url(label, artifact))
    failures.extend(validate_preprocessing(label, artifact.get("preprocessing")))
    failures.extend(validate_parity(label, artifact.get("parity")))
    return failures


def validate_sha_size_url(label: str, artifact: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    sha = artifact.get("sha256")
    if not isinstance(sha, str) or not sha.startswith("sha256:") or len(sha.removeprefix("sha256:")) != 64:
        failures.append(f"{label}: invalid sha256")
    if not isinstance(artifact.get("sizeBytes"), int) or artifact["sizeBytes"] <= 0:
        failures.append(f"{label}: invalid sizeBytes")
    url = artifact.get("url")
    if not isinstance(url, str) or not url.startswith(("https://", "file://")):
        failures.append(f"{label}: invalid url")
    return failures


def validate_preprocessing(label: str, preprocessing: Any) -> list[str]:
    if not isinstance(preprocessing, dict):
        return [f"{label}: preprocessing must be an object"]
    failures: list[str] = []
    missing = sorted(REQUIRED_PREPROCESSING_FIELDS - preprocessing.keys())
    if missing:
        failures.append(f"{label}: preprocessing missing fields {missing}")
    if preprocessing.get("channelOrder") not in {"RGB", "BGR", "GRAY"}:
        failures.append(f"{label}: preprocessing.channelOrder must be RGB, BGR, or GRAY")
    if preprocessing.get("inputLayout") not in {"NCHW", "NHWC"}:
        failures.append(f"{label}: preprocessing.inputLayout must be NCHW or NHWC")
    if not numeric_list(preprocessing.get("mean")):
        failures.append(f"{label}: preprocessing.mean must be numeric list")
    if not numeric_list(preprocessing.get("std")):
        failures.append(f"{label}: preprocessing.std must be numeric list")
    return failures


def validate_parity(label: str, parity: Any) -> list[str]:
    if not isinstance(parity, dict):
        return [f"{label}: parity must be an object"]
    failures: list[str] = []
    missing = sorted(REQUIRED_PARITY_FIELDS - parity.keys())
    if missing:
        failures.append(f"{label}: parity missing fields {missing}")
    if parity.get("tensorDumpRequired") is not True:
        failures.append(f"{label}: parity.tensorDumpRequired must be true")
    if parity.get("firstTensorValuesRequired") is not True:
        failures.append(f"{label}: parity.firstTensorValuesRequired must be true")
    max_diff = parity.get("maxAbsDiff")
    if not isinstance(max_diff, (int, float)) or max_diff < 0 or max_diff > 1e-5:
        failures.append(f"{label}: parity.maxAbsDiff must be <= 1e-5")
    return failures


def numeric_list(value: Any) -> bool:
    return isinstance(value, list) and all(isinstance(item, (int, float)) for item in value)


if __name__ == "__main__":
    raise SystemExit(main())
