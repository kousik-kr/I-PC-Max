#!/usr/bin/env python3
"""Create analysis-ready CSV summaries without dropping failed queries."""
from __future__ import annotations

import argparse
import collections
import csv
import json
import math
from pathlib import Path
import statistics


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1)]


def read(paths: list[Path]) -> list[dict]:
    records: list[dict] = []
    for path in paths:
        files = sorted(path.glob("*.jsonl")) if path.is_dir() else [path]
        for file in files:
            records.extend(json.loads(line) for line in file.read_text(encoding="utf-8").splitlines() if line.strip())
    return [record for record in records if not record["run"].get("warmup")]


def write(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = sorted({field for row in rows for field in row}) or ["empty"]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def flat(record: dict) -> dict:
    return {
        "run_id": record["run"]["run_id"], "experiment_name": record["run"]["experiment_name"],
        "query_id": record["query"]["query_id"], "algorithm": record["configuration"]["algorithm"],
        "ablation": record["configuration"]["ablation"], "threads": record["configuration"]["threads"],
        "repetition": record["run"]["repetition"], "status": record["status"]["status_code"],
        "runtime_ns": record["timing_ns"].get("query_total"), "peak_memory_bytes": record["memory_bytes"].get("peak_rss"),
        "profile_checksum": record["output"].get("profile_checksum"),
        "path_agreement_fraction": record["quality"].get("path_agreement_fraction"),
        "integrated_score_regret": record["quality"].get("integrated_score_regret"),
    }


def aggregate(records: list[dict], keys: tuple[str, ...]) -> list[dict]:
    groups: dict[tuple, list[dict]] = collections.defaultdict(list)
    flats = [flat(record) for record in records]
    for row in flats:
        groups[tuple(row[key] for key in keys)].append(row)
    rows: list[dict] = []
    for key, group in sorted(groups.items(), key=lambda item: str(item[0])):
        result = dict(zip(keys, key))
        runtimes = [row["runtime_ns"] for row in group if isinstance(row["runtime_ns"], (int, float))]
        agreements = [row["path_agreement_fraction"] for row in group if isinstance(row["path_agreement_fraction"], (int, float))]
        regrets = [row["integrated_score_regret"] for row in group if isinstance(row["integrated_score_regret"], (int, float))]
        total = len(group)
        result.update({
            "observations": total, "runtime_valid_observations": len(runtimes),
            "median_runtime_ns": statistics.median(runtimes) if runtimes else None,
            "p95_runtime_ns": percentile(runtimes, .95),
            "mean_path_agreement": statistics.fmean(agreements) if agreements else None,
            "median_path_agreement": statistics.median(agreements) if agreements else None,
            "mean_integrated_regret": statistics.fmean(regrets) if regrets else None,
            "completion_fraction": sum(row["status"] in {"COMPLETED", "NO_FEASIBLE_PATH"} for row in group) / total,
            "timeout_fraction": sum(row["status"] == "TIMEOUT" for row in group) / total,
            "out_of_memory_fraction": sum(row["status"] == "OUT_OF_MEMORY" for row in group) / total,
            "peak_memory_bytes": max((row["peak_memory_bytes"] for row in group if isinstance(row["peak_memory_bytes"], (int, float))), default=None),
        })
        rows.append(result)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    records = read(args.inputs)
    write(args.output_dir / "per_run.csv", [flat(record) for record in records])
    write(args.output_dir / "per_query_method.csv", aggregate(records, ("query_id", "algorithm", "ablation")))
    method = aggregate(records, ("algorithm", "ablation"))
    write(args.output_dir / "per_method_summary.csv", method)
    write(args.output_dir / "exactness_summary.csv", [row for row in method if row["algorithm"] in {"pace-x", "exh-profile", "pl-exact"}])
    write(args.output_dir / "quality_runtime_summary.csv", method)
    write(args.output_dir / "ablation_summary.csv", [row for row in method if row["ablation"] != "none"])
    write(args.output_dir / "parallelism_summary.csv", aggregate(records, ("algorithm", "threads")))
    failures = [flat(record) for record in records if record["status"]["status_code"] not in {"COMPLETED", "NO_FEASIBLE_PATH"}]
    write(args.output_dir / "failure_summary.csv", failures)
    print(json.dumps({"records": len(records), "output_dir": str(args.output_dir)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
