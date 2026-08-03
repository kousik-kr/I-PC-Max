#!/usr/bin/env python3
"""Generate paper graph assets with the declared DIMACS-weight normalization."""
from __future__ import annotations

import argparse
import csv
from decimal import Decimal, ROUND_HALF_UP, getcontext
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import random
import shutil
import sys
from itertools import zip_longest
from typing import Any, Iterable, Iterator

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text
from experiments.scripts.common.config import load_design, load_document, repo_path
from experiments.scripts.common.hashing import (
    dataset_checksum,
    graph_checksum,
    sha256_file,
    sha256_json,
    temporal_attribute_checksum,
)


REQUIRED_GRAPH_FILES = (
    "edges_static.csv.gz",
    "nodes.csv.gz",
    "manifest.json",
    "score_functions.jsonl.gz",
    "travel_time_functions.jsonl.gz",
)
DAY_MINUTES = 1440
MAX_SUPPORT = 10080

getcontext().prec = 40
SERIAL_SCALE = Decimal("0.000000001")


def gzip_text_writer(path: Path):
    raw = path.open("wb")
    gz = gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0)
    text = io.TextIOWrapper(gz, encoding="utf-8", newline="")
    return _ClosingText(text, gz, raw)


class _ClosingText:
    def __init__(self, text, gz, raw):
        self.text = text
        self.gz = gz
        self.raw = raw

    def __enter__(self):
        return self.text

    def __exit__(self, exc_type, exc, tb):
        try:
            self.text.close()
        finally:
            self.raw.close()


def open_maybe_gzip(path: Path):
    if path.name.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8")
    return path.open("rt", encoding="utf-8")


def decimal_text(value: Decimal | int | float | str) -> str:
    if isinstance(value, Decimal):
        decimal = value
    else:
        decimal = Decimal(str(value))
    rounded = decimal.quantize(SERIAL_SCALE, rounding=ROUND_HALF_UP)
    text = format(rounded.normalize(), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def dimacs_to_minutes(weight: int, numerator: int, denominator: int) -> Decimal:
    return Decimal(weight * numerator) / Decimal(denominator)


def resolve_config_path(path_text: str) -> Path:
    path = Path(path_text)
    return path if path.is_absolute() else repo_path(path)


def temporary_for(path: Path) -> Path:
    return path.with_name(path.name + ".tmp")


def replace_file(temp: Path, destination: Path) -> None:
    temp.replace(destination)


def parse_problem_counts(path: Path) -> tuple[int, int]:
    with open_maybe_gzip(path) as handle:
        for line_number, line in enumerate(handle, 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("c"):
                continue
            parts = stripped.split()
            if parts[0] == "p" and len(parts) == 4 and parts[1] == "sp":
                return int(parts[2]), int(parts[3])
            raise ValueError(f"{path}:{line_number}: expected DIMACS problem line")
    raise ValueError(f"{path}: missing DIMACS problem line")


def iter_dimacs_arcs(path: Path):
    expected = None
    count = 0
    with open_maybe_gzip(path) as handle:
        for line_number, line in enumerate(handle, 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("c"):
                continue
            parts = stripped.split()
            if parts[0] == "p":
                if len(parts) != 4 or parts[1] != "sp":
                    raise ValueError(f"{path}:{line_number}: invalid problem line")
                expected = int(parts[3])
                continue
            if parts[0] != "a" or len(parts) != 4:
                raise ValueError(f"{path}:{line_number}: invalid arc line")
            yield count, int(parts[1]), int(parts[2]), int(parts[3])
            count += 1
    if expected is None:
        raise ValueError(f"{path}: missing problem line")
    if count != expected:
        raise ValueError(f"{path}: parsed {count} arcs, expected {expected}")


def parse_coordinate_count(path: Path) -> int:
    expected = None
    parsed = 0
    with open_maybe_gzip(path) as handle:
        for line_number, line in enumerate(handle, 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("c"):
                continue
            parts = stripped.split()
            if parts[0] == "p":
                if len(parts) != 5 or parts[1:4] != ["aux", "sp", "co"]:
                    raise ValueError(f"{path}:{line_number}: invalid coordinate problem line")
                expected = int(parts[4])
            elif parts[0] == "v":
                parsed += 1
            else:
                raise ValueError(f"{path}:{line_number}: invalid coordinate row")
    if expected is None:
        raise ValueError(f"{path}: missing coordinate problem line")
    if parsed != expected:
        raise ValueError(f"{path}: parsed {parsed} coordinates, expected {expected}")
    return parsed


def write_edges_from_raw(
    distance_path: Path,
    travel_path: Path,
    destination: Path,
    numerator: int,
    denominator: int,
) -> int:
    temp = temporary_for(destination)
    count = 0
    with gzip_text_writer(temp) as handle:
        writer = csv.writer(handle)
        writer.writerow(["arc_id", "u", "v", "distance", "base_travel_time"])
        for travel, distance in zip(iter_dimacs_arcs(travel_path), iter_dimacs_arcs(distance_path)):
            arc_id, u, v, weight = travel
            d_arc_id, d_u, d_v, distance_weight = distance
            if (arc_id, u, v) != (d_arc_id, d_u, d_v):
                raise ValueError(
                    f"raw graph mismatch at arc {arc_id}: "
                    f"travel={u}->{v}, distance={d_u}->{d_v}"
                )
            writer.writerow([
                arc_id,
                u,
                v,
                distance_weight,
                decimal_text(dimacs_to_minutes(weight, numerator, denominator)),
            ])
            count += 1
    replace_file(temp, destination)
    return count


def breakpoints_json(points: list[list[Any]]) -> str:
    pieces = []
    for minute, value in points:
        pieces.append("[%d,%s]" % (int(minute), value))
    return "[" + ",".join(pieces) + "]"


def make_rng(seed: int):
    try:
        import numpy as np  # type: ignore
    except ImportError:
        import random

        return random.Random(seed)
    return np.random.default_rng(seed)


def rng_uniform(rng, low: float, high: float) -> float:
    return float(rng.uniform(low, high))


def rng_int_inclusive(rng, low: int, high: int) -> int:
    if hasattr(rng, "integers"):
        return int(rng.integers(low, high + 1))
    return int(rng.randint(low, high))


def rng_peak_minute(rng, start: int, end: int) -> int:
    if hasattr(rng, "integers"):
        return int(rng.integers(start + 1, end))
    return int(rng.randrange(start + 1, end))


def rng_sample_mask(rng, count: int, selected_count: int) -> set[int]:
    if selected_count == 0:
        return set()
    if hasattr(rng, "choice"):
        return {int(value) for value in rng.choice(count, size=selected_count, replace=False)}
    return set(rng.sample(range(count), selected_count))


def edge_rows(edge_path: Path):
    with gzip.open(edge_path, "rt", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            yield {
                "arc_id": int(row["arc_id"]),
                "u": int(row["u"]),
                "v": int(row["v"]),
                "distance": int(row["distance"]),
                "base_travel_time": row["base_travel_time"],
            }


def copy_required_files(source: Path, destination: Path, files: Iterable[str] = REQUIRED_GRAPH_FILES) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    for name in files:
        shutil.copy2(source / name, destination / name)


def generate_travel_for_edges(edge_path: Path, destination: Path, settings: dict[str, Any], seed: int) -> int:
    temp = temporary_for(destination)
    count = 0
    rush_windows = [
        {
            "start": int(settings["morning_rush"][0]),
            "end": int(settings["morning_rush"][1]),
            "multiplier_min": float(settings["morning_multiplier_min"]),
            "multiplier_max": float(settings["morning_multiplier_max"]),
        },
        {
            "start": int(settings["evening_rush"][0]),
            "end": int(settings["evening_rush"][1]),
            "multiplier_min": float(settings["evening_multiplier_min"]),
            "multiplier_max": float(settings["evening_multiplier_max"]),
        },
    ]
    with gzip_text_writer(temp) as writer:
        for edge in edge_rows(edge_path):
            rng = random.Random(mix64(seed ^ edge["arc_id"] ^ 0x54524156454C))
            base = Decimal(edge["base_travel_time"])
            base_text = decimal_text(base)
            points: list[list[Any]] = [[0, base_text]]
            base_float = float(base)
            for window in rush_windows:
                start = window["start"]
                end = window["end"]
                peak = rng_peak_minute(rng, start, end)
                multiplier = rng_uniform(rng, window["multiplier_min"], window["multiplier_max"])
                fifo_cap = base_float + min(peak - start, end - peak)
                peak_value = min(base_float * multiplier, fifo_cap)
                append_point(points, start, base_text)
                append_point(points, peak, decimal_text(Decimal(str(peak_value))))
                append_point(points, end, base_text)
            append_point(points, DAY_MINUTES, base_text)
            append_point(points, MAX_SUPPORT, base_text)
            writer.write(
                '{"arc_id":%d,"u":%d,"v":%d,"distance":%d,'
                '"base_travel_time":%s,"travel_time_breakpoints":%s}\n'
                % (
                    edge["arc_id"],
                    edge["u"],
                    edge["v"],
                    edge["distance"],
                    base_text,
                    breakpoints_json(points),
                )
            )
            count += 1
    replace_file(temp, destination)
    return count


def append_point(points: list[list[Any]], minute: int, value: str) -> None:
    if points and points[-1][0] == minute:
        points[-1] = [minute, value]
    else:
        points.append([minute, value])


def split_rush(start: int, end: int, pieces: int) -> list[int]:
    step = (end - start) // pieces
    return [start + step * index for index in range(pieces)] + [end]


def score_intervals(settings: dict[str, Any], rng, selected: bool) -> list[list[int]]:
    if not selected:
        return [[0, DAY_MINUTES, 0], [DAY_MINUTES, MAX_SUPPORT, 0]]
    intervals: list[list[int]] = []
    current = 0
    windows = [settings["morning_rush"], settings["evening_rush"]]
    pieces = int(settings["score_intervals_per_rush"])
    for start, end in windows:
        start = int(start)
        end = int(end)
        if current < start:
            intervals.append([current, start, 0])
        boundaries = split_rush(start, end, pieces)
        for index in range(pieces):
            score = rng_int_inclusive(rng, int(settings["score_min"]), int(settings["score_max"]))
            intervals.append([boundaries[index], boundaries[index + 1], score])
        current = end
    if current < DAY_MINUTES:
        intervals.append([current, DAY_MINUTES, 0])
    intervals.append([DAY_MINUTES, MAX_SUPPORT, 0])
    return intervals


def mix64(value: int) -> int:
    """Stable SplitMix64 finalizer used only for generator-local derivation."""
    mask = (1 << 64) - 1
    value &= mask
    value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & mask
    value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & mask
    return (value ^ (value >> 31)) & mask


def score_permutation_parameters(edge_count: int, seed: int) -> tuple[int, int]:
    """Return an affine permutation that makes all requested densities nested."""
    if edge_count <= 0:
        raise ValueError("edge count must be positive")
    multiplier = int(mix64(seed ^ 0x53434F5245) % edge_count)
    if multiplier == 0:
        multiplier = 1
    while math.gcd(multiplier, edge_count) != 1:
        multiplier += 1
        if multiplier == edge_count:
            multiplier = 1
    offset = int(mix64(seed ^ 0x44454E53495459) % edge_count)
    return multiplier, offset


def score_edge_selected(
    arc_id: int,
    selected_count: int,
    edge_count: int,
    multiplier: int,
    offset: int,
) -> bool:
    return ((multiplier * arc_id + offset) % edge_count) < selected_count


def generate_score_for_edges(
    edge_path: Path,
    destination: Path,
    settings: dict[str, Any],
    seed: int,
    density: float,
    edge_count: int,
) -> int:
    selected_count = math.floor(density * edge_count)
    multiplier, offset = score_permutation_parameters(edge_count, seed)
    temp = temporary_for(destination)
    written = 0
    with gzip_text_writer(temp) as writer:
        for edge in edge_rows(edge_path):
            is_selected = score_edge_selected(
                edge["arc_id"],
                selected_count,
                edge_count,
                multiplier,
                offset,
            )
            if not is_selected and not settings.get("materialize_zero_score_edges", False):
                continue
            rng = random.Random(mix64(seed ^ edge["arc_id"] ^ 0x53434F5245))
            writer.write(
                '{"arc_id":%d,"u":%d,"v":%d,"selected_for_score":%s,'
                '"score_intervals":%s}\n'
                % (
                    edge["arc_id"],
                    edge["u"],
                    edge["v"],
                    "true" if is_selected else "false",
                    json.dumps(score_intervals(settings, rng, is_selected), separators=(",", ":")),
                )
            )
            written += 1
    replace_file(temp, destination)
    return written


def manifest(
    dataset: str,
    output_directory: Path,
    input_paths: dict[str, Path],
    input_hashes: dict[str, str],
    node_count: int,
    edge_count: int,
    seed: int,
    score_density: float,
    selected_score_count: int,
    generation_config: dict[str, Any],
    generation_config_hash: str,
    variant: dict[str, Any] | None = None,
) -> dict[str, Any]:
    settings = generation_config["defaults"]
    conversion = generation_config["conversion_contract"]
    support = generation_config["temporal_support"]
    result = {
        "schema_version": 3,
        "generator": {
            "name": "experiments/scripts/generate_dataset_assets.py",
            "version": generation_config["generator_version"],
            "generation_config_sha256": generation_config_hash,
        },
        "conversion_contract": conversion,
        "dataset_checksum": dataset_checksum(output_directory),
        "temporal_attribute_checksum": temporal_attribute_checksum(
            output_directory
        ),
        "input_files": {
            key: path.as_posix()
            for key, path in input_paths.items()
        },
        "input_sha256": input_hashes,
        "output_files": [
            "nodes.csv.gz",
            "edges_static.csv.gz",
            "travel_time_functions.jsonl.gz",
            "score_functions.jsonl.gz",
            "manifest.json",
            "README_generated.md",
        ],
        "num_nodes": node_count,
        "num_arcs": edge_count,
        "seed": seed,
        "rush_windows": {
            "morning": settings["morning_rush"],
            "evening": settings["evening_rush"],
        },
        "multiplier_ranges": {
            "morning": [settings["morning_multiplier_min"], settings["morning_multiplier_max"]],
            "evening": [settings["evening_multiplier_min"], settings["evening_multiplier_max"]],
        },
        "temporal_support": {
            "start": support["start"],
            "first_day_end": support["first_day_end"],
            "end": support["end"],
            "extension_policy": support["extension_policy"],
        },
        "travel_time_output": {
            "file": "travel_time_functions.jsonl.gz",
            "format": (
                "gzip-compressed JSON Lines; one object per arc; "
                "travel_time_breakpoints contains [minute, travel_time] points from 0 "
                "through 10080; minutes use declared DIMACS_weight/6000 normalization; "
                "values from 1440 through 10080 equal converted base_travel_time"
            ),
            "integer_travel_times": settings["integer_travel_times"],
            "travel_time_decimals": settings["travel_time_decimals"],
            "temporal_support": {
                "start": support["start"],
                "end": support["end"],
            },
        },
        "score_edge_fraction": score_density,
        "score_min": settings["score_min"],
        "score_max": settings["score_max"],
        "selected_score_edge_count": selected_score_count,
        "score_intervals_per_rush": settings["score_intervals_per_rush"],
        "materialize_zero_score_edges": settings["materialize_zero_score_edges"],
        "unlisted_edges_have_score_zero": not settings["materialize_zero_score_edges"],
        "score_output": {
            "file": "score_functions.jsonl.gz",
            "format": (
                "gzip-compressed JSON Lines; each object contains an arc_id and "
                "piecewise-constant score_intervals as [start_minute, end_minute, score]; "
                "scores are zero from 1440 through 10080"
            ),
        },
    }
    if variant:
        result["variant"] = variant
    return result


def write_readme(path: Path, data: dict[str, Any]) -> None:
    text = f"""# Generated Time-Dependent Graph

Dataset: {data.get('variant', {}).get('dataset_id', 'base')}

Nodes: {data['num_nodes']}
Arcs: {data['num_arcs']}
Seed: {data['seed']}
Score edge fraction: {data['score_edge_fraction']}
Temporal support: [{data['temporal_support']['start']},{data['temporal_support']['end']}]

Time normalization:

`{data['conversion_contract']['formula']}`

This is an author release decision for this paper artifact, not an official
DIMACS physical-unit claim.
"""
    atomic_write_text(path, text)


def is_converted(directory: Path, config: dict[str, Any]) -> bool:
    manifest_path = directory / "manifest.json"
    if not manifest_path.is_file():
        return False
    try:
        value = load_document(manifest_path)
    except (OSError, ValueError, json.JSONDecodeError):
        return False
    return (
        value.get("conversion_contract", {}).get("contract_id")
        == config["conversion_contract"]["contract_id"]
        and int(value.get("temporal_support", {}).get("end", 0)) >= MAX_SUPPORT
    )


def raw_paths(config: dict[str, Any], dataset: str) -> dict[str, Path]:
    return {
        key: resolve_config_path(value)
        for key, value in config["datasets"][dataset].items()
    }


def raw_hashes(paths: dict[str, Path]) -> dict[str, str]:
    return {key: sha256_file(path) for key, path in paths.items()}


def selected_score_count(score_path: Path) -> int:
    count = 0
    with gzip.open(score_path, "rt", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                count += 1
    return count


def iter_jsonl(path: Path) -> Iterator[dict[str, Any]]:
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as failure:
                raise ValueError(f"{path}:{line_number}: {failure}") from failure
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: expected JSON object")
            yield value


def count_nodes_file(path: Path) -> int:
    count = 0
    previous = None
    with gzip.open(path, "rt", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != ["node_id", "x", "y"]:
            raise ValueError(f"{path}: invalid nodes header")
        for row in reader:
            node_id = int(row["node_id"])
            if node_id <= 0 or (previous is not None and node_id <= previous):
                raise ValueError(
                    f"{path}: node IDs must be unique, positive, and increasing; "
                    f"found {node_id} after {previous}"
                )
            previous = node_id
            count += 1
    return count


def validate_conversion_against_raw(
    edge_path: Path,
    raw_travel_path: Path,
    numerator: int,
    denominator: int,
) -> int:
    count = 0
    for edge, raw in zip_longest(
        edge_rows(edge_path),
        iter_dimacs_arcs(raw_travel_path),
    ):
        if edge is None or raw is None:
            raise ValueError(
                f"{edge_path}: static edge count differs from {raw_travel_path}"
            )
        arc_id, u, v, weight = raw
        if (edge["arc_id"], edge["u"], edge["v"]) != (arc_id, u, v):
            raise ValueError(
                f"{edge_path}: raw endpoint/ID mismatch at arc {arc_id}"
            )
        expected = decimal_text(
            dimacs_to_minutes(weight, numerator, denominator)
        )
        if decimal_text(edge["base_travel_time"]) != expected:
            raise ValueError(
                f"{edge_path}: arc {arc_id} violates "
                f"declared_centisecond_normalization-v1: "
                f"{edge['base_travel_time']} != {expected}"
            )
        count += 1
    return count


def validate_travel_payload(
    edge_path: Path,
    travel_path: Path,
    support_end: int,
) -> tuple[int, int]:
    count = 0
    fifo_edges = 0
    for edge, record in zip_longest(
        edge_rows(edge_path),
        iter_jsonl(travel_path),
    ):
        if edge is None or record is None:
            raise ValueError(
                f"{travel_path}: travel-function count differs from static edges"
            )
        arc_id = int(record.get("arc_id", -1))
        if arc_id != count or arc_id != edge["arc_id"]:
            raise ValueError(
                f"{travel_path}: expected unique directed arc_id {count}; "
                f"found {arc_id}"
            )
        if (int(record.get("u", -1)), int(record.get("v", -1))) != (
            edge["u"],
            edge["v"],
        ):
            raise ValueError(
                f"{travel_path}: endpoint mismatch for arc_id {arc_id}"
            )
        if decimal_text(record.get("base_travel_time")) != decimal_text(
            edge["base_travel_time"]
        ):
            raise ValueError(
                f"{travel_path}: base travel-time mismatch for arc_id {arc_id}"
            )
        points = record.get("travel_time_breakpoints")
        if not isinstance(points, list) or len(points) < 2:
            raise ValueError(
                f"{travel_path}: arc_id {arc_id} has invalid breakpoints"
            )
        previous_minute = None
        previous_arrival = None
        for point in points:
            if not isinstance(point, list) or len(point) != 2:
                raise ValueError(
                    f"{travel_path}: arc_id {arc_id} has malformed breakpoint"
                )
            minute = Decimal(str(point[0]))
            travel = Decimal(str(point[1]))
            if travel <= 0:
                raise ValueError(
                    f"{travel_path}: arc_id {arc_id} has non-positive "
                    f"lower-bound travel time"
                )
            arrival = minute + travel
            if previous_minute is not None and minute <= previous_minute:
                raise ValueError(
                    f"{travel_path}: arc_id {arc_id} breakpoints are not increasing"
                )
            if previous_arrival is not None and arrival < previous_arrival:
                raise ValueError(
                    f"{travel_path}: arc_id {arc_id} is non-FIFO"
                )
            previous_minute = minute
            previous_arrival = arrival
        if Decimal(str(points[0][0])) != 0:
            raise ValueError(
                f"{travel_path}: arc_id {arc_id} support does not start at 0"
            )
        if Decimal(str(points[-1][0])) < support_end:
            raise ValueError(
                f"{travel_path}: arc_id {arc_id} support ends before {support_end}"
            )
        fifo_edges += 1
        count += 1
    return count, fifo_edges


def score_arc_ids(path: Path) -> Iterator[int]:
    for record in iter_jsonl(path):
        yield int(record.get("arc_id", -1))


def validate_score_payload(
    edge_path: Path,
    score_path: Path,
    edge_count: int,
    support_end: int,
    collect_ids: bool,
) -> tuple[int, set[int]]:
    count = 0
    previous_arc_id = -1
    selected_ids: set[int] = set()
    edges = iter(edge_rows(edge_path))
    edge = next(edges, None)
    for record in iter_jsonl(score_path):
        arc_id = int(record.get("arc_id", -1))
        if arc_id <= previous_arc_id or arc_id >= edge_count:
            raise ValueError(
                f"{score_path}: score arc IDs must be unique and increasing; "
                f"found {arc_id} after {previous_arc_id}"
            )
        while edge is not None and edge["arc_id"] < arc_id:
            edge = next(edges, None)
        if edge is None or edge["arc_id"] != arc_id:
            raise ValueError(f"{score_path}: unknown arc_id {arc_id}")
        if (int(record.get("u", -1)), int(record.get("v", -1))) != (
            edge["u"],
            edge["v"],
        ):
            raise ValueError(
                f"{score_path}: endpoint mismatch for arc_id {arc_id}"
            )
        if record.get("selected_for_score") is not True:
            raise ValueError(
                f"{score_path}: listed arc_id {arc_id} is not score-selected"
            )
        intervals = record.get("score_intervals")
        if not isinstance(intervals, list) or not intervals:
            raise ValueError(
                f"{score_path}: arc_id {arc_id} has no score intervals"
            )
        expected_start = Decimal("0")
        positive = False
        for interval in intervals:
            if not isinstance(interval, list) or len(interval) != 3:
                raise ValueError(
                    f"{score_path}: arc_id {arc_id} has malformed score interval"
                )
            start = Decimal(str(interval[0]))
            end = Decimal(str(interval[1]))
            score = int(interval[2])
            if start != expected_start or end < start or score < 0:
                raise ValueError(
                    f"{score_path}: arc_id {arc_id} has invalid/non-contiguous "
                    f"score support"
                )
            expected_start = end
            positive = positive or score > 0
        if expected_start < support_end:
            raise ValueError(
                f"{score_path}: arc_id {arc_id} support ends before {support_end}"
            )
        if not positive:
            raise ValueError(
                f"{score_path}: selected arc_id {arc_id} is never positive"
            )
        if collect_ids:
            selected_ids.add(arc_id)
        previous_arc_id = arc_id
        count += 1
    return count, selected_ids


def refresh_manifest_checksums(directory: Path) -> bool:
    path = directory / "manifest.json"
    data = load_document(path)
    structural = dataset_checksum(directory)
    temporal = temporal_attribute_checksum(directory)
    changed = (
        data.get("schema_version") != 3
        or data.get("dataset_checksum") != structural
        or data.get("temporal_attribute_checksum") != temporal
    )
    if changed:
        data["schema_version"] = 3
        data["dataset_checksum"] = structural
        data["temporal_attribute_checksum"] = temporal
        atomic_write_json(path, data)
    return changed


def validate_dataset_directory(
    directory: Path,
    expected_nodes: int | None,
    expected_edges: int | None,
    expected_seed: int,
    expected_density: float | None,
    expected_contract: str,
    support_end: int,
    raw_travel_path: Path | None,
    numerator: int,
    denominator: int,
    require_manifest_checksums: bool = True,
    collect_score_ids: bool = False,
) -> dict[str, Any]:
    data = load_document(directory / "manifest.json")
    actual_nodes = count_nodes_file(directory / "nodes.csv.gz")
    conversion_edges = (
        validate_conversion_against_raw(
            directory / "edges_static.csv.gz",
            raw_travel_path,
            numerator,
            denominator,
        )
        if raw_travel_path is not None
        else sum(1 for _ in edge_rows(directory / "edges_static.csv.gz"))
    )
    travel_edges, fifo_edges = validate_travel_payload(
        directory / "edges_static.csv.gz",
        directory / "travel_time_functions.jsonl.gz",
        support_end,
    )
    score_edges, selected_ids = validate_score_payload(
        directory / "edges_static.csv.gz",
        directory / "score_functions.jsonl.gz",
        conversion_edges,
        support_end,
        collect_score_ids,
    )
    structural = dataset_checksum(directory)
    temporal = temporal_attribute_checksum(directory)
    full = graph_checksum(directory, REQUIRED_GRAPH_FILES)
    errors: list[str] = []
    if expected_nodes is not None and (
        actual_nodes != expected_nodes or data.get("num_nodes") != expected_nodes
    ):
        errors.append(
            f"node count mismatch: payload={actual_nodes}, "
            f"manifest={data.get('num_nodes')}, expected={expected_nodes}"
        )
    if expected_edges is not None and not (
        conversion_edges == travel_edges == expected_edges == data.get("num_arcs")
    ):
        errors.append(
            f"directed arc count mismatch: edges={conversion_edges}, "
            f"travel={travel_edges}, manifest={data.get('num_arcs')}, "
            f"expected={expected_edges}"
        )
    if expected_edges is not None and fifo_edges != expected_edges:
        errors.append(
            f"FIFO edge count {fifo_edges} != expected {expected_edges}"
        )
    if data.get("seed") != expected_seed:
        errors.append(
            f"graph seed {data.get('seed')} != expected {expected_seed}"
        )
    if expected_density is not None and float(data.get("score_edge_fraction", -1)) != expected_density:
        errors.append(
            f"score density {data.get('score_edge_fraction')} "
            f"!= expected {expected_density}"
        )
    expected_score_edges = (
        math.floor(expected_density * expected_edges)
        if expected_edges is not None and expected_density is not None else None
    )
    if expected_score_edges is not None and score_edges != expected_score_edges:
        errors.append(
            f"score edge count {score_edges} != expected {expected_score_edges}"
        )
    if data.get("selected_score_edge_count") != score_edges:
        errors.append(
            f"manifest selected_score_edge_count "
            f"{data.get('selected_score_edge_count')} != payload {score_edges}"
        )
    if (
        data.get("conversion_contract", {}).get("contract_id")
        != expected_contract
    ):
        errors.append("conversion contract mismatch")
    if int(data.get("temporal_support", {}).get("end", 0)) < support_end:
        errors.append(f"manifest support ends before {support_end}")
    if require_manifest_checksums:
        if data.get("dataset_checksum") != structural:
            errors.append("dataset checksum mismatch")
        if data.get("temporal_attribute_checksum") != temporal:
            errors.append("temporal-attribute checksum mismatch")
    result = {
        "path": directory.as_posix(),
        "nodes": actual_nodes,
        "directed_arcs": conversion_edges,
        "fifo_arrival_functions": fifo_edges,
        "positive_lower_bound_edges": travel_edges,
        "selected_score_edges": score_edges,
        "dataset_checksum": structural,
        "temporal_attribute_checksum": temporal,
        "graph_checksum": full,
        "support_end": support_end,
        "contract_id": data.get("conversion_contract", {}).get("contract_id"),
        "seed": data.get("seed"),
        "score_density": data.get("score_edge_fraction"),
        "errors": errors,
    }
    if collect_score_ids:
        result["_selected_score_arc_ids"] = selected_ids
    return result


def generate_base_dataset(
    dataset: str,
    design: dict[str, Any],
    config: dict[str, Any],
    config_hash: str,
    overwrite: bool,
) -> dict[str, Any]:
    definition = design["dataset_definitions"][dataset]
    if definition.get("induced_subgraph"):
        raise ValueError(
            f"{dataset}: this is a derived payload; run "
            "experiments/scripts/create_ny_exact.py instead of the DIMACS generator"
        )
    directory = repo_path(definition["path"])
    directory.mkdir(parents=True, exist_ok=True)
    if is_converted(directory, config) and not overwrite:
        return {
            "dataset_id": dataset,
            "path": definition["path"],
            "skipped": True,
            "graph_checksum": graph_checksum(directory, REQUIRED_GRAPH_FILES),
        }
    paths = raw_paths(config, dataset)
    hashes = raw_hashes(paths)
    node_count = parse_coordinate_count(paths["coords"])
    time_nodes, time_edges = parse_problem_counts(paths["travel_time"])
    distance_nodes, distance_edges = parse_problem_counts(paths["distance"])
    if not (node_count == time_nodes == distance_nodes):
        raise ValueError(f"{dataset}: raw node counts differ")
    if time_edges != distance_edges:
        raise ValueError(f"{dataset}: raw arc counts differ")
    if (directory / "nodes.csv.gz").is_file():
        pass
    else:
        raise FileNotFoundError(
            f"{dataset}: nodes.csv.gz is missing; base coordinate regeneration is not enabled"
        )
    write_edges_from_raw(
        paths["distance"],
        paths["travel_time"],
        directory / "edges_static.csv.gz",
        int(config["conversion_contract"]["minutes_per_dimacs_weight"]["numerator"]),
        int(config["conversion_contract"]["minutes_per_dimacs_weight"]["denominator"]),
    )
    generate_travel_for_edges(
        directory / "edges_static.csv.gz",
        directory / "travel_time_functions.jsonl.gz",
        config["defaults"],
        int(config["defaults"].get("seed", design["seeds"]["graph_main"])),
    )
    generate_score_for_edges(
        directory / "edges_static.csv.gz",
        directory / "score_functions.jsonl.gz",
        config["defaults"],
        int(config["defaults"].get("seed", design["seeds"]["graph_main"])),
        float(config["defaults"]["score_edge_fraction"]),
        time_edges,
    )
    score_count = selected_score_count(directory / "score_functions.jsonl.gz")
    data = manifest(
        dataset,
        directory,
        paths,
        hashes,
        node_count,
        time_edges,
        int(config["defaults"].get("seed", design["seeds"]["graph_main"])),
        float(config["defaults"]["score_edge_fraction"]),
        score_count,
        config,
        config_hash,
    )
    atomic_write_json(directory / "manifest.json", data)
    write_readme(directory / "README_generated.md", data)
    return {
        "dataset_id": dataset,
        "path": definition["path"],
        "skipped": False,
        "graph_checksum": graph_checksum(directory, REQUIRED_GRAPH_FILES),
    }


def _generate_dataset_variants(
    dataset: str,
    design: dict[str, Any],
    config: dict[str, Any],
    config_hash: str,
    overwrite: bool,
) -> list[dict[str, Any]]:
    definition = design["dataset_definitions"][dataset]
    if not definition.get("required_score_density_percent") and not definition.get("required_graph_seeds"):
        # External canonical payloads such as OL may intentionally provide only
        # the base graph.  Do not require unavailable DIMACS source files when
        # there are no configured derived variants to build.
        return []
    base = repo_path(definition["path"])
    paths = raw_paths(config, dataset)
    hashes = raw_hashes(paths)
    base_manifest = load_document(base / "manifest.json")
    node_count = int(definition["expected_nodes"] or base_manifest["num_nodes"])
    edge_count = int(definition["expected_edges"] or base_manifest["num_arcs"])
    records = []
    for percent in definition.get("required_score_density_percent", []):
        density = int(percent) / 100.0
        variant_dir = base / "variants" / f"score-density-{int(percent):03d}"
        if variant_dir.is_dir() and is_converted(variant_dir, config) and not overwrite:
            records.append({
                "kind": "score_density",
                "value": percent,
                "path": variant_dir.relative_to(repo_path(".")).as_posix(),
                "skipped": True,
                "graph_checksum": graph_checksum(variant_dir, REQUIRED_GRAPH_FILES),
            })
            continue
        if int(percent) == 20:
            copy_required_files(base, variant_dir)
            data = manifest(
                dataset,
                variant_dir,
                paths,
                hashes,
                node_count,
                edge_count,
                int(design["seeds"]["graph_main"]),
                density,
                selected_score_count(variant_dir / "score_functions.jsonl.gz"),
                config,
                config_hash,
                {
                    "dataset_id": dataset,
                    "kind": "score_density",
                    "value": percent,
                },
            )
            atomic_write_json(variant_dir / "manifest.json", data)
            write_readme(variant_dir / "README_generated.md", data)
            records.append({
                "kind": "score_density",
                "value": percent,
                "path": variant_dir.relative_to(repo_path(".")).as_posix(),
                "skipped": False,
                "graph_checksum": graph_checksum(variant_dir, REQUIRED_GRAPH_FILES),
                "base_alias": True,
            })
            continue
        variant_dir.mkdir(parents=True, exist_ok=True)
        for name in ("nodes.csv.gz", "edges_static.csv.gz", "travel_time_functions.jsonl.gz"):
            shutil.copy2(base / name, variant_dir / name)
        selected = generate_score_for_edges(
            variant_dir / "edges_static.csv.gz",
            variant_dir / "score_functions.jsonl.gz",
            config["defaults"],
            int(design["seeds"]["graph_main"]),
            density,
            edge_count,
        )
        data = manifest(
            dataset,
            variant_dir,
            paths,
            hashes,
            node_count,
            edge_count,
            int(design["seeds"]["graph_main"]),
            density,
            selected,
            config,
            config_hash,
            {"dataset_id": dataset, "kind": "score_density", "value": percent},
        )
        atomic_write_json(variant_dir / "manifest.json", data)
        write_readme(variant_dir / "README_generated.md", data)
        records.append({
            "kind": "score_density",
            "value": percent,
            "path": variant_dir.relative_to(repo_path(".")).as_posix(),
            "skipped": False,
            "graph_checksum": graph_checksum(variant_dir, REQUIRED_GRAPH_FILES),
        })
    for seed in definition.get("required_graph_seeds", []):
        if int(seed) == int(design["seeds"]["graph_main"]):
            continue
        variant_dir = base / "variants" / f"seed-{int(seed)}"
        if variant_dir.is_dir() and is_converted(variant_dir, config) and not overwrite:
            records.append({
                "kind": "graph_seed",
                "value": seed,
                "path": variant_dir.relative_to(repo_path(".")).as_posix(),
                "skipped": True,
                "graph_checksum": graph_checksum(variant_dir, REQUIRED_GRAPH_FILES),
            })
            continue
        variant_dir.mkdir(parents=True, exist_ok=True)
        for name in ("nodes.csv.gz", "edges_static.csv.gz"):
            shutil.copy2(base / name, variant_dir / name)
        generate_travel_for_edges(
            variant_dir / "edges_static.csv.gz",
            variant_dir / "travel_time_functions.jsonl.gz",
            config["defaults"],
            int(seed),
        )
        selected = generate_score_for_edges(
            variant_dir / "edges_static.csv.gz",
            variant_dir / "score_functions.jsonl.gz",
            config["defaults"],
            int(seed),
            float(config["defaults"]["score_edge_fraction"]),
            edge_count,
        )
        data = manifest(
            dataset,
            variant_dir,
            paths,
            hashes,
            node_count,
            edge_count,
            int(seed),
            float(config["defaults"]["score_edge_fraction"]),
            selected,
            config,
            config_hash,
            {"dataset_id": dataset, "kind": "graph_seed", "value": seed},
        )
        atomic_write_json(variant_dir / "manifest.json", data)
        write_readme(variant_dir / "README_generated.md", data)
        records.append({
            "kind": "graph_seed",
            "value": seed,
            "path": variant_dir.relative_to(repo_path(".")).as_posix(),
            "skipped": False,
            "graph_checksum": graph_checksum(variant_dir, REQUIRED_GRAPH_FILES),
        })
    return records


def generate_required_variants(
    design: dict[str, Any],
    config: dict[str, Any],
    config_hash: str,
    overwrite: bool,
) -> list[dict[str, Any]]:
    records = []
    for dataset in design["datasets"]:
        if design["dataset_definitions"][dataset].get("induced_subgraph"):
            continue
        for record in _generate_dataset_variants(
            dataset, design, config, config_hash, overwrite
        ):
            records.append({"dataset_id": dataset, **record})
    return records


def dataset_directories(
    design: dict[str, Any],
) -> list[tuple[str, str, int | float, Path]]:
    result: list[tuple[str, str, int | float, Path]] = []
    for dataset in design["datasets"]:
        definition = design["dataset_definitions"][dataset]
        base = repo_path(definition["path"])
        result.append((dataset, "base", design["seeds"]["graph_main"], base))
        for percent in definition.get("required_score_density_percent", []):
            result.append((
                dataset,
                "score_density",
                int(percent),
                base / "variants" / f"score-density-{int(percent):03d}",
            ))
        for seed in definition.get("required_graph_seeds", []):
            if int(seed) == int(design["seeds"]["graph_main"]):
                continue
            result.append((
                dataset,
                "graph_seed",
                int(seed),
                base / "variants" / f"seed-{int(seed)}",
            ))
    return result


def refresh_all_manifest_checksums(
    design: dict[str, Any],
) -> list[str]:
    changed = []
    for dataset, kind, value, directory in dataset_directories(design):
        missing = [
            name for name in REQUIRED_GRAPH_FILES
            if not (directory / name).is_file()
        ]
        if missing:
            continue
        if refresh_manifest_checksums(directory):
            changed.append(f"{dataset}:{kind}:{value}")
    return changed


def validate_assets(
    design: dict[str, Any],
    config: dict[str, Any],
) -> dict[str, Any]:
    errors: list[str] = []
    records: list[dict[str, Any]] = []
    variants: list[dict[str, Any]] = []
    expected_contract = config["conversion_contract"]["contract_id"]
    numerator = int(
        config["conversion_contract"]["minutes_per_dimacs_weight"]["numerator"]
    )
    denominator = int(
        config["conversion_contract"]["minutes_per_dimacs_weight"]["denominator"]
    )
    base_score_ids: set[int] | None = None
    density_score_ids: list[tuple[int, set[int]]] = []
    for dataset in design["datasets"]:
        definition = design["dataset_definitions"][dataset]
        directory = repo_path(definition["path"])
        missing = [
            name for name in REQUIRED_GRAPH_FILES
            if not (directory / name).is_file()
        ]
        if missing:
            dataset_errors = [
                f"{dataset}: missing {name}" for name in missing
            ]
            errors.extend(dataset_errors)
            records.append({
                "dataset_id": dataset,
                "path": definition["path"],
                "missing_files": missing,
                "errors": dataset_errors,
            })
            continue
        try:
            validated = validate_dataset_directory(
                directory,
                definition.get("expected_nodes"),
                definition.get("expected_edges"),
                int(design["seeds"]["graph_main"]),
                definition.get("expected_score_density", config["defaults"]["score_edge_fraction"]),
                expected_contract,
                int(definition["required_support_end"]),
                None if (
                    definition.get("induced_subgraph")
                    or definition.get("asset_status") == "external_input_required"
                ) else raw_paths(config, dataset)["travel_time"],
                numerator,
                denominator,
                collect_score_ids=dataset == "NY",
            )
        except (OSError, ValueError, KeyError, TypeError) as failure:
            validated = {
                "path": directory.as_posix(),
                "errors": [str(failure)],
            }
        selected = validated.pop("_selected_score_arc_ids", None)
        if dataset == "NY":
            base_score_ids = selected
        dataset_errors = [
            f"{dataset}: {message}" for message in validated["errors"]
        ]
        errors.extend(dataset_errors)
        validated["dataset_id"] = dataset
        validated["errors"] = dataset_errors
        records.append(validated)

    ny = design["dataset_definitions"].get("NY")
    if ny is not None:
        base = repo_path(ny["path"])
        for percent in ny.get("required_score_density_percent", []):
            directory = base / "variants" / f"score-density-{int(percent):03d}"
            try:
                validated = validate_dataset_directory(
                    directory,
                    ny.get("expected_nodes"),
                    ny.get("expected_edges"),
                    int(design["seeds"]["graph_main"]),
                    int(percent) / 100.0,
                    expected_contract,
                    int(ny["required_support_end"]),
                    None,
                    numerator,
                    denominator,
                    collect_score_ids=True,
                )
            except (OSError, ValueError, KeyError, TypeError) as failure:
                validated = {
                    "path": directory.as_posix(),
                    "errors": [str(failure)],
                }
            selected = validated.pop("_selected_score_arc_ids", set())
            density_score_ids.append((int(percent), selected))
            variant_errors = [
                f"NY score-density {percent}%: {message}"
                for message in validated["errors"]
            ]
            errors.extend(variant_errors)
            validated.update({
                "dataset_id": "NY",
                "variant_kind": "score_density",
                "variant_value": int(percent),
                "errors": variant_errors,
            })
            variants.append(validated)
        previous: set[int] = set()
        for percent, selected in sorted(density_score_ids):
            if not selected.issuperset(previous):
                message = (
                    f"NY score-density {percent}% is not nested over the "
                    f"previous density"
                )
                errors.append(message)
            previous = selected
        density_twenty = dict(density_score_ids).get(20)
        if (
            base_score_ids is not None
            and density_twenty is not None
            and base_score_ids != density_twenty
        ):
            errors.append(
                "NY base seed-42 score support differs from the 20% variant"
            )
        for seed in ny.get("required_graph_seeds", []):
            if int(seed) == int(design["seeds"]["graph_main"]):
                variants.append({
                    "dataset_id": "NY",
                    "variant_kind": "graph_seed",
                    "variant_value": int(seed),
                    "path": base.as_posix(),
                    "base_alias": True,
                    "errors": [],
                })
                continue
            directory = base / "variants" / f"seed-{int(seed)}"
            try:
                validated = validate_dataset_directory(
                    directory,
                    ny.get("expected_nodes"),
                    ny.get("expected_edges"),
                    int(seed),
                    float(config["defaults"]["score_edge_fraction"]),
                    expected_contract,
                    int(ny["required_support_end"]),
                    None,
                    numerator,
                    denominator,
                )
            except (OSError, ValueError, KeyError, TypeError) as failure:
                validated = {
                    "path": directory.as_posix(),
                    "errors": [str(failure)],
                }
            variant_errors = [
                f"NY graph seed {seed}: {message}"
                for message in validated["errors"]
            ]
            errors.extend(variant_errors)
            validated.update({
                "dataset_id": "NY",
                "variant_kind": "graph_seed",
                "variant_value": int(seed),
                "errors": variant_errors,
            })
            variants.append(validated)
    for dataset in design["datasets"]:
        if dataset == "NY":
            continue
        definition = design["dataset_definitions"][dataset]
        base = repo_path(definition["path"])
        for seed in definition.get("required_graph_seeds", []):
            if int(seed) == int(design["seeds"]["graph_main"]):
                variants.append({
                    "dataset_id": dataset,
                    "variant_kind": "graph_seed",
                    "variant_value": int(seed),
                    "path": base.as_posix(),
                    "base_alias": True,
                    "errors": [],
                })
                continue
            directory = base / "variants" / f"seed-{int(seed)}"
            try:
                validated = validate_dataset_directory(
                    directory,
                    definition.get("expected_nodes"),
                    definition.get("expected_edges"),
                    int(seed),
                    float(config["defaults"]["score_edge_fraction"]),
                    expected_contract,
                    int(definition["required_support_end"]),
                    None,
                    numerator,
                    denominator,
                )
            except (OSError, ValueError, KeyError, TypeError) as failure:
                validated = {
                    "path": directory.as_posix(),
                    "errors": [str(failure)],
                }
            variant_errors = [
                f"{dataset} graph seed {seed}: {message}"
                for message in validated["errors"]
            ]
            errors.extend(variant_errors)
            validated.update({
                "dataset_id": dataset,
                "variant_kind": "graph_seed",
                "variant_value": int(seed),
                "errors": variant_errors,
            })
            variants.append(validated)
    return {
        "schema_version": 2,
        "datasets": records,
        "variants": variants,
        "errors": errors,
        "passed": not errors,
    }


def plan_assets(
    design: dict[str, Any],
    config: dict[str, Any],
    overwrite: bool,
) -> dict[str, Any]:
    actions = []
    for dataset, kind, value, directory in dataset_directories(design):
        exists = is_converted(directory, config)
        derived = bool(design["dataset_definitions"][dataset].get("induced_subgraph"))
        action = (
            "derive-with-create_ny_exact"
            if derived and not exists
            else "validate-derived"
            if derived
            else "regenerate" if overwrite else ("validate" if exists else "generate")
        )
        actions.append({
            "dataset_id": dataset,
            "kind": kind,
            "value": value,
            "path": directory.as_posix(),
            "action": action,
        })
    return {
        "schema_version": 1,
        "mode": "plan-only",
        "actions": actions,
        "passed": True,
    }


def run(
    config_path: Path,
    generation_config_path: Path,
    validate_only: bool,
    overwrite: bool,
    resume: bool = False,
    plan_only: bool = False,
) -> dict[str, Any]:
    design = load_design(config_path)
    generation_config = load_document(generation_config_path)
    config_hash = sha256_json(generation_config)
    if plan_only:
        return plan_assets(design, generation_config, overwrite)
    if validate_only:
        return validate_assets(design, generation_config)
    refreshed = refresh_all_manifest_checksums(design) if resume else []
    base_records = []
    for dataset in design["datasets"]:
        if design["dataset_definitions"][dataset].get("induced_subgraph"):
            base_records.append({
                "dataset_id": dataset,
                "path": design["dataset_definitions"][dataset]["path"],
                "skipped": True,
                "reason": "derived dataset; use create_ny_exact.py",
            })
        else:
            base_records.append(
                generate_base_dataset(
                    dataset, design, generation_config, config_hash, overwrite
                )
            )
    variants = generate_required_variants(
        design, generation_config, config_hash, overwrite
    )
    validation = validate_assets(design, generation_config)
    report = {
        "schema_version": 1,
        "config": config_path.as_posix(),
        "generation_config": generation_config_path.as_posix(),
        "generation_config_sha256": config_hash,
        "base_datasets": base_records,
        "variants": variants,
        "resumed_manifest_metadata": refreshed,
        "validation": validation,
        "passed": validation["passed"],
    }
    atomic_write_json(repo_path("experiments/results/asset_generation_report.json"), report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("experiments/configs/paper_q1_server_24c_250g.yaml"))
    parser.add_argument("--generation-config", type=Path, default=Path("experiments/configs/dataset_generation.yaml"))
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--plan-only", action="store_true")
    args = parser.parse_args()
    selected_modes = sum(
        int(value)
        for value in (
            args.validate_only,
            args.overwrite,
            args.resume,
            args.plan_only,
        )
    )
    if selected_modes > 1:
        parser.error(
            "--validate-only, --overwrite, --resume, and --plan-only "
            "are mutually exclusive"
        )
    try:
        report = run(
            args.config,
            args.generation_config,
            args.validate_only,
            args.overwrite,
            args.resume,
            args.plan_only,
        )
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"dataset asset generation: {failure}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report.get("passed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
