#!/usr/bin/env python3
"""Audit the matched 90-job T03 pilot and its resume safety."""
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
VALID_PROFILE_STATUSES = {
    "SUCCESS", "TIME_CAPPED_NOT_CERTIFIED", "PATH_CAPPED_NOT_CERTIFIED",
}
TIMEOUT_ONLY_ALGORITHMS = {"pace-b", "allfp"}


def _jsonl(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def audit(pilot_manifest: Path, raw_root: Path) -> dict[str, Any]:
    plans = _jsonl(repo_path(pilot_manifest))
    errors: list[str] = []
    by_plan = {str(row.get("job_id")): row for row in plans}
    if len(plans) != 90 or len(by_plan) != 90:
        errors.append(f"pilot requires 90 unique jobs; observed {len(plans)}/{len(by_plan)}")
    expected = collections.Counter({algorithm: 30 for algorithm in ALGORITHMS})
    actual = collections.Counter(str(row.get("algorithm_id")) for row in plans)
    if actual != expected:
        errors.append(f"algorithm counts differ: {dict(actual)}")
    strata = collections.Counter(
        (str(row.get("dataset_id")), int(row.get("trial_id", -1)))
        for row in plans
    )
    expected_strata = collections.Counter({(dataset, 0): 30 for dataset in ("NY", "FLA", "CAL")})
    if strata != expected_strata:
        errors.append(f"dataset/trial counts differ: {dict(strata)}")

    by_record: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    root = repo_path(raw_root)
    if root.is_dir():
        for path in sorted(root.rglob("*.jsonl")):
            for row in _jsonl(path):
                by_record[str(row.get("job_id"))].append(row)
    for job_id in sorted(by_plan):
        records = by_record.get(job_id, [])
        if len(records) != 1:
            errors.append(f"{job_id} has {len(records)} raw records")
            continue
        record = records[0]
        plan = by_plan[job_id]
        if record.get("input_hash") != plan.get("input_hash"):
            errors.append(f"{job_id} input hash mismatch")
        algorithm = str(plan.get("algorithm_id"))
        completion = str(record.get("completion_status"))
        if completion not in TERMINAL_STATUSES:
            errors.append(f"{job_id} has nonterminal status {completion}")
        java = record.get("java_record")
        if completion == "TIMEOUT" and algorithm in TIMEOUT_ONLY_ALGORITHMS:
            if isinstance(java, dict):
                errors.append(f"{job_id} stores Java payload for timeout-only result")
            continue
        if (
            algorithm in TIMEOUT_ONLY_ALGORITHMS
            and completion in {"TIME_CAPPED_NOT_CERTIFIED", "PATH_CAPPED_NOT_CERTIFIED"}
        ):
            errors.append(f"{job_id} stores a partial profile for timeout-only {algorithm}")
            continue
        if completion not in VALID_PROFILE_STATUSES:
            errors.append(f"{job_id} pilot ended as {completion}")
            continue
        if not isinstance(java, dict):
            errors.append(f"{job_id} has no Java record")
            continue
        configuration = java.get("configuration", {})
        status = java.get("status", {})
        counters = java.get("counters", {})
        timing = java.get("timing_ns", {})
        output = java.get("output", {})
        if configuration.get("algorithm") != algorithm:
            errors.append(f"{job_id} algorithm mismatch")
        expected_timeout = 5 if algorithm == "iscope" else 10
        if int(configuration.get("timeout_seconds", -1)) != expected_timeout:
            errors.append(f"{job_id} does not declare a {expected_timeout}-second cap")
        query_total = timing.get("query_total")
        query_cap_ns = (expected_timeout + 1) * 1_000_000_000
        if not isinstance(query_total, int) or query_total < 0 or query_total > query_cap_ns:
            errors.append(f"{job_id} has invalid capped query time {query_total}")
        if not output.get("profile_checksum"):
            errors.append(f"{job_id} has no canonical profile checksum")
        if counters.get("output_validation_contract") != (
            "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1"
        ):
            errors.append(f"{job_id} lacks exact replay validation evidence")
        if counters.get("output_loopless") is not True:
            errors.append(f"{job_id} lacks looplessness evidence")
        if algorithm == "pace-b" and counters.get("output_feasible") is not True:
            errors.append(f"{job_id} lacks a feasible preference-aware output")
        if algorithm == "iscope" and counters.get("output_feasible") is not True:
            zero_coverage_cap = (
                status.get("status_code") in {
                    "TIME_CAPPED_NOT_CERTIFIED", "PATH_CAPPED_NOT_CERTIFIED",
                }
                and status.get("exactness_scope") == "NOT_CERTIFIED"
                and counters.get("departure_interval_coverage") in {0, 0.0}
            )
            if not zero_coverage_cap:
                errors.append(
                    f"{job_id} has neither a feasible iSCOPE output nor a "
                    "non-certified zero-coverage cap"
                )
        if status.get("status_code") in {
            "TIME_CAPPED_NOT_CERTIFIED", "PATH_CAPPED_NOT_CERTIFIED",
        } and status.get("exactness_scope") != "NOT_CERTIFIED":
            errors.append(f"{job_id} certifies a capped result")
        if algorithm == "allfp":
            if counters.get("preference_score_used_for_search") is not False:
                errors.append(f"{job_id} allFP used preference score")
            if counters.get("pcmax_budget_used_for_search") is not False:
                errors.append(f"{job_id} allFP used PC-Max budget")
            if status.get("status_code") == "CERTIFIED_COMPLETE" and (
                counters.get("full_interval_coverage") is not True
                or status.get("exactness_scope") != "GLOBAL_CERTIFIED"
            ):
                errors.append(f"{job_id} invalid allFP exactness certificate")

    unexpected = sorted(set(by_record) - set(by_plan))
    if unexpected:
        errors.append(f"raw directory contains {len(unexpected)} unplanned pilot jobs")
    statuses = collections.Counter(
        str(rows[0].get("completion_status"))
        for job_id, rows in by_record.items()
        if job_id in by_plan and len(rows) == 1
    )
    return {
        "schema_version": 1,
        "protocol": "t03-five-second-pilot-audit-v1",
        "planned_jobs": len(plans),
        "terminal_records": sum(len(by_record.get(job_id, [])) for job_id in by_plan),
        "algorithm_counts": dict(sorted(actual.items())),
        "status_counts": dict(sorted(statuses.items())),
        "errors": errors,
        "passed": not errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pilot-manifest", type=Path, required=True)
    parser.add_argument("--raw-root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.pilot_manifest, args.raw_root)
        if args.output:
            atomic_write_json(repo_path(args.output), report)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["passed"] else 1
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as failure:
        print(f"T03 pilot audit: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
