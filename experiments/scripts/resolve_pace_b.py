#!/usr/bin/env python3
"""Freeze PACE-B L/K from E02 using the declared disjoint-pilot rule."""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import statistics
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import load_design, repo_path
from experiments.scripts.common.hashing import sha256_file


def _rows(paths: list[Path]) -> list[dict[str, Any]]:
    return [json.loads(line) for path in paths for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def resolve(run_root: Path) -> dict[str, Any]:
    matrix = run_root / "plan" / "matrices" / "e02.jsonl"
    plans = {row["job_id"]: row for row in _rows([matrix])}
    raw = _rows(sorted((run_root / "raw" / "E02").glob("*.jsonl")))
    metrics: dict[tuple[int, int], list[tuple[float, float, float, float]]] = collections.defaultdict(list)
    missing_relative_gap = 0
    for record in raw:
        plan = plans.get(record["job_id"])
        java = record.get("java_record") or {}
        if not plan or record.get("completion_status") != "SUCCESS":
            continue
        quality = java.get("quality", {})
        path_agreement = quality.get("path_agreement_fraction")
        relative_gap = quality.get("relative_score_gap_percent")
        runtime = java.get("timing_ns", {}).get("query_total")
        memory = java.get("memory_bytes", {}).get("peak_rss")
        if relative_gap is None:
            missing_relative_gap += 1
            continue
        if all(isinstance(value, (int, float)) for value in (path_agreement, relative_gap, runtime, memory)):
            axis = plan["axis"]
            metrics[(int(axis["anchor_limit"]), int(axis["k"]))].append((path_agreement, relative_gap, runtime, memory))
    if not metrics:
        detail = "no complete E02 quality rows"
        if missing_relative_gap:
            detail += f"; {missing_relative_gap} rows lack relative_score_gap_percent"
        raise ValueError(detail)
    candidates = []
    for (anchor_limit, k), values in sorted(metrics.items()):
        agreement = statistics.mean(value[0] for value in values)
        gap = statistics.mean(value[1] for value in values)
        runtime = statistics.median(value[2] for value in values)
        memory = max(value[3] for value in values)
        candidates.append({"anchor_limit": anchor_limit, "k": k, "path_agreement": agreement, "relative_score_gap_percent": gap, "median_runtime_ns": runtime, "peak_memory_bytes": memory, "queries": len(values)})
    passing = [row for row in candidates if row["path_agreement"] >= 0.99 and row["relative_score_gap_percent"] <= 1.0]
    warning = None
    if passing:
        selected = min(passing, key=lambda row: (row["median_runtime_ns"], row["peak_memory_bytes"], row["k"], row["anchor_limit"]))
    else:
        selected = min(candidates, key=lambda row: (row["relative_score_gap_percent"], row["median_runtime_ns"], row["peak_memory_bytes"], row["k"], row["anchor_limit"]))
        warning = "PILOT_TARGET_NOT_MET"
    result = {
        "schema_version": 1,
        "rule_version": "paper-q1-pilot-v1",
        "pilot_manifest_checksum": sha256_file(matrix),
        "reference_scope": "strongest-completing-reference",
        "anchor_limit": selected["anchor_limit"],
        "k": selected["k"],
        "selection_metrics": selected,
        "warning": warning,
        "all_candidates": candidates,
    }
    atomic_write_json(run_root / "provenance" / "resolved_pace_b.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        result = resolve(repo_path(design["paths"]["results_root"]) / args.run_id)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"PACE-B resolver: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
