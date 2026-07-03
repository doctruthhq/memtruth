#!/usr/bin/env python3
"""Run OpenDataLoader hybrid as a DocTruth benchmark-oracle JSON runner.

This script is intentionally benchmark-only. It is meant to be referenced by:

    DOCTRUTH_OPENDATALOADER_HYBRID_ORACLE_COMMAND=./scripts/doctruth_opendataloader_hybrid_oracle.py
    doctruth benchmark-oracle --engine opendataloader-hybrid file.pdf --json

The output contract is consumed by DocTruth's benchmark-oracle command and then
normalized into TrustDocument. It is not a production parser fallback.
"""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


HYBRID_URL = "http://127.0.0.1:5002"
HEALTH_ENDPOINT = f"{HYBRID_URL}/health"
STARTUP_TIMEOUT_SECONDS = 120


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdf", help="PDF to convert with OpenDataLoader hybrid")
    parser.add_argument(
        "--hybrid",
        default="docling-fast",
        help="OpenDataLoader hybrid backend name. Default: docling-fast.",
    )
    return parser.parse_args()


def package_version(name: str) -> str:
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return "unknown"


def markdown_output(output_dir: Path, pdf: Path) -> str:
    direct = output_dir / f"{pdf.stem}.md"
    if direct.is_file():
        return direct.read_text(encoding="utf-8")
    markdowns = sorted(output_dir.rglob("*.md"))
    if not markdowns:
        raise FileNotFoundError(f"OpenDataLoader hybrid did not write Markdown under {output_dir}")
    return markdowns[0].read_text(encoding="utf-8")


def ensure_java_on_path() -> None:
    candidates = [
        Path(os.environ.get("JAVA_HOME", "")) / "bin",
        Path("/opt/homebrew/opt/openjdk/bin"),
        Path("/usr/local/opt/openjdk/bin"),
        Path("/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home/bin"),
    ]
    current = os.environ.get("PATH", "")
    for candidate in candidates:
        java = candidate / "java"
        if java.is_file() and os.access(java, os.X_OK):
            os.environ["PATH"] = f"{candidate}{os.pathsep}{current}"
            return


def hybrid_server_running() -> bool:
    try:
        request = urllib.request.Request(HEALTH_ENDPOINT, method="GET")
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status == 200
    except (OSError, urllib.error.URLError):
        return False


def start_hybrid_server_if_needed() -> subprocess.Popen[bytes] | None:
    if hybrid_server_running():
        return None
    process = subprocess.Popen(
        [sys.executable, "-m", "opendataloader_pdf.hybrid_server"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if process.poll() is not None:
            stderr = process.stderr.read().decode(errors="replace") if process.stderr else ""
            raise RuntimeError(
                f"OpenDataLoader hybrid server exited with {process.returncode}: {stderr}"
            )
        if hybrid_server_running():
            return process
        time.sleep(2)
    stop_hybrid_server(process)
    raise TimeoutError(
        f"OpenDataLoader hybrid server did not become ready within {STARTUP_TIMEOUT_SECONDS}s"
    )


def stop_hybrid_server(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()


def main() -> int:
    args = parse_args()
    pdf = Path(args.pdf)
    started = time.monotonic()
    try:
        import opendataloader_pdf
    except ImportError as exc:
        raise SystemExit(
            "opendataloader-pdf hybrid oracle unavailable: install opendataloader-pdf[hybrid]"
        ) from exc

    ensure_java_on_path()
    server_process = start_hybrid_server_if_needed()
    with tempfile.TemporaryDirectory(prefix="doctruth-opendataloader-hybrid-") as temp:
        try:
            output_dir = Path(temp)
            opendataloader_pdf.convert(
                input_path=[pdf],
                output_dir=output_dir,
                format=["markdown"],
                hybrid=args.hybrid,
                hybrid_url=HYBRID_URL,
                image_output="off",
                quiet=True,
            )
            elapsed_ms = round((time.monotonic() - started) * 1000)
            payload = {
                "markdown": markdown_output(output_dir, pdf),
                "elapsedMs": elapsed_ms,
                "externalBackend": {
                    "name": "opendataloader-pdf",
                    "version": package_version("opendataloader-pdf"),
                    "doclingVersion": package_version("docling"),
                    "mode": args.hybrid,
                    "serverUrl": HYBRID_URL,
                },
            }
            json.dump(payload, sys.stdout, ensure_ascii=False, separators=(",", ":"))
            sys.stdout.write("\n")
        finally:
            stop_hybrid_server(server_process)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
