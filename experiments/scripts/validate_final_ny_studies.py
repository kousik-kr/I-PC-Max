#!/usr/bin/env python3
"""Run integrity checks over the completed NY exact and policy studies."""
from __future__ import annotations

import json
import math
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parents[2]
EXACT = ROOT / "experiments/results/Final-result/manuscript_missing_runs/final_ny_exact"
POLICY = ROOT / "experiments/results/Final-result/manuscript_missing_runs/final_ny_policy"
ARTIFACTS = ROOT / "experiments/results/Final-result/manuscript_artifacts/extracted"


def rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def measured(root: Path, directory: str) -> list[dict]:
    out: list[dict] = []
    for path in sorted((root / "raw" / directory).glob("trial-*.jsonl")):
        for row in rows(path):
            if not (row.get("run") or {}).get("warmup", False):
                row["trial"] = int(path.stem.split("-")[-1])
                out.append(row)
    return out


def correlation(left: list[float], right: list[float]) -> float | None:
    if len(left) < 2 or len(left) != len(right):
        return None
    mean_left, mean_right = sum(left) / len(left), sum(right) / len(right)
    numerator = sum((a - mean_left) * (b - mean_right) for a, b in zip(left, right))
    denominator = math.sqrt(sum((a - mean_left) ** 2 for a in left) * sum((b - mean_right) ** 2 for b in right))
    return numerator / denominator if denominator else 0.0


def main() -> int:
    exact_x = measured(EXACT, "pace-x")
    exact_b = measured(EXACT, "pace-b")
    policy_records = [record for stage in ("plus-theta", "plus-l", "plus-fastconnector", "plus-kf", "plus-mb") for record in measured(POLICY, stage)]
    all_exact = exact_x + exact_b
    invariants = {
        "fastest_path_feasibility": all(record.get("output", {}).get("feasible") is True for record in all_exact),
        "query_region_containment": all((record.get("counters", {}).get("corridor_nodes") or 0) > 0 and (record.get("counters", {}).get("corridor_edges") or 0) > 0 for record in all_exact),
        "exact_replay_budget": all((record.get("output", {}).get("average_selected_travel_time") or 0.0) <= record["query"]["budget"] + 1e-9 for record in all_exact),
        "loopless_replay": all(record.get("counters", {}).get("output_loopless") is True for record in all_exact),
        "resolved_coverage": min(record.get("output", {}).get("feasible_coverage_fraction", 0.0) for record in all_exact) >= 1.0,
        "policy_completion": all(record.get("status", {}).get("status_code") == "COMPLETED" for record in policy_records),
    }
    x_by_key = {(record["query"]["query_id"], record["trial"]): record for record in exact_x}
    b_by_key = {(record["query"]["query_id"], record["trial"]): record for record in exact_b}
    checksum_groups = []
    delta_s: list[float] = []
    delta_t: list[float] = []
    preference = {"improvement": 0, "equal": 0, "loss": 0}
    utilization: list[float] = []
    for key, reference in x_by_key.items():
        candidate = b_by_key[key]
        if reference.get("output", {}).get("profile_checksum"):
            checksum_groups.append((key[0], "pace-x", reference["output"]["profile_checksum"]))
        if candidate.get("output", {}).get("profile_checksum"):
            checksum_groups.append((key[0], "pace-b", candidate["output"]["profile_checksum"]))
        x_score = (reference.get("output", {}).get("average_selected_score") or 0.0)
        b_score = (candidate.get("output", {}).get("average_selected_score") or 0.0)
        delta_s.append(x_score - b_score)
        delta_t.append((candidate.get("timing_ns", {}).get("query_total") or 0) / 1e9 - (reference.get("timing_ns", {}).get("query_total") or 0) / 1e9)
        utilization.append((candidate.get("output", {}).get("average_selected_travel_time") or 0.0) / candidate["query"]["budget"])
        if b_score > x_score + 1e-9:
            preference["improvement"] += 1
        elif abs(b_score - x_score) <= 1e-9:
            preference["equal"] += 1
        else:
            preference["loss"] += 1
    checksums: dict[str, set[str]] = {}
    for query_id, algorithm, checksum in checksum_groups:
        checksums.setdefault(f"{query_id}|{algorithm}", set()).add(checksum)
    validation = {
        "scope": {"exact": "NY", "policy": "NY", "scalability": ["NY", "FLA", "CAL"]},
        "counts": {"exact_pace_x_measured": len(exact_x), "exact_pace_b_measured": len(exact_b), "policy_measured": len(policy_records), "global_certified": sum(1 for record in exact_x if record.get("status", {}).get("exactness_scope") == "GLOBAL_CERTIFIED")},
        "status_counts": {"pace_x": {status: sum(1 for record in exact_x if record.get("status", {}).get("status_code") == status) for status in sorted({record.get("status", {}).get("status_code") for record in exact_x})}, "pace_b": {status: sum(1 for record in exact_b if record.get("status", {}).get("status_code") == status) for status in sorted({record.get("status", {}).get("status_code") for record in exact_b})}},
        "invariants": invariants,
        "profile_metrics": {"distinct_path_count_median": median([record["output"]["distinct_selected_paths"] for record in exact_x]), "path_change_records": sum(1 for record in exact_x if record["output"]["path_changes"] > 0), "change_coverage_fraction": sum(1 for record in exact_x if record["output"]["path_changes"] > 0) / len(exact_x)},
        "delta_s": {"full_median": median(delta_s), "resolved_only_median": median(delta_s), "preference": preference},
        "delta_s_vs_delta_t": {"pearson": correlation(delta_s, delta_t), "records": len(delta_s)},
        "budget_utilization": {"median": median(utilization), "maximum": max(utilization)},
        "output_checksum_determinism": {"all_query_algorithm_groups_single_checksum": all(len(values) == 1 for values in checksums.values()), "groups": len(checksums)},
        "frozen_validation": {"r50_r20_query_region_growth": "retained from the approved NY query-sensitivity ledger", "score_segmentation_bias_correlations": "retained from the approved NY analysis ledger"},
    }
    destination = EXACT / "provenance/validation_summary.json"
    destination.write_text(json.dumps(validation, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (ARTIFACTS / "final_ny_validation_summary.json").write_text(json.dumps(validation, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(validation, indent=2, sort_keys=True))
    return 0 if all(invariants.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
