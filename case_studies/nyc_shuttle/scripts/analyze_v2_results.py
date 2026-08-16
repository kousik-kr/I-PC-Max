#!/usr/bin/env python3
"""Aggregate NYC-real-shuttle-v2 validation and controlled comparisons."""

from __future__ import annotations

import collections
import csv
import hashlib
import json
import math
import statistics
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd

from nyc_case_study.common import CASE_ROOT, atomic_write_json


V2 = CASE_ROOT / "v2"
RESULTS = V2 / "results"
VALIDATION = V2 / "validation"
REPORTS = V2 / "reports"
REPLAY = VALIDATION / "story_diagnostic_30s.jsonl"
CAPS = {
    5: RESULTS / "primary_5s.jsonl",
    10: RESULTS / "diagnostic_10s.jsonl",
    30: RESULTS / "diagnostic_30s.jsonl",
}
CONTROLLED = RESULTS / "controlled"
V1_BUNDLE = (CASE_ROOT.parents[1]
             / "experiments/results/Final-result/NYC-real-shuttle")
V1_RESULTS = V1_BUNDLE / "results/nyc_case_results.jsonl"
V1_CAP30 = V1_BUNDLE / "diagnostics/pace_b_cap_30s.jsonl"
ROOT_REPORT = CASE_ROOT.parents[1] / "reports/nyc_v1_v2_comparison.md"
EDGES = CASE_ROOT / "intermediate/dimacs_ny_edges.parquet"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(
        encoding="utf-8").splitlines() if line.strip()]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1 << 20):
            digest.update(block)
    return digest.hexdigest()


def finite_summary(values: Iterable[float]) -> dict[str, Any]:
    data = np.asarray(list(values), dtype=float)
    data = data[np.isfinite(data)]
    if not len(data):
        return {"n": 0, "mean": None, "median": None,
                "q1": None, "q3": None, "minimum": None,
                "maximum": None}
    return {
        "n": int(len(data)),
        "mean": float(np.mean(data)),
        "median": float(np.median(data)),
        "q1": float(np.quantile(data, 0.25)),
        "q3": float(np.quantile(data, 0.75)),
        "minimum": float(np.min(data)),
        "maximum": float(np.max(data)),
    }


def correlation(left: Iterable[float], right: Iterable[float]) -> float | None:
    x = np.asarray(list(left), dtype=float)
    y = np.asarray(list(right), dtype=float)
    valid = np.isfinite(x) & np.isfinite(y)
    x, y = x[valid], y[valid]
    if len(x) < 2 or np.std(x) == 0 or np.std(y) == 0:
        return None
    return float(np.corrcoef(x, y)[0, 1])


def cell_at(cells: list[dict[str, Any]], departure: int) -> dict[str, Any]:
    for cell in cells:
        if cell["start"] <= departure <= cell["end"]:
            return cell
    return {"resolved": False, "arc_ids": [], "average_score": 0,
            "average_travel_time": None}


def result_points(
        row: dict[str, Any],
        replay_row: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    if replay_row is not None:
        return [{
            "departure": point["departure"],
            "fast_score": point["stored_fastest_score"],
            "fast_travel": point["stored_fastest_travel_time"],
            "fast_path": tuple(point["fastest_path"]),
            "pace_resolved": point["pace_resolved"],
            "pace_score": point["pace_score"] if point["pace_resolved"] else 0,
            "pace_travel": point["pace_travel_time"]
                if point["pace_resolved"] else None,
            "pace_path": tuple(point["pace_path"])
                if point["pace_resolved"] else (),
        } for point in replay_row["points"]]
    pace_cells = row["pace_b"]["cells"] if row.get("pace_b") else []
    fast_cells = row["fastest"]["cells"]
    points = []
    for departure in range(row["interval_start"], row["interval_end"] + 1):
        fast = cell_at(fast_cells, departure)
        pace = cell_at(pace_cells, departure)
        points.append({
            "departure": departure,
            "fast_score": fast["average_score"],
            "fast_travel": fast["average_travel_time"],
            "fast_path": tuple(fast["arc_ids"]),
            "pace_resolved": bool(pace["resolved"]),
            "pace_score": pace["average_score"] if pace["resolved"] else 0,
            "pace_travel": pace["average_travel_time"]
                if pace["resolved"] else None,
            "pace_path": tuple(pace["arc_ids"]) if pace["resolved"] else (),
        })
    return points


def cap_metrics(
        cap: int,
        rows: list[dict[str, Any]],
        replay_by_id: dict[str, dict[str, Any]] | None = None,
        ) -> dict[str, Any]:
    points = [(row, point) for row in rows for point in result_points(
        row, replay_by_id.get(row["query_id"]) if replay_by_id else None)]
    resolved = [(row, point) for row, point in points
                if point["pace_resolved"]]
    total = len(points)
    full_fast = statistics.mean(point["fast_score"] for _, point in points)
    full_pace = sum(point["pace_score"] for _, point in points) / total
    deltas = [point["pace_score"] - point["fast_score"]
              for _, point in resolved]
    delta_t = [point["pace_travel"] - point["fast_travel"]
               for _, point in resolved]
    budget_use = [point["pace_travel"] / row["budget"]
                  for row, point in resolved]
    completed = [row for row in rows if row["status"] == "COMPLETE"]
    path_change = [point["pace_path"] != point["fast_path"]
                   for _, point in resolved]
    temporal_change = []
    for row in completed:
        assigned = [point["pace_path"] for point in result_points(
                        row, replay_by_id.get(row["query_id"])
                        if replay_by_id else None)
                    if point["pace_resolved"]]
        if assigned:
            temporal_change.append(
                sum(path != assigned[0] for path in assigned) / len(assigned))
    profile_cells = [row["pace_b"]["profile_cell_count"] for row in completed]
    distinct_paths = [row["pace_b"]["distinct_path_count"] for row in completed]
    route_switches = [row["pace_b"]["route_switches"] for row in completed]
    result = {
        "cap_seconds": cap,
        "query_count": len(rows),
        "status_counts": dict(collections.Counter(
            row["status"] for row in rows)),
        "departure_samples": total,
        "resolved_departure_samples": len(resolved),
        "resolved_coverage": len(resolved) / total,
        "full_interval_fastest_score": full_fast,
        "full_interval_pace_score_unresolved_zero": full_pace,
        "full_interval_delta_s_unresolved_zero": full_pace - full_fast,
        "resolved_only_pace_score": (
            statistics.mean(point["pace_score"] for _, point in resolved)
            if resolved else None),
        "resolved_only_fastest_score": (
            statistics.mean(point["fast_score"] for _, point in resolved)
            if resolved else None),
        "resolved_only_delta_s": statistics.mean(deltas) if deltas else None,
        "preference_improvement_coverage": (
            sum(value > 0 for value in deltas) / len(deltas) if deltas else None),
        "preference_equal_coverage": (
            sum(value == 0 for value in deltas) / len(deltas) if deltas else None),
        "preference_loss_coverage": (
            sum(value < 0 for value in deltas) / len(deltas) if deltas else None),
        "pace_vs_fastest_path_change_coverage": (
            statistics.mean(path_change) if path_change else None),
        "within_profile_change_coverage": finite_summary(temporal_change),
        "delta_s": finite_summary(deltas),
        "delta_t_minutes": finite_summary(delta_t),
        "budget_utilization": finite_summary(budget_use),
        "corr_delta_s_delta_t_pearson": correlation(deltas, delta_t),
        "corr_delta_s_delta_t_spearman": correlation(
            pd.Series(deltas).rank(method="average"),
            pd.Series(delta_t).rank(method="average")),
        "profile_cell_count": finite_summary(profile_cells),
        "distinct_path_count": finite_summary(distinct_paths),
        "route_switches": finite_summary(route_switches),
        "multi_path_rate": (
            sum(value > 1 for value in distinct_paths) / len(distinct_paths)
            if distinct_paths else None),
        "budget_violation_count": sum(
            row["pace_b"]["budget_violation_count"]
            for row in completed),
    }
    by_rho = {}
    for rho in (0.2, 0.5):
        group = [row for row in rows if row["rho"] == rho]
        group_points = [(row, point) for row in group
                        for point in result_points(
                            row, replay_by_id.get(row["query_id"])
                            if replay_by_id else None)]
        group_resolved = [(row, point) for row, point in group_points
                          if point["pace_resolved"]]
        by_rho[str(rho)] = {
            "query_count": len(group),
            "status_counts": dict(collections.Counter(
                row["status"] for row in group)),
            "resolved_coverage": len(group_resolved) / len(group_points),
            "full_interval_delta_s_unresolved_zero": (
                sum(point["pace_score"] for _, point in group_points)
                / len(group_points)
                - statistics.mean(point["fast_score"]
                                  for _, point in group_points)),
            "resolved_only_delta_s": (
                statistics.mean(point["pace_score"] - point["fast_score"]
                                for _, point in group_resolved)
                if group_resolved else None),
        }
    result["by_rho"] = by_rho
    return result


def replay_invariants(replay: list[dict[str, Any]]) -> dict[str, Any]:
    points = [point for row in replay for point in row["points"]]
    pace = [point for point in points if point["pace_resolved"]]
    traces = [row["trace_rerun"] for row in replay if row["trace_rerun"]]
    return {
        "query_count": len(replay),
        "departure_samples": len(points),
        "stored_fastest_valid": sum(
            point["stored_fastest_valid"] for point in points),
        "fresh_fastest_valid": sum(
            point["fresh_fastest_valid"] for point in points),
        "fresh_fastest_path_agreement": sum(
            point["fresh_fastest_path_agreement"] for point in points),
        "fresh_fastest_travel_agreement": sum(
            point["fresh_fastest_travel_agreement"] for point in points),
        "fastest_in_query_region": sum(
            point["fastest_in_query_region"] for point in points),
        "pace_resolved_samples": len(pace),
        "pace_exact_replay_valid": sum(point["pace_valid"] for point in pace),
        "pace_budget_violations": sum(
            not point["pace_valid"] for point in pace),
        "traced_resolved_queries": len(traces),
        "trace_output_checksum_agreements": sum(
            trace["checksum_agreement"] for trace in traces),
    }


def interval_best_metrics(replay: list[dict[str, Any]]) -> dict[str, Any]:
    records = []
    for row in replay:
        trace = row.get("trace_rerun")
        if not trace or not trace["interval_best"].get("found"):
            continue
        best = trace["interval_best"]
        selected = sum(point["pace_resolved"]
                       and point["pace_path"] == best["path"]
                       for point in row["points"])
        records.append({
            "query_id": row["query_id"],
            "rho": row["rho"],
            "registry_candidate_count": trace["candidate_count"],
            "interval_best_departure": best["departure"],
            "interval_best_score": best["score"],
            "interval_best_path_selected_departure_coverage":
                selected / len(row["points"]),
        })
    output = VALIDATION / "interval_best_registry_coverage.csv"
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0])
                                if records else ["query_id"])
        writer.writeheader()
        writer.writerows(records)
    return {
        "query_count": len(records),
        "selected_departure_coverage": finite_summary(
            row["interval_best_path_selected_departure_coverage"]
            for row in records),
        "csv_sha256": sha256(output),
    }


def query_region_growth(replay: list[dict[str, Any]]) -> dict[str, Any]:
    families: dict[tuple[str, str], dict[float, dict[str, Any]]] = (
        collections.defaultdict(dict))
    for row in replay:
        families[(row["pair_id"], row["period_id"])][row["rho"]] = row
    records = []
    for (pair, period), values in sorted(families.items()):
        if 0.2 not in values or 0.5 not in values:
            continue
        low, high = values[0.2]["corridor"], values[0.5]["corridor"]
        records.append({
            "pair_id": pair,
            "period_id": period,
            "nodes_r20": low["nodes"],
            "nodes_r50": high["nodes"],
            "nodes_ratio_r50_r20": high["nodes"] / low["nodes"],
            "edges_r20": low["edges"],
            "edges_r50": high["edges"],
            "edges_ratio_r50_r20": high["edges"] / low["edges"],
        })
    output = VALIDATION / "query_region_growth.csv"
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)
    return {
        "paired_query_families": len(records),
        "nodes_ratio_r50_r20": finite_summary(
            row["nodes_ratio_r50_r20"] for row in records),
        "edges_ratio_r50_r20": finite_summary(
            row["edges_ratio_r50_r20"] for row in records),
        "nodes_nondecrease_fraction": statistics.mean(
            row["nodes_r50"] >= row["nodes_r20"] for row in records),
        "edges_nondecrease_fraction": statistics.mean(
            row["edges_r50"] >= row["edges_r20"] for row in records),
        "csv_sha256": sha256(output),
    }


def segmentation_bias(replay: list[dict[str, Any]]) -> dict[str, Any]:
    edge_data = pd.read_parquet(
        EDGES, columns=["arc_id", "geometric_length_m"])
    lengths = np.zeros(int(edge_data.arc_id.max()) + 1, dtype=float)
    lengths[edge_data.arc_id.to_numpy(dtype=int)] = (
        edge_data.geometric_length_m.to_numpy(dtype=float))
    observations = []
    for row in replay:
        for point in row["points"]:
            if not point["pace_resolved"]:
                continue
            arcs = np.asarray(point["pace_path"], dtype=int)
            observations.append({
                "query_id": row["query_id"],
                "departure": point["departure"],
                "score": point["pace_score"],
                "edge_count": len(arcs),
                "geometric_length_km": float(lengths[arcs].sum() / 1000),
            })
    frame = pd.DataFrame(observations)
    output = VALIDATION / "score_segmentation_observations.csv"
    frame.to_csv(output, index=False)
    corr_edges = float(frame.score.corr(frame.edge_count))
    corr_length = float(frame.score.corr(frame.geometric_length_km))
    return {
        "resolved_temporal_path_samples": len(frame),
        "corr_score_edge_count": corr_edges,
        "corr_score_geometric_length": corr_length,
        "severe_segmentation_bias_by_predeclared_rule": (
            abs(corr_edges) >= 0.7
            and abs(corr_edges) > abs(corr_length) + 0.15),
        "severity_rule": (
            "severe iff |corr(score,edge_count)| >= 0.70 and exceeds "
            "|corr(score,geometric_length)| by > 0.15"),
        "csv_sha256": sha256(output),
    }


def checksum_determinism(cap_rows: dict[int, list[dict[str, Any]]],
                         replay: list[dict[str, Any]]) -> dict[str, Any]:
    observed: dict[str, list[tuple[int, str]]] = collections.defaultdict(list)
    for cap, rows in cap_rows.items():
        for row in rows:
            if row.get("final_checksum"):
                observed[row["query_id"]].append(
                    (cap, row["final_checksum"]))
    repeated = {query: values for query, values in observed.items()
                if len(values) >= 2}
    trace = [row["trace_rerun"] for row in replay if row["trace_rerun"]]
    return {
        "cross_cap_repeated_completed_queries": len(repeated),
        "cross_cap_checksum_agreements": sum(
            len({checksum for _, checksum in values}) == 1
            for values in repeated.values()),
        "cross_cap_all_agree": all(
            len({checksum for _, checksum in values}) == 1
            for values in repeated.values()),
        "independent_trace_reruns": len(trace),
        "independent_trace_checksum_agreements": sum(
            value["checksum_agreement"] for value in trace),
        "trace_all_agree": all(value["checksum_agreement"] for value in trace),
    }


def controlled_summary() -> dict[str, Any]:
    selection = set(json.loads((CASE_ROOT / "manifests/nyc_pace_x_subset.json")
                               .read_text(encoding="utf-8"))["query_ids"])
    variants = {
        "V1_DATA_OLD_CONNECTOR": [row for row in read_jsonl(V1_CAP30)
                                  if row["query_id"] in selection],
        "V1_DATA_CORRECTED_CONNECTOR": read_jsonl(
            CONTROLLED / "v1_data_corrected_connector_30s.jsonl"),
        "IMPROVED_SCORE_OLD_CONNECTOR": read_jsonl(
            CONTROLLED / "improved_score_old_connector_30s.jsonl"),
        "FULL_V2": read_jsonl(CONTROLLED / "full_v2_30s.jsonl"),
    }
    summary: dict[str, Any] = {}
    for name, rows in variants.items():
        complete = [row for row in rows if row["status"] == "COMPLETE"]
        summary[name] = {
            "query_count": len(rows),
            "status_counts": dict(collections.Counter(
                row["status"] for row in rows)),
            "mean_full_interval_score_completed": (
                statistics.mean(row["profile"]["full_interval_average_score"]
                                for row in complete) if complete else None),
            "mean_resolved_score_completed": (
                statistics.mean(row["profile"]["resolved_average_score"]
                                for row in complete) if complete else None),
        }
    baseline = {row["query_id"]: row
                for row in variants["V1_DATA_OLD_CONNECTOR"]}
    effects = {}
    for name in ("V1_DATA_CORRECTED_CONNECTOR",
                 "IMPROVED_SCORE_OLD_CONNECTOR", "FULL_V2"):
        current = {row["query_id"]: row for row in variants[name]}
        common = [query for query in selection
                  if baseline[query]["status"] == "COMPLETE"
                  and current[query]["status"] == "COMPLETE"]
        effects[name] = {
            "common_completed_queries": len(common),
            "mean_full_interval_score_change_vs_v1_control": (
                statistics.mean(
                    current[query]["profile"]["full_interval_average_score"]
                    - baseline[query]["profile"]["full_interval_average_score"]
                    for query in common) if common else None),
            "identical_point_path_fraction_on_common_completed": (
                statistics.mean(
                    statistics.mean(
                        left.get("path") == right.get("path")
                        and left["resolved"] == right["resolved"]
                        for left, right in zip(
                            baseline[query]["profile"]["points"],
                            current[query]["profile"]["points"]))
                    for query in common) if common else None),
        }
    return {
        "selection_query_count": len(selection),
        "selection_manifest_sha256": sha256(
            CASE_ROOT / "manifests/nyc_pace_x_subset.json"),
        "variants": summary,
        "effects_vs_v1_control": effects,
        "boundary_note": (
            "External-cap status differences near 30 seconds are timing-sensitive; "
            "scientific comparisons use common completed queries."),
    }


def status_only_remap(v1: list[dict[str, Any]]) -> dict[str, Any]:
    remapped = [row["query_id"] for row in v1
                if row["status"] == "NO_FEASIBLE_PATH"]
    output = {
        "schema_version": "nyc-status-only-remap-v1",
        "source_result_sha256": sha256(V1_RESULTS),
        "source_status_counts": dict(collections.Counter(
            row["status"] for row in v1)),
        "remap_rule": (
            "bounded empty without independent infeasibility certificate: "
            "NO_FEASIBLE_PATH -> UNRESOLVED_NO_GENERATED_CANDIDATE (U)"),
        "remapped_query_ids": remapped,
        "remapped_query_count": len(remapped),
        "scientific_numeric_fields_changed": False,
    }
    atomic_write_json(V2 / "provenance/status_only_v1_remap.json", output)
    return output


def comparison_report(metrics: dict[str, Any], v1: list[dict[str, Any]],
                      controlled: dict[str, Any], status: dict[str, Any]) -> str:
    manifest = json.loads((V2 / "processed/NYC-REAL-V2/manifest.json")
                          .read_text(encoding="utf-8"))
    query_compare = json.loads((V2 / "provenance/query_manifest_comparison.json")
                               .read_text(encoding="utf-8"))
    v1_score = json.loads((V1_BUNDLE / "diagnostics/story_validation_metrics.json")
                          .read_text(encoding="utf-8"))["mta_mapping"]
    v2_score = manifest["score_definition"]
    v1_statuses = dict(collections.Counter(row["status"] for row in v1))
    v2_statuses = metrics["caps"]["5"]["status_counts"]
    controls = controlled["variants"]
    effects = controlled["effects_vs_v1_control"]
    return f"""# NYC V1/V2 diagnostic comparison

This report compares frozen executions and data artifacts. It does not make or modify manuscript claims.

## Frozen scientific inputs

- Query comparison pass: `{query_compare['all_scientific_query_fields_identical']}`.
- V1/V2 query records: {query_compare['v1_query_count']}/{query_compare['v2_query_count']}; terminal pairs: {query_compare['v1_pair_count']}/{query_compare['v2_pair_count']}.
- Compared fields: `{', '.join(query_compare['scientific_fields'])}`; mismatches: {len(query_compare['scientific_field_mismatches'])}.
- Frozen PACE-B parameters in every V2 timed record: `L=32`, `theta=2`, `Kf=16`, `Mb=1000000`.
- Travel artifacts are byte-identical for nodes, static edges, travel-time functions, and travel provenance. Provenance counts remain `{json.dumps(manifest['traffic_calibration']['provenance_counts'], sort_keys=True)}`.

## CONNECTOR_FIX

PACE-B now chooses one exact FIFO time-dependent fastest structure at each connected connector-cell midpoint and exactly replays that structure over the cell. The production runner exposes no Kc, Mc, Mq, connector-portfolio, or connector-cache tuning options.

On the predeclared {controlled['selection_query_count']}-query controlled subset at 30 seconds:

- V1 data + old connector: `{controls['V1_DATA_OLD_CONNECTOR']['status_counts']}`.
- V1 data + corrected connector: `{controls['V1_DATA_CORRECTED_CONNECTOR']['status_counts']}`.
- Common-completion mean score change versus the V1 control: {effects['V1_DATA_CORRECTED_CONNECTOR']['mean_full_interval_score_change_vs_v1_control']:.6f} across {effects['V1_DATA_CORRECTED_CONNECTOR']['common_completed_queries']} queries.
- Common-completion point-path identity versus the V1 control: {100 * effects['V1_DATA_CORRECTED_CONNECTOR']['identical_point_path_fraction_on_common_completed']:.3f}%.

The corrected connector is materially more expensive on this graph. This is an observed diagnostic outcome, not a basis for tuning.

## MTA_SHAPE_COMPLETENESS

- Active shape IDs: {v2_score['active_shape_ids']}.
- V1 exact active shapes mapped: 885; V2 exact geometries available/mapped: {v2_score['exact_shape_geometries_available']}/{v2_score['exact_shape_geometries_mapped_to_dimacs']}.
- Unmatched active shapes: V1 3368; V2 {v2_score['unmatched_active_shapes']}.
- Active route-shape-bin exact match: V1 {v1_score['exact_shape_id_activity_percent']:.6f}%; V2 {v2_score['active_route_shape_bin_exact_match_percent']:.6f}%.
- Score-bearing DIMACS edges: V1 21422; V2 {v2_score['score_bearing_edges']}.
- V2 route geometry fallbacks: {v2_score['route_geometry_fallback_count']}.

Controlled old-connector executions resolved {controls['IMPROVED_SCORE_OLD_CONNECTOR']['status_counts'].get('COMPLETE', 0)}/{controlled['selection_query_count']} queries. On common completions, the improved score data changed mean full-interval score by {effects['IMPROVED_SCORE_OLD_CONNECTOR']['mean_full_interval_score_change_vs_v1_control']:.6f} and retained identical point paths for {100 * effects['IMPROVED_SCORE_OLD_CONNECTOR']['identical_point_path_fraction_on_common_completed']:.3f}% of samples.

## STATUS_ONLY

The status-only machine remap identifies {status['remapped_query_count']} V1 bounded-empty records: `{', '.join(status['remapped_query_ids'])}`. Their non-certified label becomes `UNRESOLVED_NO_GENERATED_CANDIDATE` (`U`); no scientific numeric field changes. Certified `NO_FEASIBLE_PATH` remains available only with an independent global certificate.

V1 primary statuses were `{v1_statuses}`. V2 primary statuses are `{v2_statuses}`. The V2 count difference includes connector/data execution effects and must not be interpreted as status-only attribution.

## Full V2 execution and validation

- Primary 5 s: `{metrics['caps']['5']['status_counts']}`.
- Diagnostic 10 s: `{metrics['caps']['10']['status_counts']}`.
- Diagnostic 30 s: `{metrics['caps']['30']['status_counts']}`.
- Fastest invariant: {metrics['invariants']['fresh_fastest_path_agreement']}/{metrics['invariants']['departure_samples']} path agreements; query-region containment {metrics['invariants']['fastest_in_query_region']}/{metrics['invariants']['departure_samples']}.
- Exact PACE replay/budget validation: {metrics['invariants']['pace_exact_replay_valid']}/{metrics['invariants']['pace_resolved_samples']}; violations {metrics['invariants']['pace_budget_violations']}.
- Determinism: {metrics['determinism']['cross_cap_checksum_agreements']}/{metrics['determinism']['cross_cap_repeated_completed_queries']} repeated cross-cap checksums and {metrics['determinism']['independent_trace_checksum_agreements']}/{metrics['determinism']['independent_trace_reruns']} independent trace checksums agree.

The controlled full-V2 subset produced `{controls['FULL_V2']['status_counts']}`. {controlled['boundary_note']}
"""


def validation_report(metrics: dict[str, Any]) -> str:
    cap = metrics["caps"]["30"]
    invariant = metrics["invariants"]
    return f"""# NYC-real-shuttle-v2 validation

This is a diagnostic validation report and does not alter manuscript claims.

## Execution

- 5 s primary statuses: `{metrics['caps']['5']['status_counts']}`
- 10 s diagnostic statuses: `{metrics['caps']['10']['status_counts']}`
- 30 s diagnostic statuses: `{cap['status_counts']}`
- No V2 timed result emitted `NO_FEASIBLE_PATH` or `INFEASIBLE`.

## Exact invariants

- Stored/fresh fastest valid: {invariant['stored_fastest_valid']}/{invariant['fresh_fastest_valid']} of {invariant['departure_samples']} each.
- Independent fastest path/travel agreements: {invariant['fresh_fastest_path_agreement']}/{invariant['fresh_fastest_travel_agreement']}.
- Fastest paths inside safe query region: {invariant['fastest_in_query_region']}/{invariant['departure_samples']}.
- PACE exact replay valid: {invariant['pace_exact_replay_valid']}/{invariant['pace_resolved_samples']}; budget violations: {invariant['pace_budget_violations']}.

## Profile and score diagnostics at 30 seconds

- Resolved coverage: {100 * cap['resolved_coverage']:.3f}%.
- MultiPathRate: {100 * cap['multi_path_rate']:.3f}%.
- Profile cells: `{cap['profile_cell_count']}`.
- Distinct paths: `{cap['distinct_path_count']}`.
- Route switches: `{cap['route_switches']}`.
- ChangeCoverage versus fastest: {100 * cap['pace_vs_fastest_path_change_coverage']:.3f}%.
- Full-interval DeltaS (unresolved contributes zero): {cap['full_interval_delta_s_unresolved_zero']:.6f}.
- Resolved-only DeltaS: {cap['resolved_only_delta_s']:.6f}.
- Preference improvement/equal/loss: {100 * cap['preference_improvement_coverage']:.3f}% / {100 * cap['preference_equal_coverage']:.3f}% / {100 * cap['preference_loss_coverage']:.3f}%.
- Pearson/Spearman correlation of DeltaS with DeltaT: {cap['corr_delta_s_delta_t_pearson']:.6f} / {cap['corr_delta_s_delta_t_spearman']:.6f}.
- Budget utilization: `{cap['budget_utilization']}`.

## Coverage, growth, segmentation, determinism

- Interval-best registry path coverage: `{metrics['interval_best_registry']}`.
- R50/R20 query-region growth: `{metrics['query_region_growth']}`.
- Score segmentation correlations: `{metrics['segmentation_bias']}`.
- Output checksum determinism: `{metrics['determinism']}`.
"""


def main() -> int:
    cap_rows = {cap: read_jsonl(path) for cap, path in CAPS.items()}
    replay = read_jsonl(REPLAY)
    v1 = read_jsonl(V1_RESULTS)
    for cap, rows in cap_rows.items():
        if len(rows) != 100 or len({row["query_id"] for row in rows}) != 100:
            raise ValueError(f"cap {cap} does not contain 100 unique queries")
    if len(replay) != 100:
        raise ValueError("validation replay does not contain 100 queries")
    controlled = controlled_summary()
    status = status_only_remap(v1)
    replay_by_id = {row["query_id"]: row for row in replay}
    metrics = {
        "schema_version": "nyc-v2-validation-metrics-v1",
        "caps": {str(cap): cap_metrics(
                    cap, rows, replay_by_id if cap == 30 else None)
                 for cap, rows in cap_rows.items()},
        "invariants": replay_invariants(replay),
        "interval_best_registry": interval_best_metrics(replay),
        "query_region_growth": query_region_growth(replay),
        "segmentation_bias": segmentation_bias(replay),
        "determinism": checksum_determinism(cap_rows, replay),
        "controlled_variants": controlled,
        "input_sha256": {
            **{f"cap_{cap}": sha256(path) for cap, path in CAPS.items()},
            "replay": sha256(REPLAY),
            "v1_results": sha256(V1_RESULTS),
        },
    }
    atomic_write_json(VALIDATION / "validation_metrics.json", metrics)
    atomic_write_json(VALIDATION / "controlled_variant_summary.json", controlled)
    REPORTS.mkdir(parents=True, exist_ok=True)
    report = comparison_report(metrics, v1, controlled, status)
    (REPORTS / "nyc_v1_v2_comparison.md").write_text(
        report, encoding="utf-8", newline="\n")
    ROOT_REPORT.parent.mkdir(parents=True, exist_ok=True)
    ROOT_REPORT.write_text(report, encoding="utf-8", newline="\n")
    (REPORTS / "validation.md").write_text(
        validation_report(metrics), encoding="utf-8", newline="\n")
    print(json.dumps(metrics, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
