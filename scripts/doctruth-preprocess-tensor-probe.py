#!/usr/bin/env python3
"""Dump preprocessing tensor fingerprints for ONNX/MNN parity checks."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import struct
from typing import Any


def main() -> int:
    args = parse_args()
    manifest = json.loads(pathlib.Path(args.manifest).read_text(encoding="utf-8"))
    artifact = find_artifact(manifest, args.preset, args.model)
    spec = artifact["preprocessing"]
    image = load_image(pathlib.Path(args.image))
    tensor, shape = preprocess(image, spec)
    print(json.dumps({
        "ok": True,
        "model": f"{artifact['name']}:{artifact['version']}",
        "shape": shape,
        "sha256": "sha256:" + tensor_sha256(tensor),
        "firstValues": [round(value, 8) for value in tensor[:args.first]],
        "preprocessing": spec,
    }, separators=(",", ":")))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--preset", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--first", type=int, default=16)
    return parser.parse_args()


def find_artifact(manifest: dict[str, Any], preset: str, model_name: str) -> dict[str, Any]:
    for artifact in manifest.get("presets", {}).get(preset, []):
        if artifact.get("name") == model_name:
            return artifact
    raise SystemExit(f"model not found in preset: {preset}/{model_name}")


def load_image(path: pathlib.Path) -> tuple[int, int, list[tuple[int, int, int]]]:
    if path.suffix.lower() == ".ppm":
        return load_ppm(path)
    try:
        from PIL import Image  # type: ignore
    except Exception as exc:
        raise SystemExit(f"Pillow is required for non-PPM images: {exc}") from exc
    image = Image.open(path).convert("RGB")
    width, height = image.size
    return width, height, list(image.getdata())


def load_ppm(path: pathlib.Path) -> tuple[int, int, list[tuple[int, int, int]]]:
    data = path.read_bytes()
    tokens: list[bytes] = []
    index = 0
    while len(tokens) < 4:
        while index < len(data) and data[index] in b" \t\r\n":
            index += 1
        if index < len(data) and data[index] == ord("#"):
            while index < len(data) and data[index] not in b"\r\n":
                index += 1
            continue
        start = index
        while index < len(data) and data[index] not in b" \t\r\n":
            index += 1
        tokens.append(data[start:index])
    if tokens[0] != b"P6":
        raise SystemExit("only binary P6 PPM is supported")
    width = int(tokens[1])
    height = int(tokens[2])
    max_value = int(tokens[3])
    if max_value != 255:
        raise SystemExit("only max value 255 PPM is supported")
    while index < len(data) and data[index] in b" \t\r\n":
        index += 1
    raw = data[index:]
    pixels = [tuple(raw[offset:offset + 3]) for offset in range(0, len(raw), 3)]
    return width, height, [(int(r), int(g), int(b)) for r, g, b in pixels]


def preprocess(image: tuple[int, int, list[tuple[int, int, int]]], spec: dict[str, Any]) -> tuple[list[float], list[int]]:
    width, height, pixels = resize(image, spec.get("resize", {}), str(spec.get("resample") or "nearest"))
    channels = channel_values(pixels, str(spec.get("channelOrder") or "RGB"))
    scale = float(spec.get("scale") or 1.0)
    mean = [float(value) for value in spec.get("mean", [0.0] * len(channels))]
    std = [float(value) for value in spec.get("std", [1.0] * len(channels))]
    channels = normalize_channels(channels, scale, mean, std)
    layout = str(spec.get("inputLayout") or "NCHW")
    if layout == "NHWC":
        tensor = [channels[channel][index] for index in range(width * height) for channel in range(len(channels))]
        return tensor, [1, height, width, len(channels)]
    tensor = [value for channel in channels for value in channel]
    return tensor, [1, len(channels), height, width]


def resize(
    image: tuple[int, int, list[tuple[int, int, int]]],
    resize_spec: dict[str, Any],
    resample: str,
) -> tuple[int, int, list[tuple[int, int, int]]]:
    source_width, source_height, pixels = image
    width = int(resize_spec.get("width") or source_width)
    height = int(resize_spec.get("height") or source_height)
    if width == source_width and height == source_height:
        return image
    if resample != "nearest":
        return pillow_resize(image, width, height, resample)
    resized = []
    for y in range(height):
        source_y = min(source_height - 1, int(y * source_height / height))
        for x in range(width):
            source_x = min(source_width - 1, int(x * source_width / width))
            resized.append(pixels[source_y * source_width + source_x])
    return width, height, resized


def pillow_resize(
    image: tuple[int, int, list[tuple[int, int, int]]],
    width: int,
    height: int,
    resample: str,
) -> tuple[int, int, list[tuple[int, int, int]]]:
    try:
        from PIL import Image  # type: ignore
    except Exception as exc:
        raise SystemExit(f"Pillow is required for {resample} resize: {exc}") from exc
    source_width, source_height, pixels = image
    pil = Image.new("RGB", (source_width, source_height))
    pil.putdata(pixels)
    filter_id = Image.Resampling.BILINEAR if resample == "bilinear" else Image.Resampling.NEAREST
    resized = pil.resize((width, height), filter_id)
    return width, height, list(resized.getdata())


def channel_values(pixels: list[tuple[int, int, int]], order: str) -> list[list[float]]:
    if order == "BGR":
        return [[float(pixel[index]) for pixel in pixels] for index in (2, 1, 0)]
    if order == "GRAY":
        return [[float(sum(pixel) / 3.0) for pixel in pixels]]
    return [[float(pixel[index]) for pixel in pixels] for index in (0, 1, 2)]


def normalize_channels(
    channels: list[list[float]],
    scale: float,
    mean: list[float],
    std: list[float],
) -> list[list[float]]:
    normalized = []
    for index, values in enumerate(channels):
        channel_mean = mean[index] if index < len(mean) else 0.0
        channel_std = std[index] if index < len(std) and std[index] else 1.0
        normalized.append([((value * scale) - channel_mean) / channel_std for value in values])
    return normalized


def tensor_sha256(values: list[float]) -> str:
    payload = b"".join(struct.pack("<f", float(value)) for value in values)
    return hashlib.sha256(payload).hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
