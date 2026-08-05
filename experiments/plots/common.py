"""Small deterministic SVG/PNG/PDF renderer for validated aggregate data."""
from __future__ import annotations

import json
import html
import math
from pathlib import Path
import struct
import zlib
from typing import Any

from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text
from experiments.scripts.common.hashing import sha256_file


COLORS = ("#0072B2", "#D55E00", "#009E73", "#CC79A7", "#E69F00", "#56B4E9")
PAPER_LABELS = {
    "pace-b": "PACE-B",
    "pace-x": "PACE-X",
    "iscope": "iSCOPE",
    "allfp": "allFP",
    "interval-best": "interval-best (legacy)",
    "rpq": "RPQ (historical)",
}


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


def _pdf(
    title: str,
    labels: list[str],
    values: list[float],
    uncertainty: list[tuple[float, float] | None],
) -> bytes:
    commands = [
        "BT /F1 14 Tf 50 550 Td",
        f"({title.replace('(', '[').replace(')', ']')}) Tj",
        "ET",
        "BT /F1 7 Tf 50 535 Td (Whiskers show IQR or binomial 95% intervals where estimable.) Tj ET",
    ]
    maximum = max(values, default=1.0) or 1.0
    for index, (label, value, bounds) in enumerate(zip(labels, values, uncertainty)):
        y = 510 - index * 28
        width = 420 * value / maximum
        commands.extend([f"0.1 0.45 0.7 rg 130 {y} {width:.3f} 16 re f", f"BT /F1 8 Tf 45 {y + 4} Td ({label[:16]}) Tj ET"])
        if bounds is not None:
            low, high = bounds
            x1 = 130 + 420 * low / maximum
            x2 = 130 + 420 * high / maximum
            commands.extend([
                "0 0 0 RG 1 w",
                f"{x1:.3f} {y + 8} m {x2:.3f} {y + 8} l S",
                f"{x1:.3f} {y + 4} m {x1:.3f} {y + 12} l S",
                f"{x2:.3f} {y + 4} m {x2:.3f} {y + 12} l S",
            ])
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
    metric: str | tuple[str, ...] = "median_wall_time_ns",
    *,
    log_scale: bool = False,
    sample_only: bool = False,
    intended_study_ids: set[str] | None = None,
) -> dict[str, Any]:
    rows = [json.loads(line) for line in summary_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    metrics = (metric,) if isinstance(metric, str) else metric
    selected = [row for row in rows if row.get("study_id") in study_ids]
    selected.sort(key=lambda row: (
        row.get("dataset_id", ""), row.get("algorithm_id", ""),
        row.get("variant_id", ""), row.get("axis_json", ""),
    ))
    entries: list[
        tuple[str, float, dict[str, Any], str, tuple[float, float] | None]
    ] = []
    maxima: dict[str, float] = {}
    for name in metrics:
        numeric = [float(row[name]) for row in selected if isinstance(row.get(name), (int, float))]
        transformed = [math.log10(1 + max(0, value)) if log_scale else max(0, value) for value in numeric]
        maxima[name] = max(transformed, default=1.0) or 1.0
    for row in selected:
        axis = row.get("axis_json", "{}")
        algorithm = row.get("algorithm_id")
        base = f"{row.get('dataset_id')}/{PAPER_LABELS.get(algorithm, algorithm)}"
        variant = row.get("variant_id")
        if row.get("study_id") != "T03" and variant and variant != algorithm:
            base += f"/{variant}"
        if axis != "{}":
            base += f"/{axis}"
        for name in metrics:
            raw = float(row[name]) if isinstance(row.get(name), (int, float)) else 0.0
            transformed = math.log10(1 + max(0, raw)) if log_scale else max(0, raw)
            try:
                bounds = json.loads(row.get("uncertainty_json", "{}")).get(name)
            except (TypeError, json.JSONDecodeError):
                bounds = None
            transformed_bounds = None
            if (
                isinstance(bounds, list)
                and len(bounds) == 2
                and all(isinstance(value, (int, float)) for value in bounds)
            ):
                low = float(bounds[0])
                high = float(bounds[1])
                transformed_bounds = (
                    math.log10(1 + max(0, low)) if log_scale else max(0, low),
                    math.log10(1 + max(0, high)) if log_scale else max(0, high),
                )
            entries.append((
                f"{base}/{name}", raw, row, name, transformed_bounds
            ))
    labels = [entry[0] for entry in entries]
    values = [entry[1] for entry in entries]
    width, height = 1100, max(360, 110 + 34 * len(entries))
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">', '<rect width="100%" height="100%" fill="white"/>', f'<text x="40" y="36" font-family="sans-serif" font-size="20">{html.escape(title)}</text>']
    svg.append(
        f'<text x="40" y="56" font-family="sans-serif" font-size="10">'
        f'validated rows={len(selected)}; scale={"log10(1+x)" if log_scale else "linear"}; '
        'zero bars retain failed/non-numeric rows</text>'
    )
    png_bars = []
    pdf_values: list[float] = []
    pdf_uncertainty: list[tuple[float, float] | None] = []
    plotted_uncertainty = 0
    for index, (label, raw_value, row, name, bounds) in enumerate(entries):
        y = 78 + 32 * index
        transformed = math.log10(1 + max(0, raw_value)) if log_scale else max(0, raw_value)
        bar_width = int(650 * transformed / maxima[name])
        normalized = transformed / maxima[name]
        pdf_values.append(normalized)
        color = COLORS[index % len(COLORS)]
        safe_label = html.escape(label[:70])
        svg.extend([
            f'<text x="20" y="{y + 14}" font-family="sans-serif" font-size="9">{safe_label}</text>',
            f'<rect x="430" y="{y}" width="{bar_width}" height="18" fill="{color}"/>',
            f'<text x="{min(1080, 438 + bar_width)}" y="{y + 14}" font-family="sans-serif" font-size="9">{raw_value:.5g}</text>',
        ])
        rgb = tuple(int(color[position:position + 2], 16) for position in (1, 3, 5))
        png_bars.append((430, y, 430 + bar_width, y + 18, rgb))
        if bounds is not None:
            low_t, high_t = bounds
            low_n = low_t / maxima[name]
            high_n = high_t / maxima[name]
            pdf_uncertainty.append((low_n, high_n))
            plotted_uncertainty += 1
            x1 = 430 + int(650 * low_n)
            x2 = 430 + int(650 * high_n)
            svg.extend([
                f'<line x1="{x1}" y1="{y + 9}" x2="{x2}" y2="{y + 9}" stroke="black" stroke-width="2"/>',
                f'<line x1="{x1}" y1="{y + 4}" x2="{x1}" y2="{y + 14}" stroke="black"/>',
                f'<line x1="{x2}" y1="{y + 4}" x2="{x2}" y2="{y + 14}" stroke="black"/>',
            ])
            png_bars.extend([
                (x1, y + 8, x2 + 1, y + 10, (0, 0, 0)),
                (x1, y + 4, x1 + 1, y + 15, (0, 0, 0)),
                (x2, y + 4, x2 + 1, y + 15, (0, 0, 0)),
            ])
        else:
            pdf_uncertainty.append(None)
    if not entries:
        svg.append('<text x="40" y="90" font-family="sans-serif" font-size="14">No applicable validated rows</text>')
    svg.append("</svg>")
    output_directory.mkdir(parents=True, exist_ok=True)
    stem = output_directory / figure_id.lower()
    atomic_write_text(stem.with_suffix(".svg"), "\n".join(svg) + "\n")
    stem.with_suffix(".png").write_bytes(_png(width, height, png_bars))
    stem.with_suffix(".pdf").write_bytes(
        _pdf(title, labels, pdf_values, pdf_uncertainty)
    )
    sidecar = {
        "schema_version": 1,
        "figure_id": figure_id,
        "title": title,
        "input": summary_path.as_posix(),
        "input_checksum": sha256_file(summary_path),
        "filters": {"study_ids": sorted(study_ids)},
        "sample_only": sample_only,
        "intended_study_ids": sorted(intended_study_ids or study_ids),
        "metrics": list(metrics),
        "metric_definition": "Per-query trial median, then distribution across query units",
        "row_count": len(selected),
        "plotted_entry_count": len(entries),
        "preserves_failure_rows": True,
        "uncertainty": "per-query IQR or binomial 95% interval; derived parallel intervals use runtime IQR bounds",
        "entries_with_uncertainty": plotted_uncertainty,
        "scale": "log10(1+x)" if log_scale else "linear",
        "completion_and_cap_fields_available": [
            "completion_rate", "timeout_rate", "oom_rate", "cap_trigger_rate",
        ],
    }
    atomic_write_json(stem.with_suffix(".json"), sidecar)
    written = json.loads(stem.with_suffix(".json").read_text(encoding="utf-8"))
    if written["row_count"] != len(selected) or written["plotted_entry_count"] != len(entries):
        raise AssertionError(f"{figure_id} plotted-row sidecar assertion failed")
    return sidecar
