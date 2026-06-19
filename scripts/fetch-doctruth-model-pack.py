#!/usr/bin/env python3
"""Fetch DocTruth local model pack artifacts into the runtime cache."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import sys
import tempfile
import time
import urllib.request
from typing import Any


def main() -> int:
    args = parse_args()
    manifest_path = pathlib.Path(args.manifest).resolve()
    cache_dir = pathlib.Path(args.cache).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cache_dir.mkdir(parents=True, exist_ok=True)

    fetched = []
    for artifact in iter_artifacts(manifest):
        target = cache_dir / cache_filename(artifact)
        fetch_artifact(artifact, target)
        fetched.append(str(target))

    print(json.dumps({"ok": True, "cache": str(cache_dir), "artifacts": fetched}, separators=(",", ":")))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, help="Model pack JSON manifest")
    parser.add_argument("--cache", default=".doctruth/models", help="Runtime model cache directory")
    return parser.parse_args()


def iter_artifacts(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    artifacts: list[dict[str, Any]] = []
    for models in manifest.get("presets", {}).values():
        if isinstance(models, list):
            artifacts.extend(item for item in models if isinstance(item, dict))
    auxiliary = manifest.get("auxiliary", [])
    if isinstance(auxiliary, list):
        artifacts.extend(item for item in auxiliary if isinstance(item, dict))
    return artifacts


def cache_filename(artifact: dict[str, Any]) -> str:
    name = sanitize(str(artifact.get("name") or "model"))
    version = sanitize(str(artifact.get("version") or "v1"))
    return f"{name}-{version}.bin"


def sanitize(value: str) -> str:
    return "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value)


def fetch_artifact(artifact: dict[str, Any], target: pathlib.Path) -> None:
    expected_sha = normalize_sha(str(artifact.get("sha256") or artifact.get("expectedSha256") or ""))
    expected_size = int(artifact.get("sizeBytes") or 0)
    if target.is_file() and artifact_ready(target, expected_sha, expected_size):
        return

    url = str(artifact.get("url") or "")
    if not url:
        raise SystemExit(f"artifact has no url: {artifact.get('name')}")

    errors = []
    for attempt in range(1, 4):
        try:
            fetch_artifact_once(artifact, target, url, expected_sha, expected_size)
            return
        except SystemExit as exc:
            errors.append(str(exc))
            if attempt == 3:
                break
            time.sleep(attempt)
    raise SystemExit(errors[-1])


def fetch_artifact_once(
    artifact: dict[str, Any],
    target: pathlib.Path,
    url: str,
    expected_sha: str,
    expected_size: int,
) -> None:
    with tempfile.NamedTemporaryFile(prefix=f"{target.name}.", dir=str(target.parent), delete=False) as handle:
        temp_path = pathlib.Path(handle.name)
    try:
        download(url, temp_path)
        if not artifact_ready(temp_path, expected_sha, expected_size):
            actual_sha = sha256_file(temp_path)
            actual_size = temp_path.stat().st_size
            raise SystemExit(
                f"artifact verification failed for {artifact.get('name')}: "
                f"sha256={actual_sha} size={actual_size}"
            )
        temp_path.replace(target)
    finally:
        if temp_path.exists():
            temp_path.unlink()


def download(url: str, target: pathlib.Path) -> None:
    if url.startswith("file://"):
        shutil.copyfile(pathlib.Path(url[7:]), target)
        return
    with urllib.request.urlopen(url) as response, target.open("wb") as output:
        shutil.copyfileobj(response, output)


def artifact_ready(path: pathlib.Path, expected_sha: str, expected_size: int) -> bool:
    if not path.is_file():
        return False
    if expected_size and path.stat().st_size != expected_size:
        return False
    return not expected_sha or sha256_file(path) == expected_sha


def normalize_sha(value: str) -> str:
    return value.removeprefix("sha256:")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
