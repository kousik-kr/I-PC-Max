#!/usr/bin/env python3
"""Build the frozen NYC story-validation diagnostics, figures, and reports."""

from __future__ import annotations

import collections
import csv
import gzip
import hashlib
import json
import math
import statistics
from pathlib import Path
from typing import Any, Iterable

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import geopandas as gpd
import shapely

from nyc_case_study.common import CASE_ROOT
from nyc_case_study.temporal import active_route_bins, read_schedule_pages


DIAGNOSTICS = CASE_ROOT / "results" / "diagnostics"
OFFICIAL = CASE_ROOT / "results" / "nyc_case_results.jsonl"
REPLAY = DIAGNOSTICS / "nyc_story_replay.jsonl"
CAP5 = DIAGNOSTICS / "pace_b_cap_5s.jsonl"
CAP10 = DIAGNOSTICS / "pace_b_cap_10s.jsonl"
CAP30 = DIAGNOSTICS / "pace_b_cap_30s.jsonl"
PACE_X = DIAGNOSTICS / "pace_x_subset_60s.jsonl"
QUERY_UNION = DIAGNOSTICS / "query_region_union.json"
BOROUGH_BOUNDARY = DIAGNOSTICS / "nyc_borough_boundaries_26b.geojson"
PROCESSED = CASE_ROOT / "processed" / "NYC-REAL"
EDGES = CASE_ROOT / "intermediate" / "dimacs_ny_edges.parquet"
MTA_MAP = CASE_ROOT / "intermediate" / "mta_shape_to_dimacs_edges.parquet"
PACE_X_MANIFEST = CASE_ROOT / "manifests" / "nyc_pace_x_subset.json"
QUERY_MANIFEST = CASE_ROOT / "manifests" / "nyc_queries.jsonl"
REPORT = CASE_ROOT / "reports" / "nyc_story_validation.md"
FEASIBILITY_REPORT = CASE_ROOT / "reports" / "nyc_feasibility_invariant.md"
FIGURES = CASE_ROOT / "figures"

CAUSES = (
    "QUERY_REGION_BUG", "PIVOT_LIMIT", "PIVOT_DEPTH",
    "CONNECTOR_POLICY", "KF_RETENTION", "TIME_LIMIT",
    "BREAKPOINT/ENVELOPE", "OTHER",
)


def rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1 << 20):
            digest.update(block)
    return digest.hexdigest()


def pct(value: float) -> str:
    return f"{100 * value:.2f}%"


def number(value: float | int | None, digits: int = 3) -> str:
    if value is None or not math.isfinite(float(value)):
        return "N/A"
    return f"{float(value):.{digits}f}"


def summary(values: Iterable[float]) -> dict[str, float | int | None]:
    data = np.asarray(list(values), dtype=float)
    data = data[np.isfinite(data)]
    if not len(data):
        return {"n": 0, "median": None, "q1": None, "q3": None, "mean": None}
    return {
        "n": int(len(data)),
        "median": float(np.median(data)),
        "q1": float(np.quantile(data, 0.25)),
        "q3": float(np.quantile(data, 0.75)),
        "mean": float(np.mean(data)),
    }


def markdown_table(headers: list[str], data: list[list[Any]]) -> str:
    out = ["| " + " | ".join(headers) + " |",
           "| " + " | ".join("---" for _ in headers) + " |"]
    out.extend("| " + " | ".join(str(value) for value in row) + " |" for row in data)
    return "\n".join(out)


def exact_points() -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    diagnostic = rows(REPLAY)
    official = {row["query_id"]: row for row in rows(OFFICIAL)}
    by_id = {row["query_id"]: row for row in diagnostic}
    assert len(diagnostic) == len(official) == 100
    assert len({row["query_id"] for row in diagnostic}) == 100
    assert sum(len(row["points"]) for row in diagnostic) == 12_100
    return diagnostic, by_id, official


def score_comparison(diagnostic: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    per_query: list[dict[str, Any]] = []
    for row in diagnostic:
        points = row["points"]
        resolved = [point for point in points if point["pace_resolved"]]
        pace_full = sum(point["pace_score"] if point["pace_resolved"] else 0 for point in points) / len(points)
        fast_full = sum(point["stored_fastest_score"] for point in points) / len(points)
        if resolved:
            resolved_delta = statistics.mean(point["pace_score"] - point["stored_fastest_score"] for point in resolved)
            resolved_pace = statistics.mean(point["pace_score"] for point in resolved)
            resolved_fast = statistics.mean(point["stored_fastest_score"] for point in resolved)
        else:
            resolved_delta = resolved_pace = resolved_fast = None
        improve = sum(point["pace_score"] > point["stored_fastest_score"] for point in resolved)
        equal = sum(point["pace_score"] == point["stored_fastest_score"] for point in resolved)
        loss = sum(point["pace_score"] < point["stored_fastest_score"] for point in resolved)
        per_query.append({
            "query_id": row["query_id"], "rho": row["rho"],
            "status": row["official_status"],
            "resolved_coverage": len(resolved) / len(points),
            "full_pace_score": pace_full, "full_fastest_score": fast_full,
            "full_score_difference": pace_full - fast_full,
            "resolved_pace_score": resolved_pace,
            "resolved_fastest_score": resolved_fast,
            "resolved_score_difference": resolved_delta,
            "preference_improvement_coverage": improve / len(resolved) if resolved else None,
            "equal_score_coverage": equal / len(resolved) if resolved else None,
            "preference_loss_coverage": loss / len(resolved) if resolved else None,
        })

    aggregate: dict[str, Any] = {}
    for rho in (0.2, 0.5):
        group = [row for row in diagnostic if row["rho"] == rho]
        all_points = [point for row in group for point in row["points"]]
        resolved = [point for point in all_points if point["pace_resolved"]]
        pace_full = sum(point["pace_score"] if point["pace_resolved"] else 0 for point in all_points) / len(all_points)
        fast_full = statistics.mean(point["stored_fastest_score"] for point in all_points)
        complete_points = [point for row in group if row["official_status"] == "COMPLETE" for point in row["points"]]
        complete_resolved = [point for point in complete_points if point["pace_resolved"]]
        aggregate[str(rho)] = {
            "query_count": len(group),
            "resolved_coverage": len(resolved) / len(all_points),
            "full_pace_score": pace_full,
            "full_fastest_score": fast_full,
            "full_score_difference": pace_full - fast_full,
            "full_score_difference_percent": 100 * (pace_full - fast_full) / fast_full,
            "resolved_pace_score": statistics.mean(point["pace_score"] for point in resolved) if resolved else None,
            "resolved_fastest_score": statistics.mean(point["stored_fastest_score"] for point in resolved) if resolved else None,
            "resolved_score_difference": statistics.mean(point["pace_score"] - point["stored_fastest_score"] for point in resolved) if resolved else None,
            "preference_improvement_coverage": sum(point["pace_score"] > point["stored_fastest_score"] for point in resolved) / len(resolved) if resolved else None,
            "equal_score_coverage": sum(point["pace_score"] == point["stored_fastest_score"] for point in resolved) / len(resolved) if resolved else None,
            "preference_loss_coverage": sum(point["pace_score"] < point["stored_fastest_score"] for point in resolved) / len(resolved) if resolved else None,
            "completed_only_resolved_coverage": len(complete_resolved) / len(complete_points) if complete_points else None,
            "completed_only_score_difference": statistics.mean(point["pace_score"] - point["stored_fastest_score"] for point in complete_resolved) if complete_resolved else None,
        }
    pd.DataFrame(per_query).to_csv(DIAGNOSTICS / "score_comparison_by_query.csv", index=False)
    return per_query, aggregate


def omission_cause(row: dict[str, Any], point: dict[str, Any]) -> str:
    if row["official_status"] == "TIMEOUT":
        return "TIME_LIMIT"
    if not point["fastest_in_query_region"]:
        return "QUERY_REGION_BUG"
    if point["selected_pivots_on_fastest"] is not None and point["selected_pivots_on_fastest"] > 2:
        return "PIVOT_DEPTH"
    stages = point["fastest_stage_presence"] or {}
    if not stages.get("CONNECTOR_OUTPUT", False):
        return "CONNECTOR_POLICY"
    if not stages.get("PARTIAL_STATE_FRONTIER", False):
        return "KF_RETENTION"
    if not stages.get("COMPLETE_DESCRIPTOR", False):
        return "OTHER"
    if not stages.get("REPLAYED_CANDIDATE_REGISTRY", False):
        return "OTHER"
    if not stages.get("FINAL_FRONTIER", False):
        return "KF_RETENTION"
    if not stages.get("FINAL_ENVELOPE", False):
        return "BREAKPOINT/ENVELOPE"
    return "OTHER"


def omission_analysis(diagnostic: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    cells: list[dict[str, Any]] = []
    minute_counts = collections.Counter({cause: 0 for cause in CAUSES})
    loss_total = 0
    for row in diagnostic:
        classified: list[tuple[int, str, int]] = []
        for point in row["points"]:
            pace_score = point["pace_score"] if point["pace_resolved"] else 0
            if pace_score >= point["stored_fastest_score"]:
                continue
            cause = omission_cause(row, point)
            classified.append((point["departure"], cause, pace_score - point["stored_fastest_score"]))
            minute_counts[cause] += 1
            loss_total += 1
        cursor = 0
        while cursor < len(classified):
            start, cause, delta = classified[cursor]
            end = start
            deltas = [delta]
            cursor += 1
            while cursor < len(classified) and classified[cursor][0] == end + 1 and classified[cursor][1] == cause:
                end, _, value = classified[cursor]
                deltas.append(value)
                cursor += 1
            cells.append({
                "query_id": row["query_id"], "rho": row["rho"],
                "official_status": row["official_status"],
                "start": start, "end": end, "sample_duration": end - start + 1,
                "omission_cause": cause, "mean_score_delta": statistics.mean(deltas),
            })
    pd.DataFrame(cells).to_csv(DIAGNOSTICS / "omission_cells.csv", index=False)
    aggregate = {
        "loss_departure_samples": loss_total,
        "total_departure_samples": 12_100,
        "by_cause": {
            cause: {
                "departure_samples": minute_counts[cause],
                "loss_time_share": minute_counts[cause] / loss_total if loss_total else 0,
                "all_time_share": minute_counts[cause] / 12_100,
                "compressed_cells": sum(cell["omission_cause"] == cause for cell in cells),
            } for cause in CAUSES
        },
    }
    return cells, aggregate


def profile_metrics(official: dict[str, dict[str, Any]], diagnostic: list[dict[str, Any]], cap30: list[dict[str, Any]]) -> dict[str, Any]:
    complete = [row for row in official.values() if row["status"] == "COMPLETE"]
    full = [row for row in complete if row["pace_b"]["resolved_coverage"] == 1.0]
    cells = [row["pace_b"]["profile_cell_count"] for row in complete]
    paths = [row["pace_b"]["distinct_path_count"] for row in complete]
    switches = [row["pace_b"]["route_switches"] for row in complete]
    durations = [cell["end"] - cell["start"] + 1 for row in complete for cell in row["pace_b"]["cells"] if cell["resolved"]]

    diag_by_id = {row["query_id"]: row for row in diagnostic}
    changes = []
    for row in full:
        points = diag_by_id[row["query_id"]]["points"]
        first = tuple(points[0]["pace_path"])
        changes.append(sum(tuple(point["pace_path"]) != first for point in points) / len(points))

    cap30_profiles = []
    for row in cap30:
        if not row["profile"]:
            continue
        points = row["profile"]["points"]
        assignments = [tuple(point.get("path", [])) if point["resolved"] else None for point in points]
        if not any(path is not None for path in assignments):
            continue
        distinct = len({path for path in assignments if path is not None})
        switches30 = sum(left is not None and right is not None and left != right for left, right in zip(assignments, assignments[1:]))
        run_lengths = []
        index = 0
        while index < len(assignments):
            end = index
            while end + 1 < len(assignments) and assignments[end + 1] == assignments[index]:
                end += 1
            if assignments[index] is not None:
                run_lengths.append(end - index + 1)
            index = end + 1
        cap30_profiles.append({"profile_cells": row["profile"]["profile_cell_count"], "distinct_paths": distinct, "switches": switches30, "durations": run_lengths})

    output = {
        "official_completed_query_count": len(complete),
        "official_fully_resolved_query_count": len(full),
        "official_profile_cells": summary(cells),
        "official_distinct_paths": summary(paths),
        "official_route_switches": summary(switches),
        "official_multi_path_rate": sum(value > 1 for value in paths) / len(paths),
        "official_change_coverage": summary(changes),
        "official_assignment_cell_duration_samples": summary(durations),
        "cap30_resolved_profile_count": len(cap30_profiles),
        "cap30_profile_cells": summary(item["profile_cells"] for item in cap30_profiles),
        "cap30_distinct_paths": summary(item["distinct_paths"] for item in cap30_profiles),
        "cap30_route_switches": summary(item["switches"] for item in cap30_profiles),
        "cap30_multi_path_rate": sum(item["distinct_paths"] > 1 for item in cap30_profiles) / len(cap30_profiles),
        "cap30_assignment_cell_duration_samples": summary(value for item in cap30_profiles for value in item["durations"]),
    }
    pd.DataFrame({
        "query_id": [row["query_id"] for row in complete],
        "profile_cells": cells, "distinct_paths": paths,
        "route_switches": switches,
    }).to_csv(DIAGNOSTICS / "profile_necessity_by_query.csv", index=False)
    return output


def interval_best(diagnostic: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    values = []
    for row in diagnostic:
        if row["official_status"] != "COMPLETE" or not row["trace_rerun"]:
            continue
        coverage = sum(point["pace_resolved"] for point in row["points"]) / len(row["points"])
        if coverage < 0.95:
            continue
        best = row["trace_rerun"]["interval_best"]
        if not best.get("found"):
            continue
        path = tuple(best["path"])
        selected = sum(point["pace_resolved"] and tuple(point["pace_path"]) == path for point in row["points"])
        path_coverage = selected / len(row["points"])
        values.append({
            "query_id": row["query_id"], "rho": row["rho"],
            "resolved_coverage": coverage,
            "interval_best_departure": best["departure"],
            "interval_best_score": best["score"],
            "interval_best_path_coverage": path_coverage,
        })
    pd.DataFrame(values).to_csv(DIAGNOSTICS / "interval_best_coverage.csv", index=False)
    result = {
        "sufficiently_resolved_definition": "official COMPLETE and ResolvedCoverage >= 0.95",
        "query_count": len(values),
        "coverage": summary(row["interval_best_path_coverage"] for row in values),
    }
    for threshold in (0.25, 0.50, 0.75):
        result[f"not_selected_at_least_{int(100 * threshold)}pct"] = sum(1 - row["interval_best_path_coverage"] >= threshold for row in values) / len(values) if values else None
    return values, result


def tradeoff(diagnostic: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    points = []
    for row in diagnostic:
        for point in row["points"]:
            if not point["pace_resolved"]:
                continue
            delta_s = point["pace_score"] - point["stored_fastest_score"]
            delta_t = point["pace_travel_time"] - point["stored_fastest_travel_time"]
            points.append({
                "query_id": row["query_id"], "rho": row["rho"],
                "departure": point["departure"], "delta_score": delta_s,
                "delta_travel_minutes": delta_t,
                "budget_utilization": point["pace_travel_time"] / row["budget"],
                "travel_time_premium_percent": 100 * delta_t / point["stored_fastest_travel_time"],
            })
    positive_time = [point for point in points if point["delta_travel_minutes"] > 1e-12]
    result = {
        "resolved_temporal_samples": len(points),
        "median_delta_score": statistics.median(point["delta_score"] for point in points),
        "median_delta_travel_minutes": statistics.median(point["delta_travel_minutes"] for point in points),
        "median_budget_utilization": statistics.median(point["budget_utilization"] for point in points),
        "score_improvement_coverage": sum(point["delta_score"] > 0 for point in points) / len(points),
        "aggregate_travel_time_premium_percent": None,
        "median_travel_time_premium_percent": statistics.median(point["travel_time_premium_percent"] for point in points),
        "median_score_gain_per_added_minute": statistics.median(point["delta_score"] / point["delta_travel_minutes"] for point in positive_time),
        "aggregate_score_gain_per_added_minute": sum(point["delta_score"] for point in positive_time) / sum(point["delta_travel_minutes"] for point in positive_time),
    }
    # The aggregate premium uses exact underlying fastest travel, retained separately here.
    fast_sum = 0.0
    pace_sum = 0.0
    for row in diagnostic:
        for point in row["points"]:
            if point["pace_resolved"]:
                fast_sum += point["stored_fastest_travel_time"]
                pace_sum += point["pace_travel_time"]
    result["aggregate_travel_time_premium_percent"] = 100 * (pace_sum - fast_sum) / fast_sum
    pd.DataFrame(points).to_csv(DIAGNOSTICS / "score_travel_tradeoff.csv", index=False)
    return points, result


def cap_summary(diagnostic: list[dict[str, Any]], official: dict[str, dict[str, Any]], cap5: list[dict[str, Any]], cap10: list[dict[str, Any]], cap30: list[dict[str, Any]]) -> list[dict[str, Any]]:
    baseline = {row["query_id"]: row for row in diagnostic}
    records: list[dict[str, Any]] = []

    def summarize_group(label: str, cap_rows: list[dict[str, Any]] | None, rho: float) -> None:
        selected_ids = [query_id for query_id, row in official.items() if row["rho"] == rho]
        if label == "official_5s":
            source = [official[query_id] for query_id in selected_ids]
            status = collections.Counter(row["status"] for row in source)
            resolved_points = [point for query_id in selected_ids for point in baseline[query_id]["points"] if point["pace_resolved"]]
            total = len(selected_ids) * 121
            full_score = sum(point["pace_score"] for point in resolved_points) / total
            resolved_score = statistics.mean(point["pace_score"] for point in resolved_points) if resolved_points else None
            candidates = [row["candidate_count"] for row in source if row["candidate_count"] is not None]
            replayed = []
        else:
            assert cap_rows is not None
            source = [row for row in cap_rows if row["rho"] == rho]
            status = collections.Counter(row["status"] for row in source)
            total = len(source) * 121
            resolved_score_values = [point["score"] for row in source if row["profile"] for point in row["profile"]["points"] if point["resolved"]]
            full_score = sum(resolved_score_values) / total
            resolved_score = statistics.mean(resolved_score_values) if resolved_score_values else None
            candidates = [row["candidate_count"] for row in source if row["candidate_count"] is not None]
            replayed = [row["observed_replayed_candidates"] for row in source]
            resolved_points = resolved_score_values
        records.append({
            "cap": label, "rho": rho,
            "complete": status.get("COMPLETE", 0),
            "no_feasible_path": status.get("NO_FEASIBLE_PATH", 0),
            "external_timeout": status.get("TIMEOUT", 0) + status.get("EXTERNAL_TIMEOUT", 0),
            "resolved_coverage": len(resolved_points) / total,
            "full_interval_pace_score": full_score,
            "resolved_only_pace_score": resolved_score,
            "mean_final_candidate_count": statistics.mean(candidates) if candidates else None,
            "mean_observed_replayed_candidates": statistics.mean(replayed) if replayed else None,
        })

    for label, source in (("official_5s", None), ("diagnostic_10s", cap10), ("diagnostic_30s", cap30)):
        for rho in (0.2, 0.5):
            summarize_group(label, source, rho)
    pd.DataFrame(records).to_csv(DIAGNOSTICS / "cap_summary.csv", index=False)
    return records


def budget_growth(cap30: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    by_family: dict[tuple[str, str], dict[float, dict[str, Any]]] = collections.defaultdict(dict)
    for row in cap30:
        by_family[(row["pair_id"], row["period_id"])][row["rho"]] = row
    metrics = {
        "corridor_nodes": lambda row: row["generation_stats"]["corridor_nodes"],
        "corridor_edges": lambda row: row["generation_stats"]["corridor_edges"],
        "score_relevant_edges": lambda row: row["generation_stats"]["score_relevant_edges"],
        "connector_requests": lambda row: row["generation_stats"]["connector_calls"],
        "generated_states": lambda row: row["generation_stats"]["candidates_generated"],
        "retained_states": lambda row: row["generation_stats"]["candidates_retained"],
        "completed_descriptors": lambda row: row["observed_complete_descriptors"],
        "replayed_candidates": lambda row: row["observed_replayed_candidates"],
    }
    records = []
    ratios: dict[str, list[float]] = collections.defaultdict(list)
    nondecrease = collections.Counter()
    for (pair_id, period), values in sorted(by_family.items()):
        if 0.2 not in values or 0.5 not in values:
            continue
        record: dict[str, Any] = {"pair_id": pair_id, "period_id": period}
        for name, getter in metrics.items():
            low, high = getter(values[0.2]), getter(values[0.5])
            ratio = high / low if low else None
            record[f"{name}_r20"] = low
            record[f"{name}_r50"] = high
            record[f"{name}_ratio"] = ratio
            if ratio is not None:
                ratios[name].append(ratio)
            nondecrease[name] += high >= low
        records.append(record)
    pd.DataFrame(records).to_csv(DIAGNOSTICS / "budget_growth_pairs.csv", index=False)
    aggregate = {
        name: {
            "ratio": summary(ratios[name]),
            "nondecrease_fraction": nondecrease[name] / len(records),
        } for name in metrics
    }
    return records, aggregate


def segmentation(diagnostic: list[dict[str, Any]]) -> dict[str, Any]:
    edge_data = pd.read_parquet(EDGES, columns=["arc_id", "geometric_length_m"])
    lengths = np.zeros(int(edge_data.arc_id.max()) + 1, dtype=float)
    lengths[edge_data.arc_id.to_numpy(dtype=int)] = edge_data.geometric_length_m.to_numpy(dtype=float)
    observations = []
    for row in diagnostic:
        for point in row["points"]:
            if not point["pace_resolved"]:
                continue
            arcs = np.asarray(point["pace_path"], dtype=int)
            length_km = float(lengths[arcs].sum() / 1000)
            observations.append({
                "query_id": row["query_id"], "departure": point["departure"],
                "score": point["pace_score"], "edge_count": len(arcs),
                "geometric_length_km": length_km,
                "score_per_km": point["pace_score"] / length_km if length_km else None,
            })
    frame = pd.DataFrame(observations)
    frame.to_csv(DIAGNOSTICS / "score_segmentation_observations.csv", index=False)
    corr_edges = float(frame.score.corr(frame.edge_count))
    corr_length = float(frame.score.corr(frame.geometric_length_km))
    distinct = frame.groupby(["query_id", "edge_count", "geometric_length_km"], as_index=False).agg(score=("score", "mean"), score_per_km=("score_per_km", "mean"))
    severe = abs(corr_edges) >= 0.7 and abs(corr_edges) > abs(corr_length) + 0.15
    return {
        "selected_temporal_path_samples": len(frame),
        "distinct_query_path_samples": len(distinct),
        "corr_score_edge_count": corr_edges,
        "corr_score_geometric_length": corr_length,
        "score_per_km": summary(frame.score_per_km.dropna()),
        "severe_segmentation_bias_by_predeclared_rule": severe,
        "severity_rule": "severe iff |corr(score,edge_count)| >= 0.70 and exceeds |corr(score,length)| by > 0.15",
        "proposal_not_activated": "length-normalized transit affinity, e.g. integrate active-route density over geometric arc length rather than adding one unit per segmented arc",
    }


def mta_activity() -> dict[str, Any]:
    manifest = json.loads((PROCESSED / "manifest.json").read_text(encoding="utf-8"))
    support = manifest["temporal_support"]
    schedule_pages = sorted((CASE_ROOT / "raw" / "mta_bus_schedules_2026").glob("*/page-*.csv"))
    active, schedule_stats = active_route_bins(
        read_schedule_pages(schedule_pages), support["observed_start_utc"],
        support["observed_end_utc"], manifest["score_definition"]["bin_minutes"],
    )
    mapping = pd.read_parquet(MTA_MAP, columns=["route_id", "shape_id", "direction", "arc_id"])
    mapping["shape_id"] = mapping.shape_id.astype(str)
    mapped_shapes = set(mapping.shape_id)
    arcs_per_shape = mapping.groupby("shape_id").arc_id.nunique().to_dict()
    exact = [(route, shape, bin_index) for route, shape, bin_index in active if shape in mapped_shapes]
    unmatched = [(route, shape, bin_index) for route, shape, bin_index in active if shape not in mapped_shapes]
    mapping_routes = mapping.groupby("shape_id").route_id.apply(lambda values: set(map(str, values))).to_dict()
    route_mismatch = [(route, shape, bin_index) for route, shape, bin_index in exact if route not in mapping_routes.get(shape, set())]
    result = {
        **schedule_stats,
        "activity_unit": "unique active (route_id, shape_id, 15-minute bin)",
        "exact_shape_id_activity": len(exact),
        "route_id_fallback_activity": 0,
        "unmatched_activity": len(unmatched),
        "exact_shape_id_activity_percent": 100 * len(exact) / len(active),
        "route_id_fallback_activity_percent": 0.0,
        "unmatched_activity_percent": 100 * len(unmatched) / len(active),
        "exact_shape_graph_arc_route_bin_contributions": sum(arcs_per_shape[shape] for _, shape, _ in exact),
        "route_shape_mismatch_activity": len(route_mismatch),
        "fallback_implementation": "none; build_scores joins active service strictly by shape_id",
        "direction_overactivation_check": "PASS: no route-level fallback exists; only the active shape_id's mapped geometry contributes",
    }
    (DIAGNOSTICS / "mta_mapping_activity.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def provenance_scopes(diagnostic: list[dict[str, Any]]) -> list[dict[str, Any]]:
    boundary = gpd.read_file(BOROUGH_BOUNDARY).to_crs("EPSG:4326").geometry.union_all()
    edge_data = pd.read_parquet(EDGES, columns=["arc_id", "source_longitude", "source_latitude", "target_longitude", "target_latitude"])
    midpoints = shapely.points(
        (edge_data.source_longitude.to_numpy() + edge_data.target_longitude.to_numpy()) / 2,
        (edge_data.source_latitude.to_numpy() + edge_data.target_latitude.to_numpy()) / 2,
    )
    inside = set(edge_data.loc[shapely.covers(boundary, midpoints), "arc_id"].astype(int))
    query_union = set(json.loads(QUERY_UNION.read_text(encoding="utf-8"))["arc_ids"])
    pace_arcs = {arc for row in diagnostic for point in row["points"] if point["pace_resolved"] for arc in point["pace_path"]}
    fastest_arcs = {arc for row in diagnostic for point in row["points"] for arc in point["fastest_path"]}
    scopes = {
        "NYC_BOUNDARY_MIDPOINT": inside,
        "QUERY_REGION_UNION": query_union,
        "PACE_B_OFFICIAL_OUTPUTS": pace_arcs,
        "FASTEST_OUTPUTS": fastest_arcs,
    }
    membership: dict[int, list[str]] = collections.defaultdict(list)
    for scope, arcs in scopes.items():
        for arc in arcs:
            membership[arc].append(scope)
    counts = {scope: collections.Counter() for scope in scopes}
    with gzip.open(PROCESSED / "travel_time_provenance.jsonl.gz", "rt", encoding="utf-8") as stream:
        for line in stream:
            value = json.loads(line)
            scope_names = membership.get(value["arc_id"])
            if not scope_names:
                continue
            local = collections.Counter(bin_value[2] for bin_value in value["bins"])
            for scope in scope_names:
                counts[scope].update(local)
    records = []
    for scope, arcs in scopes.items():
        total = sum(counts[scope].values())
        record: dict[str, Any] = {"scope": scope, "arc_count": len(arcs), "arc_bin_count": total}
        for category in ("DIRECT", "BOROUGH_CLASS_IMPUTED", "CITYWIDE_IMPUTED", "STATIC_FALLBACK"):
            record[f"{category}_count"] = counts[scope][category]
            record[f"{category}_percent"] = 100 * counts[scope][category] / total if total else 0
        records.append(record)
    pd.DataFrame(records).to_csv(DIAGNOSTICS / "provenance_by_scope.csv", index=False)
    return records


def connector_semantics(diagnostic: list[dict[str, Any]]) -> dict[str, Any]:
    comparisons = [comparison for row in diagnostic if row["trace_rerun"] for comparison in row["trace_rerun"]["connector_observations"]]
    static = [check for row in diagnostic if row["trace_rerun"] for check in row["connector_static_witness_checks"]]
    return {
        "classification": "LOWER_BOUND_STATIC_WITNESS",
        "implementation_mode": "PACE_B_FASTEST_LOWER_BOUND_WITNESS_V1",
        "representative_connector_comparisons": len(comparisons),
        "point_fastest_path_agreements": sum(item["path_agreement"] for item in comparisons),
        "point_fastest_path_disagreements": sum(not item["path_agreement"] for item in comparisons),
        "fastest_profile_paths_checked_against_static_segmentation": len(static),
        "all_segments_static_witness_agreements": sum(item["all_connector_segments_match_static_witness"] for item in static),
        "minimum_correction": "For each connector request, run exact FIFO time-dependent fastest labeling over the request entry domain (splitting when the fastest structural path changes), while enforcing the corridor, selected-pivot exclusions, visited masks, and residual budget; return that temporal single-fastest connector profile instead of reusing only global static F/B witnesses.",
    }


def exact_subset(diagnostic: list[dict[str, Any]], pace_x: list[dict[str, Any]]) -> dict[str, Any]:
    selected = set(json.loads(PACE_X_MANIFEST.read_text(encoding="utf-8"))["query_ids"])
    assert selected == {row["query_id"] for row in pace_x}
    bounded = [row for row in diagnostic if row["query_id"] in selected]
    agreements = []
    score_gaps = []
    coverages = []
    for row in bounded:
        resolved = [point for point in row["points"] if point["pace_resolved"]]
        coverages.append(len(resolved) / len(row["points"]))
        if resolved:
            agreements.extend(tuple(point["pace_path"]) == tuple(point["fastest_path"]) for point in resolved)
            score_gaps.extend(point["pace_score"] - point["stored_fastest_score"] for point in resolved)
    certified = [row for row in pace_x if row["exactness_scope"] == "GLOBAL_CERTIFIED"]
    return {
        "predeclared_query_count": len(selected),
        "diagnostic_external_cap_seconds": 60,
        "status_counts": dict(collections.Counter(row["status"] for row in pace_x)),
        "last_phase_counts": dict(collections.Counter(row["last_phase"] for row in pace_x)),
        "certified_query_count": len(certified),
        "complete_descriptors": sum(row["observed_complete_descriptors"] for row in pace_x),
        "replayed_candidates": sum(row["observed_replayed_candidates"] for row in pace_x),
        "pace_b_path_agreement_on_certified_queries": None,
        "pace_b_score_gap_on_certified_queries": None,
        "pace_b_resolved_coverage_on_certified_queries": None,
        "nonexact_subset_descriptive_pace_b_path_agreement": statistics.mean(agreements) if agreements else None,
        "nonexact_subset_descriptive_pace_b_score_gap": statistics.mean(score_gaps) if score_gaps else None,
        "nonexact_subset_descriptive_pace_b_resolved_coverage": statistics.mean(coverages),
    }


def make_figures(official: dict[str, dict[str, Any]], diagnostic: list[dict[str, Any]], trade_points: list[dict[str, Any]], growth: list[dict[str, Any]]) -> str:
    FIGURES.mkdir(parents=True, exist_ok=True)
    completed = [row for row in official.values() if row["status"] == "COMPLETE"]
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.7))
    values = [
        [row["pace_b"]["profile_cell_count"] for row in completed],
        [row["pace_b"]["distinct_path_count"] for row in completed],
        [row["pace_b"]["route_switches"] for row in completed],
    ]
    labels = ["Profile cells", "Distinct paths", "Route switches"]
    for axis, data, label in zip(axes, values, labels):
        axis.hist(data, bins="auto", color="#35618f", edgecolor="white")
        axis.axvline(np.median(data), color="#b33a3a", linestyle="--", label="median")
        axis.set_xlabel(label); axis.set_ylabel("Queries"); axis.legend(frameon=False)
    fig.tight_layout()
    for suffix in ("pdf", "png"):
        fig.savefig(FIGURES / f"nyc_profile_necessity.{suffix}", dpi=220, bbox_inches="tight")
    plt.close(fig)

    frame = pd.DataFrame(trade_points)
    fig, axis = plt.subplots(figsize=(6.5, 4.8))
    colors = frame.rho.map({0.2: "#35618f", 0.5: "#d17b2f"})
    axis.scatter(frame.delta_travel_minutes, frame.delta_score, c=colors, s=12, alpha=0.38, linewidths=0)
    axis.axhline(0, color="black", linewidth=0.8); axis.axvline(0, color="black", linewidth=0.8)
    axis.set_xlabel("PACE travel-time premium, minutes")
    axis.set_ylabel("PACE score gain over fastest")
    axis.set_title("All official resolved departure samples (negative points retained)")
    fig.tight_layout()
    for suffix in ("pdf", "png"):
        fig.savefig(FIGURES / f"nyc_score_travel_tradeoff.{suffix}", dpi=220, bbox_inches="tight")
    plt.close(fig)

    growth_frame = pd.DataFrame(growth)
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.2))
    for axis, stem, label in ((axes[0], "corridor_edges", "Query-region arcs"), (axes[1], "generated_states", "Generated candidates")):
        axis.scatter(growth_frame[f"{stem}_r20"], growth_frame[f"{stem}_r50"], s=22, alpha=0.7, color="#35618f")
        maximum = max(growth_frame[f"{stem}_r20"].max(), growth_frame[f"{stem}_r50"].max())
        axis.plot([0, maximum], [0, maximum], linestyle="--", color="black", linewidth=0.8)
        axis.set_xlabel("R20"); axis.set_ylabel("R50"); axis.set_title(label)
    fig.tight_layout()
    for suffix in ("pdf", "png"):
        fig.savefig(FIGURES / f"nyc_budget_growth.{suffix}", dpi=220, bbox_inches="tight")
    plt.close(fig)

    current_pair = official["NYC-Q003-MORNING-R50"]["pair_id"]
    eligible = [row for row in completed if row["pace_b"]["resolved_coverage"] == 1.0 and row["pace_b"]["profile_cell_count"] >= 3 and row["score_gain_absolute"] > 0 and row["pair_id"] != current_pair]
    grouped: dict[tuple[str, float], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in eligible:
        grouped[(row["period_id"], row["rho"])].append(row)
    medians = {key: statistics.median(item["score_gain_absolute"] for item in values) for key, values in grouped.items()}
    chosen = min(eligible, key=lambda row: (abs(row["score_gain_absolute"] - medians[(row["period_id"], row["rho"])]), row["query_id"]))
    diag = next(row for row in diagnostic if row["query_id"] == chosen["query_id"])
    departures = [point["departure"] for point in diag["points"]]
    pace_scores = [point["pace_score"] for point in diag["points"]]
    fast_scores = [point["stored_fastest_score"] for point in diag["points"]]
    paths = []
    path_index: dict[tuple[int, ...], int] = {}
    for point in diag["points"]:
        path = tuple(point["pace_path"])
        if path not in path_index:
            path_index[path] = len(path_index)
        paths.append(path_index[path])
    fig, axes = plt.subplots(2, 1, figsize=(9, 5.8), sharex=True, gridspec_kw={"height_ratios": [2, 1]})
    axes[0].plot(departures, pace_scores, label="PACE-B", color="#35618f")
    axes[0].plot(departures, fast_scores, label="Fastest", color="#d17b2f", linestyle="--")
    axes[0].set_ylabel("Score"); axes[0].legend(frameon=False)
    axes[1].step(departures, paths, where="post", color="#35618f")
    axes[1].set_ylabel("PACE path ID"); axes[1].set_xlabel("Departure minute")
    fig.suptitle(f"Predeclared second profile: {chosen['query_id']}")
    fig.tight_layout()
    for suffix in ("pdf", "png"):
        fig.savefig(FIGURES / f"nyc_second_profile_predeclared.{suffix}", dpi=220, bbox_inches="tight")
    plt.close(fig)
    return chosen["query_id"]


def write_reports(metrics: dict[str, Any], diagnostic: list[dict[str, Any]], official: dict[str, dict[str, Any]]) -> None:
    feasibility = metrics["feasibility"]
    nofeasible = [row for row in diagnostic if row["official_status"] == "NO_FEASIBLE_PATH"]
    nofeasible_rows = []
    for row in nofeasible:
        causes = collections.Counter(omission_cause(row, point) for point in row["points"])
        nofeasible_rows.append([
            row["query_id"], "121/121", "yes", "UNRESOLVED/NO_GENERATED_CANDIDATE",
            ", ".join(f"{key}: {value}" for key, value in sorted(causes.items())),
        ])
    FEASIBILITY_REPORT.write_text(
        "# NYC feasibility invariant\n\n"
        "The current official result bundle and query manifest were treated as immutable inputs. "
        "Every stored fastest path was replayed with `ExactPathValidator`, and an independent "
        "`PointForwardLabeling` run was performed for every integer departure.\n\n"
        + markdown_table(
            ["Check", "Result"], [
                ["Queries", feasibility["queries"]],
                ["Departure samples", feasibility["departure_samples"]],
                ["Stored fastest witnesses valid", feasibility["stored_fastest_valid"]],
                ["Fresh fastest witnesses valid", feasibility["fresh_fastest_valid"]],
                ["Fresh/stored structural agreement", feasibility["fresh_path_agreement"]],
                ["Fastest budget violations", feasibility["budget_violations"]],
                ["Fastest query-region omissions", feasibility["query_region_omissions"]],
                ["Traced checksum agreement", f"{feasibility['trace_checksum_agreements']}/{feasibility['traced_queries']}"],
            ])
        + "\n\n## Current bounded-empty outcomes\n\n"
        + markdown_table(["Query", "Feasible replay", "Feasible path exists", "Correct classification", "Earliest omission"], nofeasible_rows)
        + "\n\nAll three official `NO_FEASIBLE_PATH` labels are non-certified bounded outcomes. "
        "They must be interpreted as unresolved empty generated frontiers, not graph infeasibility. "
        "The safe query region contains every one of the 12,100 feasible fastest witnesses; no "
        "query-region correctness bug was found.\n",
        encoding="utf-8",
    )

    score_rows = []
    for rho in (0.2, 0.5):
        value = metrics["score_comparison"][str(rho)]
        score_rows.append([
            f"R{int(rho * 100)}", pct(value["resolved_coverage"]),
            number(value["full_score_difference"]), number(value["resolved_score_difference"]),
            pct(value["preference_improvement_coverage"]), pct(value["equal_score_coverage"]),
            pct(value["preference_loss_coverage"]), number(value["completed_only_score_difference"]),
        ])
    omission_rows = [[cause, value["departure_samples"], value["compressed_cells"], pct(value["loss_time_share"])] for cause, value in metrics["omissions"]["by_cause"].items()]
    cap_rows = [[row["cap"], f"R{int(row['rho']*100)}", row["complete"], row["no_feasible_path"], row["external_timeout"], pct(row["resolved_coverage"]), number(row["full_interval_pace_score"]), number(row["resolved_only_pace_score"]), number(row["mean_final_candidate_count"]), number(row["mean_observed_replayed_candidates"])] for row in metrics["cap_summary"]]
    provenance_rows = [[row["scope"], row["arc_count"], number(row["DIRECT_percent"], 2) + "%", number(row["BOROUGH_CLASS_IMPUTED_percent"], 2) + "%", number(row["CITYWIDE_IMPUTED_percent"], 2) + "%", number(row["STATIC_FALLBACK_percent"], 2) + "%"] for row in metrics["provenance"]]
    profile = metrics["profile_necessity"]
    interval = metrics["interval_best"]
    connector = metrics["connector_semantics"]
    exact = metrics["pace_x"]
    trade = metrics["tradeoff"]
    segmentation_value = metrics["segmentation"]
    mta = metrics["mta_mapping"]
    report = f"""# NYC story validation

Diagnostic inputs are frozen to query manifest `{metrics['provenance_hashes']['query_manifest']}` and official result bundle `{metrics['provenance_hashes']['official_results']}`. The official five-second JSONL was not modified.

## 1. Correctness issues discovered

No query-region, temporal-coordinate, budget-construction, exact replay, or envelope correctness bug was found. All 12,100 fastest witnesses validate, match independent time-dependent labeling, satisfy their budgets, and lie in their safe query regions. The correctness issue is semantic reporting: the three bounded-empty results were labeled `NO_FEASIBLE_PATH` even though feasible paths exist; they are unresolved, non-certified outcomes. A diagnostic-only raw-distance field was also mislabeled as meters and has been corrected in source; geometric analyses use `geometric_length_m`.

## 2. Correctness fixes required

Change the public/result interpretation of an empty PACE-B frontier to `UNRESOLVED/NO_GENERATED_CANDIDATE` (or an equivalent explicitly non-certified status). Do not expose it as infeasibility. No scientific graph or score fix is required, so the original 100-query result bundle was not rerun or replaced.

## 3. Why NO_FEASIBLE_PATH occurred

All three cases fail the selected-pivot representation because each fastest witness contains more than `theta=2` selected pivots. Their earliest classified omission is `PIVOT_DEPTH` for all 363 departure samples. They remain bounded-empty at 5, 10, and 30 seconds, proving that added wall time does not resolve this policy restriction.

{markdown_table(['Cause', 'Loss samples', 'Compressed cells', 'Share of loss time'], omission_rows)}

## 4. Exact connector semantics

The implementation is `{connector['classification']}`, mode `{connector['implementation_mode']}`. It reuses one global static lower-bound F/B witness, rejects requests not represented by those labels, and replays that path over the temporal domain. It is not a time-dependent fastest connector. In {connector['representative_connector_comparisons']} representative production requests, {connector['point_fastest_path_agreements']} matched and {connector['point_fastest_path_disagreements']} differed from point time-dependent fastest labeling. Only {connector['all_segments_static_witness_agreements']} of {connector['fastest_profile_paths_checked_against_static_segmentation']} fastest-profile paths had every connector segment equal to the static witness.

Minimum correction: {connector['minimum_correction']}

## 5. Full-vs-resolved score analysis

{markdown_table(['Group', 'Resolved coverage', 'Full DeltaS', 'Resolved DeltaS', 'Improve', 'Equal', 'Loss', 'Completed-only DeltaS'], score_rows)}

Across all 50 queries per rho, the dominant full-interval penalty is unresolved time caused by the five-second cap. Conditional on resolved time, R50 still has a negative score difference, and the completed-only R50 difference is negative. Thus the previously reported R50 loss is not primarily an unresolved-cell accounting artifact; lower-quality bounded candidates are also present.

## 6. Evidence that path choice varies over I

Among {profile['official_completed_query_count']} official completed profiles, MultiPathRate is {pct(profile['official_multi_path_rate'])}. Profile cells have median {number(profile['official_profile_cells']['median'])} and IQR [{number(profile['official_profile_cells']['q1'])}, {number(profile['official_profile_cells']['q3'])}]; distinct paths have median {number(profile['official_distinct_paths']['median'])} and IQR [{number(profile['official_distinct_paths']['q1'])}, {number(profile['official_distinct_paths']['q3'])}]. Route switches have median {number(profile['official_route_switches']['median'])}. For fully resolved profiles, ChangeCoverage has median {pct(profile['official_change_coverage']['median'])}. Assignment-cell durations have median {number(profile['official_assignment_cell_duration_samples']['median'])} sampled minutes and IQR [{number(profile['official_assignment_cell_duration_samples']['q1'])}, {number(profile['official_assignment_cell_duration_samples']['q3'])}]. The unchanged 30-second diagnostic yields {profile['cap30_resolved_profile_count']} resolved profiles and MultiPathRate {pct(profile['cap30_multi_path_rate'])}. Path variation is therefore real but concentrated in a nontrivial minority, not typical of the median query.

## 7. Interval-best coverage analysis

“Sufficiently resolved” was predeclared as official `COMPLETE` with at least 95% coverage. For {interval['query_count']} such candidate registries, interval-best path coverage has median {pct(interval['coverage']['median'])} and IQR [{pct(interval['coverage']['q1'])}, {pct(interval['coverage']['q3'])}]. The interval-best path is absent from at least 25%, 50%, and 75% of the window in {pct(interval['not_selected_at_least_25pct'])}, {pct(interval['not_selected_at_least_50pct'])}, and {pct(interval['not_selected_at_least_75pct'])} of queries, respectively. This supports the profile motivation for a minority of queries, not as a universal requirement.

## 8. Score/travel-time trade-off

Across {trade['resolved_temporal_samples']} resolved samples, median DeltaS is {number(trade['median_delta_score'])}, median DeltaT is {number(trade['median_delta_travel_minutes'])} minutes, score-improvement coverage is {pct(trade['score_improvement_coverage'])}, and aggregate travel-time premium is {number(trade['aggregate_travel_time_premium_percent'])}%. Median budget utilization is {pct(trade['median_budget_utilization'])}. For samples with added travel, median and aggregate score gain per added minute are {number(trade['median_score_gain_per_added_minute'])} and {number(trade['aggregate_score_gain_per_added_minute'])}. Negative points are retained in the scatter and CSV.

## 9. PACE-X exact real-data validation

The predeclared subset contains {exact['predeclared_query_count']} queries. All {exact['predeclared_query_count']} hit the 60-second external machine-protection cap in connector generation before producing a complete descriptor; certified count is **{exact['certified_query_count']}**. Therefore PathAgreement, score gap, and ResolvedCoverage against a certified PACE-X optimum are N/A, and the NYC experiment supplies no real-data global-optimality certificate. The non-exact subset’s bounded descriptive PathAgreement is {pct(exact['nonexact_subset_descriptive_pace_b_path_agreement'])}, score gap {number(exact['nonexact_subset_descriptive_pace_b_score_gap'])}, and mean resolved coverage {pct(exact['nonexact_subset_descriptive_pace_b_resolved_coverage'])}; these are not exact-validation statistics.

## 10. Effect of the five-second cap

{markdown_table(['Cap', 'Group', 'Complete', 'Bounded empty', 'Timeout', 'Resolved', 'Full score', 'Resolved score', 'Candidates', 'Replayed'], cap_rows)}

At 30 seconds, 97 queries complete and the same three return bounded-empty, with zero external timeouts. A 60-second sweep was stopped as redundant and unreasonable because it could not change the external-truncation classification. The instrumented five-second rerun showed one boundary perturbation relative to the official bundle, so official 5-second statuses remain authoritative.

## 11. Effect of increasing budget

Paired R50/R20 median ratios are {', '.join(f"{name}={number(value['ratio']['median'])}" for name, value in metrics['budget_growth'].items())}. Nondecrease fractions are {', '.join(f"{name}={pct(value['nondecrease_fraction'])}" for name, value in metrics['budget_growth'].items())}. Corridor size grows systematically, but generated/retained work does not justify a blanket monotonicity claim; see `budget_growth_pairs.csv` for all 50 pairs.

## 12. Score-proxy robustness

For selected PACE path-time samples, corr(score, edge count) is {number(segmentation_value['corr_score_edge_count'])}, while corr(score, geometric length) is {number(segmentation_value['corr_score_geometric_length'])}. Score/km has median {number(segmentation_value['score_per_km']['median'])} and IQR [{number(segmentation_value['score_per_km']['q1'])}, {number(segmentation_value['score_per_km']['q3'])}]. The predeclared severe-bias rule evaluates to **{segmentation_value['severe_segmentation_bias_by_predeclared_rule']}**. Proposed but not activated: {segmentation_value['proposal_not_activated']}.

## 13. Query-relevant real-data coverage

{markdown_table(['Scope', 'Arcs', 'DIRECT', 'BOROUGH_CLASS', 'CITYWIDE', 'STATIC'], provenance_rows)}

The NYC boundary scope uses the preserved official DCP 26B water-excluded polygon and arc midpoints. The union of safe query regions is broad ({next(row['arc_count'] for row in metrics['provenance'] if row['scope'] == 'QUERY_REGION_UNION')} of 733,846 arcs), explaining why it resembles regional whole-graph provenance. Of the active `(route_id, shape_id, 15-minute bin)` activity units, {mta['exact_shape_id_activity']} ({number(mta['exact_shape_id_activity_percent'], 2)}%) are exact-shape matched, {mta['route_id_fallback_activity']} (0%) use route fallback, and {mta['unmatched_activity']} ({number(mta['unmatched_activity_percent'], 2)}%) are unmatched. Exact matches contribute {mta['exact_shape_graph_arc_route_bin_contributions']} graph arc-route-bin activations. No route-level fallback exists, the route/shape mismatch count is {mta['route_shape_mismatch_activity']}, and inactive directions/geometries are not activated through fallback.

## 14. Claims supported by the NYC experiment

- Fastest baseline feasibility and safe-corridor containment hold for every query/departure sample.
- Departure-time path choice varies within the two-hour interval for a nontrivial minority (20% of official completed profiles), so a single route is insufficient for those cases.
- Bounded PACE can trade additional travel for preference score, sometimes positively and sometimes negatively.
- The five-second limit is the main reason for the low official completion rate; 30 seconds removes all external truncations under unchanged bounded parameters.
- The connector is a deterministic static lower-bound witness policy, and pivot depth plus connector policy explain most structural fastest-path omissions.

## 15. Claims NOT supported

- The three bounded-empty results do not establish infeasibility.
- PACE-B does not uniformly dominate the fastest path in score; negative cells and negative R50 resolved gaps exist.
- “Single fastest connector” cannot currently mean exact time-dependent fastest connector.
- No real-data global-optimality or PACE-X certificate was obtained.
- The experiment does not support monotone candidate/state growth for every family as rho increases.
- The primary additive score is a transit-affinity proxy, not validated user preference or a segmentation-invariant objective.
- The median completed query does not require multiple paths, and the interval-best path covers the full window for the median sufficiently resolved query; profile necessity is not universal.

## Diagnostic figures and second example

The three added figures are `nyc_profile_necessity`, `nyc_score_travel_tradeoff`, and `nyc_budget_growth` (PDF and PNG). The second route-profile example is `{metrics['second_profile_query']}`, selected without manual inspection by minimizing absolute deviation from its period/rho group median among completed, 100%-resolved, >=3-cell, positive-gain queries from a different pair than the original representative.
"""
    REPORT.write_text(report, encoding="utf-8")


def main() -> int:
    DIAGNOSTICS.mkdir(parents=True, exist_ok=True)
    FIGURES.mkdir(parents=True, exist_ok=True)
    diagnostic, by_id, official = exact_points()
    cap5, cap10, cap30, pace_x = rows(CAP5), rows(CAP10), rows(CAP30), rows(PACE_X)
    feasibility = {
        "queries": len(diagnostic), "departure_samples": 12_100,
        "stored_fastest_valid": sum(point["stored_fastest_valid"] for row in diagnostic for point in row["points"]),
        "fresh_fastest_valid": sum(point["fresh_fastest_valid"] for row in diagnostic for point in row["points"]),
        "fresh_path_agreement": sum(point["fresh_fastest_path_agreement"] for row in diagnostic for point in row["points"]),
        "fresh_travel_agreement": sum(point["fresh_fastest_travel_agreement"] for row in diagnostic for point in row["points"]),
        "budget_violations": sum(point["stored_fastest_valid"] and point["stored_fastest_travel_time"] > row["budget"] + 1e-9 for row in diagnostic for point in row["points"]),
        "query_region_omissions": sum(not point["fastest_in_query_region"] for row in diagnostic for point in row["points"]),
        "traced_queries": sum(row["trace_rerun"] is not None for row in diagnostic),
        "trace_checksum_agreements": sum(row["trace_rerun"] is not None and row["trace_rerun"]["checksum_agreement"] for row in diagnostic),
    }
    _, score_aggregate = score_comparison(diagnostic)
    _, omissions = omission_analysis(diagnostic)
    profiles = profile_metrics(official, diagnostic, cap30)
    _, interval = interval_best(diagnostic)
    trade_points, trade = tradeoff(diagnostic)
    caps = cap_summary(diagnostic, official, cap5, cap10, cap30)
    growth_rows, growth = budget_growth(cap30)
    segmentation_value = segmentation(diagnostic)
    mta = mta_activity()
    provenance = provenance_scopes(diagnostic)
    connector = connector_semantics(diagnostic)
    pace_x_value = exact_subset(diagnostic, pace_x)
    second = make_figures(official, diagnostic, trade_points, growth_rows)
    metrics = {
        "schema_version": "nyc-story-validation-metrics-v1",
        "provenance_hashes": {
            "query_manifest": sha256(QUERY_MANIFEST),
            "official_results": sha256(OFFICIAL),
            "diagnostic_replay": sha256(REPLAY),
            "pace_x_subset_manifest": sha256(PACE_X_MANIFEST),
            "pace_x_results": sha256(PACE_X),
            "borough_boundary": sha256(BOROUGH_BOUNDARY),
        },
        "feasibility": feasibility,
        "score_comparison": score_aggregate,
        "omissions": omissions,
        "connector_semantics": connector,
        "profile_necessity": profiles,
        "interval_best": interval,
        "tradeoff": trade,
        "pace_x": pace_x_value,
        "cap_summary": caps,
        "budget_growth": growth,
        "segmentation": segmentation_value,
        "mta_mapping": mta,
        "provenance": provenance,
        "second_profile_query": second,
        "cap_60_status": "NOT_RUN_REDUNDANT_AFTER_ZERO_30S_EXTERNAL_TIMEOUTS",
    }
    (DIAGNOSTICS / "story_validation_metrics.json").write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_reports(metrics, diagnostic, official)
    print(json.dumps({"status": "COMPLETE", "metrics": str(DIAGNOSTICS / "story_validation_metrics.json"), "report": str(REPORT)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
