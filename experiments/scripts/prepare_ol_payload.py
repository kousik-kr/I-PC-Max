#!/usr/bin/env python3
"""Finalize the supplied OL canonical payload for the PACE contract.

The OL graph has already been parsed by the repository's existing OL loader.
This utility only rewrites that canonical payload: it scales the supplied edge
weights into minutes under ``declared_centisecond_normalization-v1`` and
extends the existing first-day functions to the declared 10080-minute horizon.
It does not parse DIMACS and does not create another graph representation.
"""
from __future__ import annotations

import argparse
import csv
from decimal import Decimal, ROUND_HALF_UP
import gzip
import json
from pathlib import Path
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text
from experiments.scripts.common.hashing import (
    dataset_checksum,
    temporal_attribute_checksum,
)

SCALE = Decimal(6000)
SUPPORT_END = Decimal(10080)
SERIAL_QUANTUM = Decimal("0.000000001")


def number(value: Decimal | str | float | int) -> str:
    result = Decimal(str(value)).quantize(SERIAL_QUANTUM, rounding=ROUND_HALF_UP)
    text = format(result.normalize(), "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def read_gzip_csv(path: Path) -> list[dict[str, str]]:
    with gzip.open(path, "rt", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def write_gzip_csv(path: Path, fieldnames: list[str], rows: list[dict[str, str]]) -> None:
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            import io
            with io.TextIOWrapper(compressed, encoding="utf-8", newline="") as text:
                writer = csv.DictWriter(text, fieldnames=fieldnames, lineterminator="\n")
                writer.writeheader()
                writer.writerows(rows)
    temporary.replace(path)


def read_jsonl(path: Path) -> list[dict]:
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            for row in rows:
                compressed.write(
                    (json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
                )
    temporary.replace(path)


def prepare(directory: Path) -> dict:
    directory = directory.resolve()
    required = [
        "nodes.csv.gz", "edges_static.csv.gz", "travel_time_functions.jsonl.gz",
        "score_functions.jsonl.gz", "manifest.json",
    ]
    missing = [name for name in required if not (directory / name).is_file()]
    if missing:
        raise FileNotFoundError("OL payload is missing: " + ", ".join(missing))
    prior_manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    already_normalized = (
        prior_manifest.get("conversion_contract", {}).get("contract_id")
        == "declared_centisecond_normalization-v1"
        and int(prior_manifest.get("temporal_support", {}).get("end", 0)) >= 10080
    )

    nodes = read_gzip_csv(directory / "nodes.csv.gz")
    node_ids = [int(row["node_id"]) for row in nodes]
    remap_nodes = any(node_id <= 0 for node_id in node_ids)
    node_mapping = (
        {old: index + 1 for index, old in enumerate(sorted(node_ids))}
        if remap_nodes else {node_id: node_id for node_id in node_ids}
    )
    for row in nodes:
        # GeneratedGraphLoader's canonical node schema stores integral
        # coordinates.  OL's supplied loader emitted decimal coordinates;
        # deterministic nearest-integer conversion preserves topology and is
        # used only by the spatial metadata, never by travel-time weights.
        row["x"] = str(Decimal(row["x"]).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
        row["y"] = str(Decimal(row["y"]).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
        row["node_id"] = str(node_mapping[int(row["node_id"])])
    write_gzip_csv(directory / "nodes.csv.gz", ["node_id", "x", "y"], nodes)

    edges = read_gzip_csv(directory / "edges_static.csv.gz")
    expected_ids = list(range(len(edges)))
    actual_ids = [int(row["arc_id"]) for row in edges]
    if actual_ids != expected_ids:
        raise ValueError("OL directed arc IDs must be unique and consecutive from zero")
    endpoints = {(int(row["u"]), int(row["v"])) for row in edges}
    if len(endpoints) != len(edges):
        # Parallel directed arcs are valid; this check is intentionally only an
        # ID check.  Keeping it explicit prevents accidental ID de-duplication.
        pass
    for row in edges:
        if remap_nodes:
            row["u"] = str(node_mapping[int(row["u"])])
            row["v"] = str(node_mapping[int(row["v"])])
        base = Decimal(row["base_travel_time"])
        if base <= 0:
            raise ValueError(f"OL arc {row['arc_id']} has non-positive base travel time")
        row["distance"] = str(
            Decimal(row["distance"]).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
        )
        row["base_travel_time"] = number(base if already_normalized else base / SCALE)
    write_gzip_csv(
        directory / "edges_static.csv.gz",
        ["arc_id", "u", "v", "distance", "base_travel_time"],
        edges,
    )

    travel = read_jsonl(directory / "travel_time_functions.jsonl.gz")
    if len(travel) != len(edges):
        raise ValueError("OL travel-time record count does not match static arcs")
    for index, row in enumerate(travel):
        if int(row.get("arc_id", -1)) != index:
            raise ValueError(f"OL travel-time arc IDs are not ordered at {index}")
        if remap_nodes:
            row["u"] = node_mapping[int(row["u"])]
            row["v"] = node_mapping[int(row["v"])]
        row["base_travel_time"] = float(number(
            Decimal(str(row["base_travel_time"]))
            if already_normalized
            else Decimal(str(row["base_travel_time"])) / SCALE
        ))
        points = row.get("travel_time_breakpoints")
        if not isinstance(points, list) or not points:
            raise ValueError(f"OL arc {index} has no travel-time breakpoints")
        if Decimal(str(points[-1][0])) == Decimal(10080):
            if not already_normalized:
                raise ValueError(f"OL arc {index} has an unexpected 10080-minute endpoint")
            row["travel_time_breakpoints"] = points
        elif Decimal(str(points[-1][0])) == Decimal(1440):
            row["travel_time_breakpoints"] = [
                [point[0], float(number(Decimal(str(point[1])) if already_normalized else Decimal(str(point[1])) / SCALE))]
                for point in points
            ] + [[10080, row["base_travel_time"]]]
        else:
            raise ValueError(f"OL arc {index} must have a first-day endpoint at minute 1440")
    write_jsonl(directory / "travel_time_functions.jsonl.gz", travel)

    score = read_jsonl(directory / "score_functions.jsonl.gz")
    seen: set[int] = set()
    for row in score:
        arc_id = int(row.get("arc_id", -1))
        if arc_id < 0 or arc_id >= len(edges) or arc_id in seen:
            raise ValueError(f"invalid or duplicate OL score arc ID: {arc_id}")
        seen.add(arc_id)
        if remap_nodes:
            row["u"] = node_mapping[int(row["u"])]
            row["v"] = node_mapping[int(row["v"])]
        intervals = row.get("score_intervals")
        if not isinstance(intervals, list) or not intervals:
            raise ValueError(f"OL score arc {arc_id} has no score intervals")
        if Decimal(str(intervals[-1][1])) == Decimal(10080):
            if not already_normalized:
                raise ValueError(f"OL score arc {arc_id} has an unexpected 10080-minute endpoint")
        elif Decimal(str(intervals[-1][1])) == Decimal(1440):
            row["score_intervals"] = intervals + [[1440, 10080, 0]]
        else:
            raise ValueError(f"OL score arc {arc_id} must have a first-day endpoint at minute 1440")
    write_jsonl(directory / "score_functions.jsonl.gz", score)

    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    manifest.update({
        "schema_version": 3,
        "conversion_contract": {
            "contract_id": "declared_centisecond_normalization-v1",
            "formula": "minutes = DIMACS_weight * 1 / 6000",
            "input_weight_unit": "declared_centisecond_normalization",
            "internal_time_unit": "minutes",
            "minutes_per_dimacs_weight": {"numerator": 1, "denominator": 6000},
            "provenance": "author release decision for this paper artifact; supplied OL canonical edge weights are treated as declared DIMACS weights",
            "rounding_policy": "deterministic decimal serialization with up to 9 digits after decimal",
        },
        "temporal_support": {
            "start": 0,
            "first_day_end": 1440,
            "end": 10080,
            "extension_policy": "preserve first-day behavior over [0,1440]; travel time flat at converted base travel time through 10080; score zero through 10080; no extrapolation",
        },
        "num_nodes": len(nodes),
        "num_arcs": len(edges),
        "node_id_policy": "positive-stable-remap-from-supplied-OL-IDs" if remap_nodes else "supplied-positive-IDs",
        "dataset_checksum": dataset_checksum(directory),
        "temporal_attribute_checksum": temporal_attribute_checksum(directory),
        "travel_time_output": {
            "file": "travel_time_functions.jsonl.gz",
            "format": "gzip-compressed JSON Lines; one object per directed arc; breakpoints support [0,10080] minutes",
            "temporal_support": {"start": 0, "end": 10080},
        },
        "score_output": {
            "file": "score_functions.jsonl.gz",
            "format": "gzip-compressed JSON Lines; score intervals are contiguous on [0,10080] and zero after minute 1440",
        },
    })
    atomic_write_json(directory / "manifest.json", manifest)
    atomic_write_text(directory / "README_generated.md", """# OL canonical PACE payload\n\nThe supplied Oldenburg graph is normalized under `declared_centisecond_normalization-v1` (`minutes = DIMACS_weight / 6000`). Travel-time and score functions are supported on [0,10080] minutes without wrapping or extrapolation.\n""")
    # Manifest checksums intentionally exclude manifest/README and therefore are
    # valid after the metadata write above.
    return {
        "dataset_id": "OL",
        "nodes": manifest["num_nodes"],
        "directed_arcs": manifest["num_arcs"],
        "score_edges": len(score),
        "dataset_checksum": manifest["dataset_checksum"],
        "temporal_attribute_checksum": manifest["temporal_attribute_checksum"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("data/input/OL"))
    args = parser.parse_args()
    print(json.dumps(prepare(args.directory), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
