#!/usr/bin/env python3
"""Report independent, duplicate-aware progress for the T03 scalability run."""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import repo_path
from experiments.scripts.common.status import TERMINAL_STATUSES


ALGORITHMS = ("pace-b", "iscope", "allfp")


def _jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def _skipped_algorithms(root: Path) -> set[str]:
    path = root / "provenance" / "skipped_algorithms.json"
    if not path.is_file():
        return set()
    value = json.loads(path.read_text(encoding="utf-8"))
    algorithms = value.get("skip_algorithms", [])
    if not isinstance(algorithms, list) or not all(
        isinstance(item, str) for item in algorithms
    ):
        raise ValueError(
            "provenance/skipped_algorithms.json must contain a string "
            "skip_algorithms list"
        )
    return set(algorithms)


def progress(run_root: Path) -> dict[str, Any]:
    root = repo_path(run_root)
    skipped_algorithms = _skipped_algorithms(root)
    logical_path = root / "plan/matrices/t03.jsonl"
    execution_path = root / "plan/reconciliation/execution_manifest.jsonl"
    if execution_path.is_file():
        execution_jobs = _jsonl(execution_path)
    elif logical_path.is_file():
        execution_jobs = _jsonl(logical_path)
    else:
        raise ValueError(
            f"missing execution manifest or logical matrix under {root}"
        )
    jobs = _jsonl(logical_path) if logical_path.is_file() else execution_jobs
    planned_by_id = {str(row["job_id"]): row for row in jobs}
    if len(planned_by_id) != len(jobs):
        raise ValueError("logical T03 manifest contains duplicate job IDs")
    execution_ids = {str(row["job_id"]) for row in execution_jobs}
    unknown_execution = sorted(execution_ids - set(planned_by_id))
    if unknown_execution:
        raise ValueError(
            f"execution manifest references unplanned jobs: "
            f"{unknown_execution[:3]}"
        )

    reuse_path = root / "plan/reconciliation/effective_result_index.jsonl"
    reused = _jsonl(reuse_path)
    reused_ids = {str(row["target_job_id"]) for row in reused}
    unknown_reuse = sorted(reused_ids - set(planned_by_id))
    if unknown_reuse:
        raise ValueError(f"reuse index references unplanned jobs: {unknown_reuse[:3]}")
    overlap = sorted(execution_ids & reused_ids)
    if overlap:
        raise ValueError(
            f"reused jobs also appear in the execution manifest: {overlap[:3]}"
        )

    records_by_job: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    raw_root = root / "raw/T03"
    if raw_root.is_dir():
        for path in sorted(raw_root.glob("*.jsonl")):
            for row in _jsonl(path):
                job_id = str(row.get("job_id", ""))
                if job_id:
                    records_by_job[job_id].append(row)

    unknown_raw = sorted(set(records_by_job) - set(planned_by_id))
    duplicates = {
        job_id: len(rows)
        for job_id, rows in records_by_job.items()
        if len(rows) > 1
    }
    algorithms: dict[str, Any] = {}
    for algorithm in ALGORITHMS:
        algorithm_jobs = {
            job_id for job_id, row in planned_by_id.items()
            if row.get("algorithm_id") == algorithm
        }
        algorithm_reused = algorithm_jobs & reused_ids
        algorithm_raw = algorithm_jobs & set(records_by_job)
        terminal_raw: set[str] = set()
        status_counts: collections.Counter[str] = collections.Counter()
        for job_id in sorted(algorithm_raw):
            statuses = {
                str(row.get("completion_status", ""))
                for row in records_by_job[job_id]
            }
            status_counts.update(statuses)
            if statuses and statuses <= TERMINAL_STATUSES:
                terminal_raw.add(job_id)
        completed = algorithm_reused | terminal_raw
        planned = len(algorithm_jobs)
        skipped_by_request = (
            planned - len(completed)
            if algorithm in skipped_algorithms
            else 0
        )
        completed_effective = len(completed) + skipped_by_request
        algorithms[algorithm] = {
            "planned": planned,
            "reused_historical": len(algorithm_reused),
            "raw_unique": len(algorithm_raw),
            "terminal_unique": len(terminal_raw),
            "skipped_by_request": skipped_by_request,
            "completed_effective": completed_effective,
            "remaining": planned - completed_effective,
            "completion_fraction": (completed_effective / planned) if planned else 1.0,
            "status_counts": dict(sorted(status_counts.items())),
            "duplicate_job_ids": sum(job_id in duplicates for job_id in algorithm_jobs),
        }

    completed_total = sum(value["completed_effective"] for value in algorithms.values())
    report = {
        "schema_version": 1,
        "protocol": "t03-scalability-progress-v2",
        "run_root": root.as_posix(),
        "planned_total": len(jobs),
        "execution_manifest_total": len(execution_jobs),
        "skip_algorithms": sorted(skipped_algorithms),
        "reused_historical_total": len(reused_ids),
        "completed_effective_total": completed_total,
        "remaining_total": len(jobs) - completed_total,
        "completion_fraction": completed_total / len(jobs) if jobs else 1.0,
        "algorithms": algorithms,
        "raw_duplicate_job_ids": dict(sorted(duplicates.items())),
        "unknown_raw_job_ids": unknown_raw,
        "passed_identity_audit": not duplicates and not unknown_raw,
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = progress(args.run_root)
        if args.output:
            atomic_write_json(repo_path(args.output), report)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["passed_identity_audit"] else 1
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as failure:
        print(f"T03 scalability progress: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
