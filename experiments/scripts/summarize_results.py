#!/usr/bin/env python3
"""Aggregate trials per query first, then summarize across queries."""
from __future__ import annotations

import argparse
import collections
import csv
import json
import math
from pathlib import Path
import random
import statistics
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, write_jsonl
from experiments.scripts.common.config import load_design, repo_path


def _percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
    return ordered[index]


def _bootstrap_median(values: list[float], seed: int, samples: int = 1000) -> tuple[float | None, float | None]:
    if not values:
        return None, None
    randomizer = random.Random(seed)
    medians = [statistics.median(randomizer.choices(values, k=len(values))) for _ in range(samples)]
    return _percentile(medians, 0.025), _percentile(medians, 0.975)


def _wilcoxon(values: list[tuple[float, float]]) -> tuple[float | None, float | None]:
    """Return two-sided normal-approximation p and rank-biserial effect."""
    differences = [left - right for left, right in values if left != right]
    if not differences:
        return None, 0.0 if values else None
    ordered = sorted(enumerate(differences), key=lambda item: abs(item[1]))
    ranks = [0.0] * len(differences)
    index = 0
    while index < len(ordered):
        end = index + 1
        while end < len(ordered) and abs(ordered[end][1]) == abs(ordered[index][1]):
            end += 1
        rank = (index + 1 + end) / 2.0
        for original, _ in ordered[index:end]:
            ranks[original] = rank
        index = end
    positive = sum(rank for rank, difference in zip(ranks, differences) if difference > 0)
    negative = sum(rank for rank, difference in zip(ranks, differences) if difference < 0)
    total = positive + negative
    n = len(differences)
    mean = n * (n + 1) / 4.0
    variance = n * (n + 1) * (2 * n + 1) / 24.0
    z = (min(positive, negative) - mean + 0.5) / math.sqrt(variance) if variance else 0.0
    return math.erfc(abs(z) / math.sqrt(2.0)), (positive - negative) / total


def _holm(rows: list[dict[str, Any]]) -> None:
    ranked = sorted((row for row in rows if row["p_value"] is not None), key=lambda row: row["p_value"])
    previous = 0.0
    count = len(ranked)
    for index, row in enumerate(ranked):
        adjusted = min(1.0, (count - index) * row["p_value"])
        adjusted = max(previous, adjusted)
        row["holm_p_value"] = adjusted
        previous = adjusted
    for row in rows:
        row.setdefault("holm_p_value", None)


def summarize(run_root: Path, design: dict[str, Any]) -> dict[str, Any]:
    normalized = run_root / "normalized" / "run_records.jsonl"
    records = [json.loads(line) for line in normalized.read_text(encoding="utf-8").splitlines() if line.strip()]
    plan_rows = []
    for path in sorted((run_root / "plan" / "matrices").glob("*.jsonl")):
        if path.name == "canonical_job_ledger.jsonl":
            continue
        plan_rows.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    plans = {row["job_id"]: row for row in plan_rows}
    metric_paths = {
        "wall_time_ns": ("timing_ns", "query_total"),
        "cpu_time_ns": ("timing_ns", "cpu_total"),
        "peak_rss_bytes": ("memory_bytes", "peak_rss"),
        "start_rss_bytes": ("memory_bytes", "start_rss"),
        "end_rss_bytes": ("memory_bytes", "end_rss"),
        "peak_heap_bytes": ("memory_bytes", "peak_heap"),
        "profile_cells_total": ("output", "final_profile_intervals"),
        "feasible_coverage": ("output", "feasible_coverage_fraction"),
        "average_selected_score": ("output", "average_selected_score"),
        "best_selected_score": ("output", "best_selected_score"),
        "path_agreement": ("quality", "path_agreement_fraction"),
        "score_agreement": ("quality", "score_agreement_fraction"),
        "score_regret": ("quality", "integrated_score_regret"),
        "relative_score_gap_percent": ("quality", "relative_score_gap_percent"),
        "corridor_nodes": ("counters", "corridor_nodes"),
        "corridor_edges": ("counters", "corridor_edges"),
        "selected_pivots": ("counters", "selected_pivots"),
        "connector_calls": ("counters", "connector_calls"),
        "connector_expansions": ("counters", "connector_expansions"),
        "valid_connectors": ("counters", "valid_connectors"),
        "candidates_generated": ("counters", "candidates_generated"),
        "candidates_retained": ("counters", "candidates_retained"),
        "candidates_before_compression": ("counters", "candidates_before_compression"),
        "total_candidate_work": ("counters", "total_candidate_work"),
        "memo_lookups": ("counters", "memo_lookups"),
        "memo_hits": ("counters", "memo_hits"),
        "memo_misses": ("counters", "memo_misses"),
        "memo_waits": ("counters", "memo_waits"),
        "observed_workers": ("counters", "observed_workers"),
    }
    Group = tuple[str, str, str, str, str]
    trials: dict[tuple[Group, str], dict[str, list[float]]] = collections.defaultdict(
        lambda: collections.defaultdict(list)
    )
    completion: collections.Counter[Group] = collections.Counter()
    successful: collections.Counter[Group] = collections.Counter()
    statuses: dict[Group, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    exactness: dict[Group, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    generations: dict[Group, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    cap_counts: dict[Group, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    any_cap: collections.Counter[Group] = collections.Counter()
    output_checksums: dict[tuple[Group, str], set[str]] = collections.defaultdict(set)
    par2_samples: dict[Group, list[float]] = collections.defaultdict(list)
    metadata: dict[Group, dict[str, Any]] = {}
    for record in records:
        plan = plans.get(record["job_id"])
        if not plan:
            continue
        axis_json = json.dumps(plan.get("axis", {}), sort_keys=True, separators=(",", ":"))
        group = (
            plan["study_id"],
            plan["dataset_id"],
            plan["algorithm_id"],
            plan["variant_id"],
            axis_json,
        )
        completion[group] += 1
        statuses[group][record["completion_status"]] += 1
        java = record.get("java_record") or {}
        java_status = java.get("status", {})
        if java_status.get("exactness_scope"):
            exactness[group][str(java_status["exactness_scope"])] += 1
        if java_status.get("generation_completion"):
            generations[group][str(java_status["generation_completion"])] += 1
        triggered_caps = java_status.get("cap_triggered") or []
        if triggered_caps:
            any_cap[group] += 1
        for cap in triggered_caps:
            cap_counts[group][str(cap)] += 1
        metadata.setdefault(group, {
            "axis": dict(plan.get("axis", {})),
            "configuration": dict(java.get("configuration", {})),
        })
        runtime = java.get("timing_ns", {}).get("query_total")
        checksum = java.get("output", {}).get("profile_checksum")
        if checksum:
            output_checksums[(group, plan["query_id"])].add(str(checksum))
        if record["completion_status"] == "SUCCESS" and isinstance(runtime, (int, float)):
            successful[group] += 1
            par2_samples[group].append(float(runtime))
            query_key = (group, plan["query_id"])
            for metric, (section, field) in metric_paths.items():
                value = java.get(section, {}).get(field)
                if isinstance(value, (int, float)):
                    trials[query_key][metric].append(float(value))
            memory = java.get("memory_bytes", {})
            if isinstance(memory.get("peak_rss"), (int, float)) and isinstance(
                memory.get("start_rss"), (int, float)
            ):
                trials[query_key]["incremental_rss_bytes"].append(
                    max(0.0, float(memory["peak_rss"]) - float(memory["start_rss"]))
                )
            counters = java.get("counters", {})
            if isinstance(counters.get("memo_hits"), (int, float)) and isinstance(
                counters.get("memo_lookups"), (int, float)
            ):
                lookups = float(counters["memo_lookups"])
                trials[query_key]["memo_hit_rate"].append(
                    float(counters["memo_hits"]) / lookups if lookups else 0.0
                )
            if isinstance(counters.get("candidates_retained"), (int, float)) and isinstance(
                counters.get("candidates_generated"), (int, float)
            ):
                generated = float(counters["candidates_generated"])
                trials[query_key]["candidate_retention_fraction"].append(
                    float(counters["candidates_retained"]) / generated if generated else 0.0
                )
        else:
            par2_samples[group].append(2.0 * int(design["resources"]["timeout_seconds"]) * 1_000_000_000)
    query_medians: dict[Group, dict[str, list[float]]] = collections.defaultdict(
        lambda: collections.defaultdict(list)
    )
    paired_runtime: dict[tuple[str, str, str, str, str], float] = {}
    for key, metrics in trials.items():
        group, query_id = key
        for metric, values in metrics.items():
            query_medians[group][metric].append(statistics.median(values))
        if metrics.get("wall_time_ns"):
            method = group[3] if group[3] != group[2] else group[2]
            paired_runtime[(group[0], group[1], query_id, method, group[4])] = statistics.median(
                metrics["wall_time_ns"]
            )
    rows: list[dict[str, Any]] = []
    seed = int(design.get("seeds", {}).get("bootstrap", 20260726))
    for group in sorted(completion):
        metrics = query_medians.get(group, {})
        values = metrics.get("wall_time_ns", [])
        low, high = _bootstrap_median(values, seed + len(rows))
        q1 = _percentile(values, 0.25)
        q3 = _percentile(values, 0.75)
        total = completion[group]
        axis = metadata[group]["axis"]
        configuration = metadata[group]["configuration"]
        row = {
            "schema_version": 1,
            "metric_definition_version": design["protocol"]["metric_definition_version"],
            "study_id": group[0], "dataset_id": group[1], "algorithm_id": group[2],
            "variant_id": group[3], "axis_json": group[4],
            "planned": total, "successful": successful[group],
            "completion_rate": successful[group] / total if total else 0,
            "timeout_rate": statuses[group]["TIMEOUT"] / total if total else 0,
            "oom_rate": statuses[group]["OUT_OF_MEMORY"] / total if total else 0,
            "limit_rate": statuses[group]["RESOURCE_LIMIT_EXCEEDED"] / total if total else 0,
            "failure_rate": (total - successful[group]) / total if total else 0,
            "cap_trigger_rate": any_cap[group] / total if total else 0,
            "connector_cap_hit_rate": cap_counts[group]["CONNECTOR_M_C"] / total if total else 0,
            "breakpoint_cap_hit_rate": cap_counts[group]["BREAKPOINT_M_B"] / total if total else 0,
            "query_work_cap_hit_rate": cap_counts[group]["QUERY_WORK_M_Q"] / total if total else 0,
            "emergency_guard_hit_rate": cap_counts[group]["EMERGENCY_FRONTIER_GUARD"] / total if total else 0,
            "status_counts": json.dumps(dict(sorted(statuses[group].items())), sort_keys=True),
            "exactness_status_counts": json.dumps(dict(sorted(exactness[group].items())), sort_keys=True),
            "generation_status_counts": json.dumps(dict(sorted(generations[group].items())), sort_keys=True),
            "query_units": len(values),
            "median_wall_time_ns": statistics.median(values) if values else None,
            "median_cpu_time_ns": statistics.median(metrics["cpu_time_ns"]) if metrics.get("cpu_time_ns") else None,
            "iqr_wall_time_ns": q3 - q1 if q1 is not None and q3 is not None else None,
            "bootstrap_95_low_ns": low, "bootstrap_95_high_ns": high,
            "par2_wall_time_ns": statistics.mean(par2_samples[group]) if par2_samples[group] else None,
            "median_peak_rss_bytes": statistics.median(metrics["peak_rss_bytes"]) if metrics.get("peak_rss_bytes") else None,
            "median_incremental_rss_bytes": statistics.median(metrics["incremental_rss_bytes"]) if metrics.get("incremental_rss_bytes") else None,
            "median_peak_heap_bytes": statistics.median(metrics["peak_heap_bytes"]) if metrics.get("peak_heap_bytes") else None,
            "median_corridor_nodes": statistics.median(metrics["corridor_nodes"]) if metrics.get("corridor_nodes") else None,
            "median_corridor_edges": statistics.median(metrics["corridor_edges"]) if metrics.get("corridor_edges") else None,
            "median_selected_pivots": statistics.median(metrics["selected_pivots"]) if metrics.get("selected_pivots") else None,
            "median_connector_calls": statistics.median(metrics["connector_calls"]) if metrics.get("connector_calls") else None,
            "median_connector_expansions": statistics.median(metrics["connector_expansions"]) if metrics.get("connector_expansions") else None,
            "median_valid_connectors": statistics.median(metrics["valid_connectors"]) if metrics.get("valid_connectors") else None,
            "median_candidates_generated": statistics.median(metrics["candidates_generated"]) if metrics.get("candidates_generated") else None,
            "median_candidates_retained": statistics.median(metrics["candidates_retained"]) if metrics.get("candidates_retained") else None,
            "median_candidate_retention_fraction": statistics.median(metrics["candidate_retention_fraction"]) if metrics.get("candidate_retention_fraction") else None,
            "median_total_candidate_work": statistics.median(metrics["total_candidate_work"]) if metrics.get("total_candidate_work") else None,
            "median_memo_lookups": statistics.median(metrics["memo_lookups"]) if metrics.get("memo_lookups") else None,
            "median_memo_hits": statistics.median(metrics["memo_hits"]) if metrics.get("memo_hits") else None,
            "median_memo_misses": statistics.median(metrics["memo_misses"]) if metrics.get("memo_misses") else None,
            "median_memo_waits": statistics.median(metrics["memo_waits"]) if metrics.get("memo_waits") else None,
            "median_memo_hit_rate": statistics.median(metrics["memo_hit_rate"]) if metrics.get("memo_hit_rate") else None,
            "median_observed_workers": statistics.median(metrics["observed_workers"]) if metrics.get("observed_workers") else None,
            "median_profile_cells_total": statistics.median(metrics["profile_cells_total"]) if metrics.get("profile_cells_total") else None,
            "median_feasible_coverage": statistics.median(metrics["feasible_coverage"]) if metrics.get("feasible_coverage") else None,
            "median_average_selected_score": statistics.median(metrics["average_selected_score"]) if metrics.get("average_selected_score") else None,
            "median_best_selected_score": statistics.median(metrics["best_selected_score"]) if metrics.get("best_selected_score") else None,
            "median_path_agreement": statistics.median(metrics["path_agreement"]) if metrics.get("path_agreement") else None,
            "median_score_agreement": statistics.median(metrics["score_agreement"]) if metrics.get("score_agreement") else None,
            "median_score_regret": statistics.median(metrics["score_regret"]) if metrics.get("score_regret") else None,
            "median_relative_score_gap_percent": statistics.median(metrics["relative_score_gap_percent"]) if metrics.get("relative_score_gap_percent") else None,
            "threads": axis.get("threads", configuration.get("threads")),
            "speedup": None,
            "parallel_efficiency": None,
            "worker_utilization": None,
            "checksum_equal_across_trials": all(
                len(checksums) <= 1
                for (checksum_group, _), checksums in output_checksums.items()
                if checksum_group == group
            ),
        }
        for name in (
            "window_minutes", "budget_overhead", "theta", "score_density",
            "pivot_limit_l", "connector_limit_kc", "frontier_limit_kf",
            "connector_expansion_cap_mc", "query_work_cap_mq", "graph_seed",
            "diagnostic",
        ):
            row[name] = axis.get(name, configuration.get(name))
        uncertainty_fields = {
            "median_wall_time_ns": "wall_time_ns",
            "median_cpu_time_ns": "cpu_time_ns",
            "median_peak_rss_bytes": "peak_rss_bytes",
            "median_incremental_rss_bytes": "incremental_rss_bytes",
            "median_peak_heap_bytes": "peak_heap_bytes",
            "median_corridor_nodes": "corridor_nodes",
            "median_corridor_edges": "corridor_edges",
            "median_selected_pivots": "selected_pivots",
            "median_connector_calls": "connector_calls",
            "median_connector_expansions": "connector_expansions",
            "median_valid_connectors": "valid_connectors",
            "median_candidates_generated": "candidates_generated",
            "median_candidates_retained": "candidates_retained",
            "median_total_candidate_work": "total_candidate_work",
            "median_profile_cells_total": "profile_cells_total",
            "median_feasible_coverage": "feasible_coverage",
            "median_average_selected_score": "average_selected_score",
            "median_best_selected_score": "best_selected_score",
            "median_path_agreement": "path_agreement",
            "median_score_agreement": "score_agreement",
            "median_score_regret": "score_regret",
            "median_observed_workers": "observed_workers",
        }
        uncertainty: dict[str, list[float] | None] = {}
        for field, source_metric in uncertainty_fields.items():
            samples = metrics.get(source_metric, [])
            low_value = _percentile(samples, 0.25)
            high_value = _percentile(samples, 0.75)
            uncertainty[field] = (
                [low_value, high_value]
                if low_value is not None and high_value is not None
                else None
            )
        for field in (
            "completion_rate", "timeout_rate", "oom_rate", "cap_trigger_rate",
            "connector_cap_hit_rate", "breakpoint_cap_hit_rate",
            "query_work_cap_hit_rate", "emergency_guard_hit_rate",
        ):
            proportion = float(row[field])
            radius = 1.96 * math.sqrt(
                proportion * (1.0 - proportion) / total
            ) if total else 0.0
            uncertainty[field] = [
                max(0.0, proportion - radius),
                min(1.0, proportion + radius),
            ]
        par2_low = _percentile(par2_samples[group], 0.25)
        par2_high = _percentile(par2_samples[group], 0.75)
        uncertainty["par2_wall_time_ns"] = (
            [par2_low, par2_high]
            if par2_low is not None and par2_high is not None
            else None
        )
        row["uncertainty_json"] = json.dumps(
            uncertainty, sort_keys=True, separators=(",", ":")
        )
        rows.append(row)
    parallel_baselines: dict[tuple[str, str, str], dict[str, Any]] = {}
    for row in rows:
        if row["study_id"] == "E11" and row.get("threads") == 1 and isinstance(
            row.get("median_wall_time_ns"), (int, float)
        ):
            parallel_baselines[(row["dataset_id"], row["algorithm_id"], row["variant_id"])] = row
    for row in rows:
        if row["study_id"] != "E11":
            continue
        baseline_row = parallel_baselines.get(
            (row["dataset_id"], row["algorithm_id"], row["variant_id"])
        )
        baseline = baseline_row.get("median_wall_time_ns") if baseline_row else None
        runtime = row.get("median_wall_time_ns")
        threads = row.get("threads")
        if isinstance(baseline, (int, float)) and isinstance(runtime, (int, float)) and runtime > 0:
            row["speedup"] = baseline / runtime
            if isinstance(threads, (int, float)) and threads > 0:
                row["parallel_efficiency"] = row["speedup"] / threads
        observed = row.get("median_observed_workers")
        if isinstance(observed, (int, float)) and isinstance(threads, (int, float)) and threads > 0:
            row["worker_utilization"] = observed / threads
        uncertainty = json.loads(row["uncertainty_json"])
        if (
            baseline_row
            and isinstance(threads, (int, float))
            and threads > 0
            and isinstance(runtime, (int, float))
            and runtime > 0
        ):
            baseline_interval = json.loads(
                baseline_row["uncertainty_json"]
            ).get("median_wall_time_ns")
            runtime_interval = uncertainty.get("median_wall_time_ns")
            if (
                baseline_interval
                and runtime_interval
                and runtime_interval[0] > 0
            ):
                speedup_interval = [
                    baseline_interval[0] / runtime_interval[1],
                    baseline_interval[1] / runtime_interval[0],
                ]
                uncertainty["speedup"] = speedup_interval
                uncertainty["parallel_efficiency"] = [
                    value / threads for value in speedup_interval
                ]
        observed_interval = uncertainty.get("median_observed_workers")
        if observed_interval and isinstance(threads, (int, float)) and threads > 0:
            uncertainty["worker_utilization"] = [
                value / threads for value in observed_interval
            ]
        row["uncertainty_json"] = json.dumps(
            uncertainty, sort_keys=True, separators=(",", ":")
        )
    summaries = run_root / "summaries"
    summaries.mkdir(parents=True, exist_ok=True)
    write_jsonl(summaries / "aggregate_records.jsonl", rows)
    fields = list(rows[0]) if rows else ["study_id"]
    with (summaries / "aggregate_records.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    comparisons: list[dict[str, Any]] = []
    study_datasets = sorted({key[:2] for key in paired_runtime})
    for study_id, dataset_id in study_datasets:
        methods = sorted({key[3] for key in paired_runtime if key[:2] == (study_id, dataset_id)})
        reference = "pace-b" if "pace-b" in methods else "exh-profile" if "exh-profile" in methods else methods[0]
        for method in methods:
            if method == reference:
                continue
            method_values = {
                (key[2], key[4]): value for key, value in paired_runtime.items()
                if key[:2] == (study_id, dataset_id) and key[3] == method
            }
            reference_values = {
                (key[2], key[4]): value for key, value in paired_runtime.items()
                if key[:2] == (study_id, dataset_id) and key[3] == reference
            }
            query_ids = sorted(set(method_values) & set(reference_values))
            pairs = [(method_values[query], reference_values[query]) for query in query_ids]
            p_value, effect = _wilcoxon(pairs)
            ratios = [left / right for left, right in pairs if right > 0]
            comparisons.append({
                "study_id": study_id, "dataset_id": dataset_id, "method": method,
                "reference": reference, "paired_queries": len(pairs),
                "median_runtime_ratio": statistics.median(ratios) if ratios else None,
                "p_value": p_value, "holm_p_value": None, "rank_biserial": effect,
            })
    _holm(comparisons)
    comparison_fields = ["study_id", "dataset_id", "method", "reference", "paired_queries", "median_runtime_ratio", "p_value", "holm_p_value", "rank_biserial"]
    with (summaries / "paired_comparisons.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=comparison_fields)
        writer.writeheader()
        writer.writerows(comparisons)
    status_counts = collections.Counter(record["completion_status"] for record in records)
    report = {"aggregate_rows": len(rows), "paired_comparisons": len(comparisons), "status_counts": dict(sorted(status_counts.items()))}
    atomic_write_json(summaries / "summary_report.json", report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        report = summarize(repo_path(design["paths"]["results_root"]) / args.run_id, design)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"summary: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
