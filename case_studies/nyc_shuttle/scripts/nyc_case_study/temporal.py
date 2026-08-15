"""Observed-horizon travel functions and active transit-corridor scores."""

from __future__ import annotations

import csv
import datetime as dt
import gzip
import json
import math
import shutil
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo

from .common import (
    CaseStudyError, atomic_write_json, atomic_write_text, canonical_json,
    git_revision, parse_timestamp, sha256_file,
)


PROVENANCE = ("DIRECT", "BOROUGH_CLASS_IMPUTED", "CITYWIDE_IMPUTED", "STATIC_FALLBACK")


def capped_route_score(route_ids: Iterable[object], cap: int = 15) -> int:
    if cap < 0:
        raise ValueError("score cap cannot be negative")
    return min(cap, len({str(value) for value in route_ids if str(value).strip()}))


def fifo_repair(times: list[float], travel_times: list[float]) -> tuple[list[float], int]:
    """Least one-sided cumulative-maximum repair of sampled arrivals."""
    if len(times) != len(travel_times) or len(times) < 2:
        raise ValueError("equal time/travel arrays with at least two knots are required")
    repaired: list[float] = []
    previous = -math.inf
    changes = 0
    for time, travel in zip(times, travel_times):
        if not math.isfinite(time) or not math.isfinite(travel) or travel < 0:
            raise ValueError("finite nonnegative sampled travel times are required")
        arrival = time + travel
        fixed = max(arrival, previous)
        if fixed > arrival + 1e-12:
            changes += 1
        repaired.append(max(0.0, fixed - time))
        previous = fixed
    return repaired, changes


def validate_fifo(times: list[float], travel_times: list[float]) -> None:
    previous = -math.inf
    for index, (time, travel) in enumerate(zip(times, travel_times)):
        if travel < 0 or not math.isfinite(travel):
            raise CaseStudyError(f"negative/non-finite travel time at knot {index}")
        arrival = time + travel
        if arrival + 1e-9 < previous:
            raise CaseStudyError(f"non-FIFO arrival at knot {index}: {arrival} < {previous}")
        previous = arrival


def longest_contiguous_bins(values: Iterable[str], minutes: int) -> list[dt.datetime]:
    unique = sorted({parse_timestamp(value) for value in values})
    if not unique:
        return []
    step = dt.timedelta(minutes=minutes)
    segments: list[list[dt.datetime]] = [[unique[0]]]
    for value in unique[1:]:
        if value == segments[-1][-1] + step:
            segments[-1].append(value)
        else:
            segments.append([value])
    return max(segments, key=lambda segment: (len(segment), -segment[0].timestamp()))


def _number(value: object) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def build_travel_graph(source_graph: Path, traffic_path: Path, dot_map_path: Path,
                       centerline_map_path: Path, output_graph: Path,
                       report: Path, config: dict[str, Any]) -> dict[str, Any]:
    import pandas as pd  # type: ignore

    minutes = int(config["time_bin_minutes"])
    traffic = pd.read_parquet(traffic_path)
    dot_map = pd.read_parquet(dot_map_path)
    attributes = pd.read_parquet(centerline_map_path)
    required_traffic = {"link_id", "bin_start_utc", "median_speed", "median_travel_time"}
    required_map = {"link_id", "arc_id", "length_allocation_fraction", "mapped_arc_length_m"}
    if not required_traffic.issubset(traffic.columns):
        raise CaseStudyError(f"traffic bins missing {sorted(required_traffic - set(traffic.columns))}")
    if not required_map.issubset(dot_map.columns):
        raise CaseStudyError(f"DOT map missing {sorted(required_map - set(dot_map.columns))}")
    bins = longest_contiguous_bins(traffic.bin_start_utc.astype(str), minutes)
    if len(bins) < 2:
        raise CaseStudyError(
            "fewer than two contiguous observed 15-minute traffic bins; collect real DOT snapshots longer"
        )
    bin_keys = [value.isoformat().replace("+00:00", "Z") for value in bins]
    traffic = traffic[traffic.bin_start_utc.astype(str).isin(bin_keys)].copy()
    joined = traffic.merge(dot_map, on="link_id", how="inner", validate="many_to_many")

    edge_path = source_graph / "edges_static.csv.gz"
    base_rows: list[dict[str, str]] = []
    with gzip.open(edge_path, "rt", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        base_rows.extend(reader)
    if any(int(row["arc_id"]) != index for index, row in enumerate(base_rows)):
        raise CaseStudyError("source graph arc IDs are not stable consecutive row IDs")
    base = {int(row["arc_id"]): float(row["base_travel_time"]) for row in base_rows}

    direct_values: dict[tuple[int, str], list[float]] = defaultdict(list)
    rejected = 0
    for row in joined.itertuples(index=False):
        travel_seconds = _number(row.median_travel_time)
        speed_mph = _number(row.median_speed)
        fraction = _number(row.length_allocation_fraction) or 0.0
        arc_length = _number(row.mapped_arc_length_m) or 0.0
        if travel_seconds is not None and 0 < travel_seconds <= 7200 and fraction > 0:
            travel_minutes = travel_seconds * fraction / 60.0
        elif speed_mph is not None and 0 < speed_mph <= 100 and arc_length > 0:
            travel_minutes = arc_length / (speed_mph * 1609.344 / 60.0)
        else:
            rejected += 1
            continue
        arc_id = int(row.arc_id)
        multiplier = travel_minutes / base[arc_id]
        if not (0.05 <= multiplier <= 20.0):
            rejected += 1
            continue
        direct_values[(arc_id, str(row.bin_start_utc))].append(travel_minutes)

    def clean_attribute(value: object) -> str:
        if value is None or (isinstance(value, float) and math.isnan(value)):
            return "UNKNOWN"
        text = str(value).strip()
        return "UNKNOWN" if not text or text.lower() in {"nan", "none", "null"} else text

    attrs: dict[int, tuple[str, str]] = {}
    for row in attributes.itertuples(index=False):
        attrs[int(row.arc_id)] = (clean_attribute(getattr(row, "borough", None)),
                                  clean_attribute(getattr(row, "road_type", None)))
    group_multipliers: dict[tuple[str, str, str], list[float]] = defaultdict(list)
    city_multipliers: dict[str, list[float]] = defaultdict(list)
    direct_medians: dict[tuple[int, str], float] = {}
    for key, values in direct_values.items():
        arc_id, bin_key = key
        value = statistics.median(values)
        direct_medians[key] = value
        multiplier = value / base[arc_id]
        borough, road = attrs.get(arc_id, ("UNKNOWN", "UNKNOWN"))
        group_multipliers[(bin_key, borough, road)].append(multiplier)
        city_multipliers[bin_key].append(multiplier)
    group_median = {key: statistics.median(values) for key, values in group_multipliers.items()}
    city_median = {key: statistics.median(values) for key, values in city_multipliers.items()}

    output_graph.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_graph / "nodes.csv.gz", output_graph / "nodes.csv.gz")
    shutil.copy2(edge_path, output_graph / "edges_static.csv.gz")
    travel_output = output_graph / "travel_time_functions.jsonl.gz"
    provenance_output = output_graph / "travel_time_provenance.jsonl.gz"
    counts: Counter[str] = Counter()
    repairs = 0
    knot_times = [index * minutes for index in range(len(bins) + 1)]
    with gzip.open(travel_output, "wt", encoding="utf-8", newline="\n") as travel_stream, \
            gzip.open(provenance_output, "wt", encoding="utf-8", newline="\n") as provenance_stream:
        for row in base_rows:
            arc_id = int(row["arc_id"])
            base_time = float(row["base_travel_time"])
            borough, road = attrs.get(arc_id, ("UNKNOWN", "UNKNOWN"))
            values: list[float] = []
            sources: list[str] = []
            for bin_key in bin_keys:
                if (arc_id, bin_key) in direct_medians:
                    value = direct_medians[(arc_id, bin_key)]
                    source = "DIRECT"
                elif (bin_key, borough, road) in group_median and borough != "UNKNOWN":
                    value = base_time * group_median[(bin_key, borough, road)]
                    source = "BOROUGH_CLASS_IMPUTED"
                elif bin_key in city_median:
                    value = base_time * city_median[bin_key]
                    source = "CITYWIDE_IMPUTED"
                else:
                    value = base_time
                    source = "STATIC_FALLBACK"
                values.append(value)
                sources.append(source)
                counts[source] += 1
            sampled = values + [values[-1]]
            repaired, changed = fifo_repair(knot_times, sampled)
            validate_fifo(knot_times, repaired)
            repairs += changed
            travel_record = {
                "arc_id": arc_id, "u": int(row["u"]), "v": int(row["v"]),
                "distance": int(row["distance"]), "base_travel_time": base_time,
                "travel_time_breakpoints": [[time, round(value, 9)] for time, value in zip(knot_times, repaired)],
            }
            provenance_record = {
                "arc_id": arc_id,
                "bins": [[index * minutes, (index + 1) * minutes, source]
                         for index, source in enumerate(sources)],
            }
            travel_stream.write(canonical_json(travel_record) + "\n")
            provenance_stream.write(canonical_json(provenance_record) + "\n")

    horizon_start = bins[0]
    horizon_end = bins[-1] + dt.timedelta(minutes=minutes)
    manifest = {
        "schema_version": 3,
        "case_study_schema_version": 1,
        "case_id": config["case_id"],
        "num_nodes": _gzip_row_count(output_graph / "nodes.csv.gz"),
        "num_arcs": len(base_rows),
        "seed": int(config["seed"]),
        "selected_score_edge_count": 0,
        "unlisted_edges_have_score_zero": True,
        "conversion_contract": json.loads((source_graph / "manifest.json").read_text())["conversion_contract"],
        "temporal_support": {
            "start": 0, "end": len(bins) * minutes,
            "observed_start_utc": horizon_start.isoformat().replace("+00:00", "Z"),
            "observed_end_utc": horizon_end.isoformat().replace("+00:00", "Z"),
            "extension_policy": "none; exact longest contiguous observed DOT-bin horizon",
        },
        "traffic_calibration": {
            "bin_minutes": minutes,
            "source_timestamp_type": "Socrata Floating Timestamp",
            "source_timezone": config["dot_floating_timestamp_timezone"],
            "normalization_timezone": "UTC",
            "contiguous_bin_count": len(bins),
            "invalid_observations_rejected": rejected,
            "provenance_counts": dict(counts),
            "fifo_repaired_knots": repairs,
            "fifo_repair_policy": "cumulative maximum of sampled arrivals; piecewise-linear interpolation",
            "no_day_wrapping": True,
            "no_extrapolation": True,
        },
        "source_graph": str(source_graph),
        "source_graph_manifest_sha256": sha256_file(source_graph / "manifest.json"),
        "pace_revision": git_revision(),
        "output_files": ["nodes.csv.gz", "edges_static.csv.gz", "travel_time_functions.jsonl.gz",
                         "travel_time_provenance.jsonl.gz", "manifest.json"],
    }
    atomic_write_json(output_graph / "manifest.json", manifest)
    total = sum(counts.values())
    stats = {
        "temporal_start_utc": manifest["temporal_support"]["observed_start_utc"],
        "temporal_end_utc": manifest["temporal_support"]["observed_end_utc"],
        "horizon_minutes": len(bins) * minutes,
        "fifo_repaired_knots": repairs,
        "invalid_observations_rejected": rejected,
        "provenance_percent": {key: 100 * counts[key] / total for key in PROVENANCE},
        "travel_functions_sha256": sha256_file(travel_output),
        "provenance_sha256": sha256_file(provenance_output),
    }
    atomic_write_text(report, "# NYC temporal travel-function construction\n\n" +
                      "\n".join(f"- {key}: {value}" for key, value in stats.items()) + "\n")
    return stats


def _gzip_row_count(path: Path) -> int:
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        return max(0, sum(1 for _ in stream) - 1)


def _schedule_time(date_value: object, time_value: object, timezone: ZoneInfo) -> dt.datetime:
    date = parse_timestamp(str(date_value)).date() if "T" in str(date_value) else dt.date.fromisoformat(str(date_value)[:10])
    text = str(time_value).strip()
    if "T" in text:
        normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
        parsed = dt.datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone)
        return parsed.astimezone(timezone)
    parts = text.split(":")
    if len(parts) < 2:
        raise ValueError(f"invalid schedule time {text!r}")
    hour, minute = int(parts[0]), int(parts[1])
    second = int(float(parts[2])) if len(parts) > 2 else 0
    return dt.datetime.combine(date + dt.timedelta(days=hour // 24), dt.time(hour % 24, minute, second), timezone)


def active_route_bins(schedule: Any, horizon_start_utc: str, horizon_end_utc: str,
                      bin_minutes: int) -> tuple[set[tuple[str, str, int]], dict[str, Any]]:
    """Reconstruct block trips from stop-sequence resets and return active bins."""
    aliases = {
        "date": ["schedule_date"], "route": ["route_id"], "shape": ["shape_id"],
        "sequence": ["stop_sequence"], "time": ["schedule_time"],
        "block": ["block_id", "service_id"], "direction": ["direction", "direction_id"],
    }
    normalized = {str(column).lower(): str(column) for column in schedule.columns}
    fields: dict[str, str] = {}
    missing = []
    for name, candidates in aliases.items():
        match = next((normalized[item] for item in candidates if item in normalized), None)
        if match is None:
            missing.append(name)
        else:
            fields[name] = match
    if missing:
        raise CaseStudyError(f"MTA schedule cannot reconstruct trip activity; missing fields: {missing}")
    timezone = ZoneInfo("America/New_York")
    start = parse_timestamp(horizon_start_utc)
    end = parse_timestamp(horizon_end_utc)
    local_dates = {start.astimezone(timezone).date(), (end - dt.timedelta(microseconds=1)).astimezone(timezone).date()}
    schedule = schedule[schedule[fields["date"]].astype(str).str[:10].isin({item.isoformat() for item in local_dates})].copy()
    if schedule.empty:
        raise CaseStudyError(
            f"MTA schedules have no rows for captured traffic dates {sorted(map(str, local_dates))}; "
            "do not substitute another day"
        )
    parsed: list[dt.datetime | None] = []
    bad_times = 0
    for row in schedule.itertuples(index=False, name=None):
        record = dict(zip(schedule.columns, row))
        try:
            parsed.append(_schedule_time(record[fields["date"]], record[fields["time"]], timezone).astimezone(dt.timezone.utc))
        except (ValueError, TypeError):
            parsed.append(None)
            bad_times += 1
    schedule["_instant"] = parsed
    schedule = schedule[schedule._instant.notna()].copy()
    schedule["_sequence"] = schedule[fields["sequence"]].astype(int)
    group_fields = [fields["date"], fields["route"], fields["shape"], fields["block"], fields["direction"]]
    active: set[tuple[str, str, int]] = set()
    trips = 0
    for _, group in schedule.sort_values(group_fields + ["_instant"]).groupby(group_fields, dropna=False):
        current: list[Any] = []
        previous = -1
        chunks: list[list[Any]] = []
        for row in group.itertuples(index=False):
            sequence = int(row[group.columns.get_loc("_sequence")])
            if current and sequence <= previous:
                chunks.append(current)
                current = []
            current.append(row)
            previous = sequence
        if current:
            chunks.append(current)
        for chunk in chunks:
            trips += 1
            row0 = chunk[0]
            first = min(row[group.columns.get_loc("_instant")] for row in chunk)
            last = max(row[group.columns.get_loc("_instant")] for row in chunk)
            route = str(row0[group.columns.get_loc(fields["route"])]).strip()
            shape = str(row0[group.columns.get_loc(fields["shape"])]).strip()
            first = max(first, start)
            last = min(last, end)
            if last < first:
                continue
            first_bin = max(0, int((first - start).total_seconds() // (bin_minutes * 60)))
            last_bin = min(math.ceil((end - start).total_seconds() / (bin_minutes * 60)) - 1,
                           int((last - start).total_seconds() // (bin_minutes * 60)))
            for index in range(first_bin, last_bin + 1):
                active.add((route, shape, index))
    return active, {"schedule_rows": len(schedule), "reconstructed_trips": trips,
                    "invalid_schedule_times": bad_times}


def read_schedule_pages(paths: list[Path]) -> Any:
    import pandas as pd  # type: ignore
    if not paths:
        raise CaseStudyError("no MTA schedule CSV pages supplied")
    return pd.concat((pd.read_csv(path, dtype=str) for path in paths), ignore_index=True)


def build_scores(output_graph: Path, shape_map_path: Path, schedule_pages: list[Path],
                 report: Path, config: dict[str, Any]) -> dict[str, Any]:
    import pandas as pd  # type: ignore

    manifest_path = output_graph / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    support = manifest["temporal_support"]
    minutes = int(config["time_bin_minutes"])
    active, schedule_stats = active_route_bins(
        read_schedule_pages(schedule_pages), support["observed_start_utc"],
        support["observed_end_utc"], minutes,
    )
    mapping = pd.read_parquet(shape_map_path)
    required = {"route_id", "shape_id", "arc_id"}
    if not required.issubset(mapping.columns):
        raise CaseStudyError(f"MTA shape map missing {sorted(required - set(mapping.columns))}")
    route_by_shape_bin: dict[tuple[str, int], set[str]] = defaultdict(set)
    for route, shape, bin_index in active:
        route_by_shape_bin[(shape, bin_index)].add(route)
    arcs_by_shape = mapping.groupby(mapping.shape_id.astype(str)).arc_id.apply(lambda values: sorted(set(map(int, values))))
    edge_bins: dict[int, dict[int, set[str]]] = defaultdict(lambda: defaultdict(set))
    unmatched_active_shapes: set[str] = set()
    for (shape, bin_index), routes in route_by_shape_bin.items():
        if shape not in arcs_by_shape:
            unmatched_active_shapes.add(shape)
            continue
        for arc_id in arcs_by_shape[shape]:
            edge_bins[arc_id][bin_index].update(routes)
    if not edge_bins:
        raise CaseStudyError("no active MTA route pattern joins the route-shape mapping in the traffic horizon")
    score_arc_ids = frozenset(edge_bins)
    joined_route_ids = {
        route for route, shape, _ in active if shape in arcs_by_shape
    }
    horizon = int(support["end"])
    bin_count = horizon // minutes
    cap = int(config["score"]["cap"])
    score_output = output_graph / "score_functions.jsonl.gz"
    distributions: Counter[int] = Counter()
    breakpoints = 0
    with gzip.open(score_output, "wt", encoding="utf-8", newline="\n") as stream:
        with gzip.open(output_graph / "edges_static.csv.gz", "rt", encoding="utf-8", newline="") as edges_stream:
            for row in csv.DictReader(edges_stream):
                arc_id = int(row["arc_id"])
                values = [capped_route_score(edge_bins.get(arc_id, {}).get(index, set()), cap)
                          for index in range(bin_count)]
                distributions.update(values)
                if not any(values):
                    continue
                intervals: list[list[int]] = []
                start = 0
                value = values[0]
                for index in range(1, bin_count):
                    if values[index] != value:
                        intervals.append([start * minutes, index * minutes, value])
                        start = index
                        value = values[index]
                intervals.append([start * minutes, horizon, value])
                breakpoints += max(0, len(intervals) - 1)
                stream.write(canonical_json({
                    "arc_id": arc_id, "u": int(row["u"]), "v": int(row["v"]),
                    "selected_for_score": True, "score_intervals": intervals,
                }) + "\n")
    bearing_edges = len(score_arc_ids)
    manifest["selected_score_edge_count"] = bearing_edges
    manifest["score_definition"] = {
        "name": "active transit-corridor affinity",
        "formula": "sigma_e(t)=min(15, count(distinct active route_id using e in bin t))",
        "count_unit": "route_id",
        "cap": cap,
        "bin_minutes": minutes,
        "service_day_filter": "exact local dates overlapping captured DOT horizon",
        "score_breakpoints": breakpoints,
        "score_bearing_edges": bearing_edges,
        "active_route_ids": len({route for route, _, _ in active}),
        "mapped_active_route_ids": len(joined_route_ids),
        "active_shape_ids": len({shape for _, shape, _ in active}),
        "unmatched_active_shapes": len(unmatched_active_shapes),
    }
    if "score_functions.jsonl.gz" not in manifest["output_files"]:
        manifest["output_files"].append("score_functions.jsonl.gz")
    atomic_write_json(manifest_path, manifest)
    arc_count = int(manifest["num_arcs"])
    stats = {
        **schedule_stats,
        "active_route_shape_bins": len(active),
        "active_route_ids": len({route for route, _, _ in active}),
        "mapped_active_route_ids": len(joined_route_ids),
        "active_shape_ids": len({shape for _, shape, _ in active}),
        "unmatched_active_shapes": len(unmatched_active_shapes),
        "score_bearing_edges": bearing_edges,
        "score_bearing_edge_percent": 100 * bearing_edges / arc_count,
        "score_breakpoints": breakpoints,
        "score_value_distribution_edge_bins": dict(sorted(distributions.items())),
        "score_functions_sha256": sha256_file(score_output),
    }
    lines = [
        "# Transit-corridor affinity score", "",
        "The primary score is a shuttle corridor preference proxy, not a ground-truth MTA preference.", "",
        "For each directed DIMACS arc `e` and 15-minute bin `t`, `N_e(t)` is the number of distinct ",
        "scheduled active MTA `route_id` values whose mapped route shape uses `e`. The score is ",
        "`sigma_e(t) = min(15, N_e(t))`; all other arcs have score zero.", "",
        "Route IDs are counted instead of shape IDs so bundle/direction shape duplication cannot inflate the proxy.", "",
    ] + [f"- {key}: {value}" for key, value in stats.items()]
    atomic_write_text(report, "\n".join(lines) + "\n")
    return stats
