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
    for path in sorted((run_root / "plan" / "matrices").glob("e*.jsonl")):
        plan_rows.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    plans = {row["job_id"]: row for row in plan_rows}
    metric_paths = {
        "wall_time_ns": ("timing_ns", "query_total"),
        "peak_rss_bytes": ("memory_bytes", "peak_rss"),
        "profile_cells_total": ("output", "final_profile_intervals"),
        "feasible_coverage": ("output", "feasible_coverage_fraction"),
        "path_agreement": ("quality", "path_agreement_fraction"),
        "score_regret": ("quality", "integrated_score_regret"),
    }
    trials: dict[tuple[str, str, str, str, str], dict[str, list[float]]] = collections.defaultdict(lambda: collections.defaultdict(list))
    completion: collections.Counter[tuple[str, str, str]] = collections.Counter()
    successful: collections.Counter[tuple[str, str, str]] = collections.Counter()
    par2_samples: dict[tuple[str, str, str], list[float]] = collections.defaultdict(list)
    for record in records:
        plan = plans.get(record["job_id"])
        if not plan:
            continue
        group = (plan["study_id"], plan["dataset_id"], plan["algorithm_id"])
        completion[group] += 1
        java = record.get("java_record") or {}
        runtime = java.get("timing_ns", {}).get("query_total")
        if record["completion_status"] == "SUCCESS" and isinstance(runtime, (int, float)):
            successful[group] += 1
            par2_samples[group].append(float(runtime))
            query_key = (*group, plan["query_id"], plan["variant_id"])
            for metric, (section, field) in metric_paths.items():
                value = java.get(section, {}).get(field)
                if isinstance(value, (int, float)):
                    trials[query_key][metric].append(float(value))
        else:
            par2_samples[group].append(2.0 * int(design["resources"]["timeout_seconds"]) * 1_000_000_000)
    query_medians: dict[tuple[str, str, str], dict[str, list[float]]] = collections.defaultdict(lambda: collections.defaultdict(list))
    paired_runtime: dict[tuple[str, str, str, str], float] = {}
    for key, metrics in trials.items():
        for metric, values in metrics.items():
            query_medians[key[:3]][metric].append(statistics.median(values))
        if metrics.get("wall_time_ns"):
            method = key[4] if key[4] != key[2] else key[2]
            paired_runtime[(key[0], key[1], key[3], method)] = statistics.median(metrics["wall_time_ns"])
    rows: list[dict[str, Any]] = []
    seed = int(design.get("seeds", {}).get("bootstrap", 20260726))
    for group in sorted(completion):
        metrics = query_medians.get(group, {})
        values = metrics.get("wall_time_ns", [])
        low, high = _bootstrap_median(values, seed + len(rows))
        q1 = _percentile(values, 0.25)
        q3 = _percentile(values, 0.75)
        rows.append({
            "schema_version": 1,
            "metric_definition_version": design["protocol"]["metric_definition_version"],
            "study_id": group[0], "dataset_id": group[1], "algorithm_id": group[2],
            "planned": completion[group], "successful": successful[group],
            "completion_rate": successful[group] / completion[group] if completion[group] else 0,
            "query_units": len(values),
            "median_wall_time_ns": statistics.median(values) if values else None,
            "iqr_wall_time_ns": q3 - q1 if q1 is not None and q3 is not None else None,
            "bootstrap_95_low_ns": low, "bootstrap_95_high_ns": high,
            "par2_wall_time_ns": statistics.mean(par2_samples[group]) if par2_samples[group] else None,
            "median_peak_rss_bytes": statistics.median(metrics["peak_rss_bytes"]) if metrics.get("peak_rss_bytes") else None,
            "median_profile_cells_total": statistics.median(metrics["profile_cells_total"]) if metrics.get("profile_cells_total") else None,
            "median_feasible_coverage": statistics.median(metrics["feasible_coverage"]) if metrics.get("feasible_coverage") else None,
            "median_path_agreement": statistics.median(metrics["path_agreement"]) if metrics.get("path_agreement") else None,
            "median_score_regret": statistics.median(metrics["score_regret"]) if metrics.get("score_regret") else None,
        })
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
            query_ids = sorted({key[2] for key in paired_runtime if key[:2] == (study_id, dataset_id) and key[3] == method} & {key[2] for key in paired_runtime if key[:2] == (study_id, dataset_id) and key[3] == reference})
            pairs = [(paired_runtime[(study_id, dataset_id, query, method)], paired_runtime[(study_id, dataset_id, query, reference)]) for query in query_ids]
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
