"""Small deterministic SVG/PNG/PDF renderer for validated aggregate data."""
from __future__ import annotations

import json
import math
from pathlib import Path
import struct
import zlib
from typing import Any

from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text
from experiments.scripts.common.hashing import sha256_file


COLORS = ("#0072B2", "#D55E00", "#009E73", "#CC79A7", "#E69F00", "#56B4E9")


def _png(width: int, height: int, bars: list[tuple[int, int, int, int, tuple[int, int, int]]]) -> bytes:
    pixels = bytearray([255] * width * height * 3)
    for left, top, right, bottom, color in bars:
        for y in range(max(0, top), min(height, bottom)):
            for x in range(max(0, left), min(width, right)):
                index = (y * width + x) * 3
                pixels[index:index + 3] = bytes(color)
    raw = b"".join(b"\0" + pixels[row * width * 3:(row + 1) * width * 3] for row in range(height))
    def chunk(kind: bytes, value: bytes) -> bytes:
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def _pdf(title: str, labels: list[str], values: list[float]) -> bytes:
    commands = ["BT /F1 14 Tf 50 550 Td", f"({title.replace('(', '[').replace(')', ']')}) Tj", "ET"]
    maximum = max(values, default=1.0) or 1.0
    for index, (label, value) in enumerate(zip(labels, values)):
        y = 510 - index * 28
        width = 420 * value / maximum
        commands.extend([f"0.1 0.45 0.7 rg 130 {y} {width:.3f} 16 re f", f"BT /F1 8 Tf 45 {y + 4} Td ({label[:16]}) Tj ET"])
    stream = "\n".join(commands).encode("ascii", "replace")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 612] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    result = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, value in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{number} 0 obj\n".encode() + value + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode())
    result.extend(f"trailer << /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(result)


def make_figure(
    summary_path: Path,
    output_directory: Path,
    figure_id: str,
    title: str,
    study_ids: set[str],
    metric: str = "median_wall_time_ns",
) -> dict[str, Any]:
    rows = [json.loads(line) for line in summary_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    selected = [row for row in rows if row.get("study_id") in study_ids and isinstance(row.get(metric), (int, float))]
    selected.sort(key=lambda row: (row.get("dataset_id", ""), row.get("algorithm_id", "")))
    labels = [f"{row.get('dataset_id')}/{row.get('algorithm_id')}" for row in selected]
    values = [float(row[metric]) for row in selected]
    maximum = max(values, default=1.0) or 1.0
    width, height = 900, max(360, 90 + 34 * len(values))
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">', '<rect width="100%" height="100%" fill="white"/>', f'<text x="40" y="36" font-family="sans-serif" font-size="20">{title}</text>']
    png_bars = []
    for index, (label, value) in enumerate(zip(labels, values)):
        y = 65 + 32 * index
        bar_width = int(650 * value / maximum)
        color = COLORS[index % len(COLORS)]
        svg.extend([f'<text x="35" y="{y + 14}" font-family="sans-serif" font-size="11">{label}</text>', f'<rect x="190" y="{y}" width="{bar_width}" height="18" fill="{color}"/>'])
        rgb = tuple(int(color[position:position + 2], 16) for position in (1, 3, 5))
        png_bars.append((190, y, 190 + bar_width, y + 18, rgb))
    if not selected:
        svg.append('<text x="40" y="90" font-family="sans-serif" font-size="14">No applicable validated rows</text>')
    svg.append("</svg>")
    output_directory.mkdir(parents=True, exist_ok=True)
    stem = output_directory / figure_id.lower()
    atomic_write_text(stem.with_suffix(".svg"), "\n".join(svg) + "\n")
    stem.with_suffix(".png").write_bytes(_png(width, height, png_bars))
    stem.with_suffix(".pdf").write_bytes(_pdf(title, labels, values))
    sidecar = {
        "schema_version": 1,
        "figure_id": figure_id,
        "title": title,
        "input": summary_path.as_posix(),
        "input_checksum": sha256_file(summary_path),
        "filters": {"study_ids": sorted(study_ids)},
        "metric": metric,
        "metric_definition": "Per-query trial median, then distribution across query units",
        "row_count": len(selected),
    }
    atomic_write_json(stem.with_suffix(".json"), sidecar)
    return sidecar
