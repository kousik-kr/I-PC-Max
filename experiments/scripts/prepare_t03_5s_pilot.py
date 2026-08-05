#!/usr/bin/env python3
"""Select a deterministic easy/hard T03 pilot without changing job identity."""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import (
    atomic_write_json,
    atomic_write_text,
    write_jsonl,
)
from experiments.scripts.common.config import repo_path
from experiments.scripts.common.hashing import sha256_file


ALGORITHMS = ("pace-b", "iscope", "allfp")
DATASETS = ("NY", "FLA", "CAL")
STRATA = {
    "easy": {"window_minutes": 120, "budget_overhead": 0.10},
    "hard": {"window_minutes": 360, "budget_overhead": 0.50},
}


def _rows(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def select_pilot(
    execution_manifest: Path,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    jobs = _rows(execution_manifest)
    queries: dict[tuple[str, str], dict[str, Any]] = {}
    for manifest in sorted({str(job["manifest"]) for job in jobs}):
        path = repo_path(manifest)
        for row in _rows(path):
            key = (str(row.get("dataset_id")), str(row.get("query_id")))
            if key in queries and queries[key] != row:
                raise ValueError(f"conflicting query definition: {key}")
            queries[key] = row

    by_query: dict[tuple[str, str], list[dict[str, Any]]] = (
        collections.defaultdict(list)
    )
    for job in jobs:
        if job.get("study_id") != "T03" or int(job.get("trial_id", -1)) != 0:
            continue
        if job.get("dataset_id") not in DATASETS:
            continue
        by_query[(str(job["dataset_id"]), str(job["query_id"]))].append(job)

    selected: list[dict[str, Any]] = []
    strata: list[dict[str, Any]] = []
    for dataset in DATASETS:
        for distance_band in range(1, 6):
            for difficulty, axis in STRATA.items():
                candidates: list[
                    tuple[tuple[float, int, int, str], list[dict[str, Any]]]
                ] = []
                for (job_dataset, query_id), query_jobs in by_query.items():
                    if job_dataset != dataset:
                        continue
                    query = queries.get((dataset, query_id))
                    if not query or int(query.get("distance_bin", -1)) != distance_band:
                        continue
                    if any(
                        (job.get("axis") or {}).get(name) != value
                        for name, value in axis.items()
                        for job in query_jobs
                    ):
                        continue
                    algorithms = {str(job.get("algorithm_id")) for job in query_jobs}
                    if algorithms != set(ALGORITHMS):
                        continue
                    representative = query_jobs[0]
                    candidates.append((
                        (
                            float(query["lower_bound_distance"]),
                            int(representative["pair_index"]),
                            int(representative["time_center"]),
                            query_id,
                        ),
                        query_jobs,
                    ))
                if not candidates:
                    raise ValueError(
                        f"missing {difficulty} pilot stratum for "
                        f"{dataset}/distance-band-{distance_band}"
                    )
                candidates.sort(key=lambda item: item[0])
                chosen_key, chosen_jobs = (
                    candidates[0] if difficulty == "easy" else candidates[-1]
                )
                chosen_jobs = sorted(
                    chosen_jobs,
                    key=lambda job: ALGORITHMS.index(job["algorithm_id"]),
                )
                selected.extend(chosen_jobs)
                strata.append({
                    "dataset_id": dataset,
                    "distance_band": distance_band,
                    "difficulty": difficulty,
                    "query_id": chosen_key[3],
                    "lower_bound_distance": chosen_key[0],
                    "pair_index": chosen_key[1],
                    "time_center": chosen_key[2],
                    "axis": axis,
                    "algorithms": list(ALGORITHMS),
                    "jobs": len(chosen_jobs),
                })

    selected.sort(key=lambda job: (
        DATASETS.index(job["dataset_id"]),
        int((queries[(job["dataset_id"], job["query_id"])]).get(
            "distance_bin"
        )),
        job["query_id"],
        ALGORITHMS.index(job["algorithm_id"]),
    ))
    if len(selected) != 90 or len({job["job_id"] for job in selected}) != 90:
        raise ValueError(
            f"pilot must contain 90 unique jobs, observed {len(selected)}"
        )
    report = {
        "schema_version": 1,
        "protocol": "t03-five-second-stratified-pilot-v1",
        "source_execution_manifest": execution_manifest.as_posix(),
        "datasets": list(DATASETS),
        "distance_bands": [1, 2, 3, 4, 5],
        "difficulty_axes": STRATA,
        "algorithms": list(ALGORITHMS),
        "jobs": len(selected),
        "queries": len({(job["dataset_id"], job["query_id"]) for job in selected}),
        "strata": strata,
        "full_experiment_launched": False,
    }
    return selected, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execution-manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    try:
        source = repo_path(args.execution_manifest)
        selected, report = select_pilot(source)
        output = repo_path(args.output)
        manifest = output / "pilot_manifest.jsonl"
        report_path = output / "pilot_report.json"
        if args.validate_only:
            if not manifest.is_file() or _rows(manifest) != selected:
                raise ValueError("pilot manifest differs from deterministic selection")
            existing = json.loads(report_path.read_text(encoding="utf-8"))
            for key, value in report.items():
                if existing.get(key) != value:
                    raise ValueError(f"pilot report differs at {key}")
        else:
            output.mkdir(parents=True, exist_ok=True)
            write_jsonl(manifest, selected)
            report["pilot_manifest_sha256"] = sha256_file(manifest)
            atomic_write_json(report_path, report)
            atomic_write_text(
                output / "README.md",
                "\n".join([
                    "# T03 five-second stratified pilot",
                    "",
                    "- 3 datasets × 5 distance bands × 2 difficulty strata",
                    "- 3 matched algorithms per query",
                    "- 90 jobs total; trial 0 only",
                    "- easy: W=120, rho=0.10",
                    "- hard: W=360, rho=0.50",
                    "- the selected rows retain their full-run job IDs",
                    "",
                ]),
            )
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as failure:
        print(f"T03 pilot selection: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
