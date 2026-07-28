#!/usr/bin/env python3
"""Validate planned coverage, terminal records, provenance, and PACE claims."""
from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, atomic_write_text
from experiments.scripts.common.config import load_design, repo_path
from experiments.scripts.common.provenance import physical_core_count
from experiments.scripts.common.status import TERMINAL_STATUSES


def _jsonl(paths: list[Path]) -> list[dict[str, Any]]:
    rows = []
    for path in paths:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if line.strip():
                try:
                    rows.append(json.loads(line, parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value))))
                except Exception as failure:
                    raise ValueError(f"{path}:{number}: malformed JSON: {failure}") from failure
    return rows


def _expected_cells(design: dict[str, Any]) -> collections.Counter[tuple[Any, ...]]:
    """Independently enumerate the declarative study Cartesian products."""
    result: collections.Counter[tuple[Any, ...]] = collections.Counter()
    for study in design["study_definitions"]:
        if study.get("mode") != "execute":
            continue
        configured = study.get("datasets", "all")
        datasets = design["datasets"] if configured == "all" else configured
        for dataset in datasets:
            if dataset != "demo" and dataset not in design["datasets"]:
                continue
            for algorithm in study.get("algorithms", []):
                if algorithm.get("datasets") and dataset not in algorithm["datasets"]:
                    continue
                for axis in algorithm.get("axes", study.get("axes", [{}])):
                    threads = int(axis.get(
                        "threads", algorithm.get("parameters", {}).get("threads", 1)
                    ))
                    if study.get("filter_threads_to_physical_cores") and threads > physical_core_count():
                        continue
                    pair_count = int(algorithm.get(
                        "pairs_per_dataset", study.get("pairs_per_dataset", 0)
                    ))
                    for pair in range(1, pair_count + 1):
                        for center in study.get("centers", [0]):
                            for trial in range(int(study.get("trials", 1))):
                                result[(
                                    study["study_id"], dataset, algorithm["id"],
                                    algorithm.get("variant", algorithm["id"]),
                                    json.dumps(axis, sort_keys=True, separators=(",", ":")),
                                    pair, center, trial,
                                )] += 1
    return result


def validate_planned_cells(
    planned: list[dict[str, Any]],
    design: dict[str, Any],
) -> dict[str, Any]:
    actual = collections.Counter(
        (
            job["study_id"], job["dataset_id"], job["algorithm_id"],
            job["variant_id"],
            json.dumps(job.get("axis", {}), sort_keys=True, separators=(",", ":")),
            job["pair_index"], job["time_center"], job["trial_id"],
        )
        for job in planned
    )
    expected = _expected_cells(design)
    missing = expected - actual
    extra = actual - expected
    duplicates = sum(count - 1 for count in actual.values() if count > 1)
    return {
        "expected_formula_cells": sum(expected.values()),
        "actual_planned_cells": len(planned),
        "duplicate_planned_cells": duplicates,
        "missing_formula_cells": sum(missing.values()),
        "unexpected_formula_cells": sum(extra.values()),
        "passed": not duplicates and not missing and not extra,
        "_actual": actual,
        "_expected": expected,
        "_missing": missing,
        "_extra": extra,
    }


def validate(run_root: Path, design: dict[str, Any]) -> dict[str, Any]:
    plan_files = sorted((run_root / "plan" / "matrices").glob("e*.jsonl"))
    raw_files = sorted((run_root / "raw").rglob("*.jsonl")) if (run_root / "raw").is_dir() else []
    planned = _jsonl(plan_files)
    records = _jsonl(raw_files)
    errors: list[str] = []
    warnings: list[str] = []
    plans = {job["job_id"]: job for job in planned}
    if len(plans) != len(planned):
        errors.append("duplicate job_id in matrix manifests")
    cell_validation = validate_planned_cells(planned, design)
    actual_cells = cell_validation["_actual"]
    expected_cells = cell_validation["_expected"]
    missing_cells = cell_validation["_missing"]
    extra_cells = cell_validation["_extra"]
    if missing_cells:
        errors.append(f"missing {sum(missing_cells.values())} independently enumerated planned cells")
    if extra_cells:
        errors.append(f"found {sum(extra_cells.values())} unexpected planned cells")
    by_job: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for record in records:
        by_job[record.get("job_id")].append(record)
    for job_id, duplicates in by_job.items():
        if len(duplicates) != 1:
            errors.append(f"job {job_id} has {len(duplicates)} terminal records")
    missing = sorted(set(plans) - set(by_job))
    unexpected = sorted(set(by_job) - set(plans))
    if missing:
        errors.append(f"missing {len(missing)} planned terminal records")
    if unexpected:
        errors.append(f"found {len(unexpected)} unplanned terminal records")
    deterministic: dict[tuple[Any, ...], set[str]] = collections.defaultdict(set)
    thread_determinism: dict[tuple[Any, ...], set[str]] = collections.defaultdict(set)
    exact_checksums: dict[tuple[str, str], dict[str, str]] = collections.defaultdict(dict)
    for job_id, group in by_job.items():
        record = group[0]
        plan = plans.get(job_id)
        if not plan:
            continue
        if record.get("input_hash") != plan.get("input_hash"):
            errors.append(f"input hash mismatch for {job_id}")
        if record.get("completion_status") not in TERMINAL_STATUSES:
            errors.append(f"invalid terminal status for {job_id}")
        java = record.get("java_record")
        if record.get("completion_status") == "SUCCESS" and not isinstance(java, dict):
            errors.append(f"successful job {job_id} has no Java terminal record")
            continue
        if not isinstance(java, dict):
            continue
        query = java.get("query", {})
        dataset = java.get("dataset", {})
        configuration = java.get("configuration", {})
        status = java.get("status", {})
        counters = java.get("counters", {})
        output = java.get("output", {})
        values = [value for section in java.values() if isinstance(section, dict) for value in section.values()]
        if any(isinstance(value, float) and not math.isfinite(value) for value in values):
            errors.append(f"non-finite value in {job_id}")
        interval_end = query.get("interval_end")
        budget = query.get("budget")
        support_end = dataset.get("temporal_horizon_end")
        if all(isinstance(value, (int, float)) for value in (interval_end, budget, support_end)):
            if interval_end + budget > support_end + 1e-9:
                errors.append(f"query horizon exceeds support in {job_id}")
        algorithm = configuration.get("algorithm")
        exactness = status.get("exactness_scope")
        if algorithm == "pace-b" and exactness == "GLOBAL_CERTIFIED":
            errors.append(f"PACE-B is globally certified in {job_id}")
        if algorithm == "pace-x" and status.get("status_code") in {"LIMIT_EXCEEDED", "TIMEOUT", "OUT_OF_MEMORY"}:
            if status.get("completed") or exactness == "GLOBAL_CERTIFIED":
                errors.append(f"resource-limited PACE-X is marked completed/exact in {job_id}")
        checksum = output.get("profile_checksum")
        if record.get("completion_status") == "SUCCESS" and not checksum:
            errors.append(f"successful job {job_id} has no output checksum")
        if checksum:
            semantic = (plan["study_id"], plan["dataset_id"], plan["query_id"], plan["algorithm_id"], plan["variant_id"], json.dumps(plan["axis"], sort_keys=True))
            deterministic[semantic].add(checksum)
            thread_key = (plan["study_id"], plan["dataset_id"], plan["query_id"], plan["algorithm_id"], plan["variant_id"])
            thread_determinism[thread_key].add(checksum)
            if plan["study_id"] == "E01" and plan["algorithm_id"] in {"exh-profile", "pace-x"}:
                exact_checksums[(plan["dataset_id"], plan["query_id"])][plan["algorithm_id"]] = checksum
        cap_mapping = {
            "CONNECTOR_M_C": "connector_cap_hits",
            "BREAKPOINT_M_B": "breakpoint_cap_hits",
            "QUERY_WORK_M_Q": "query_work_cap_hits",
        }
        triggered = set(status.get("cap_triggered") or [])
        for cap, counter in cap_mapping.items():
            value = counters.get(counter)
            if isinstance(value, (int, float)) and value > 0 and cap not in triggered:
                errors.append(f"{job_id} has {counter}>0 without {cap} status")
        if triggered and status.get("generation_completion") != "RESOURCE_TRUNCATED":
            errors.append(f"{job_id} has cap flags without RESOURCE_TRUNCATED generation status")
    for key, checksums in deterministic.items():
        if len(checksums) > 1:
            errors.append(f"trial output checksum mismatch: {key}")
    for key, checksums in thread_determinism.items():
        study_id = key[0]
        if study_id == "E11" and len(checksums) > 1:
            errors.append(f"thread-count output checksum mismatch: {key}")
    for key, checksums in exact_checksums.items():
        if {"exh-profile", "pace-x"} <= checksums.keys() and checksums["exh-profile"] != checksums["pace-x"]:
            errors.append(f"PACE-X/exhaustive envelope mismatch: {key}")
    for study in design["study_definitions"]:
        if study.get("split") == "evaluation" and study.get("pairs_per_dataset") == 100:
            for dataset in [value for value in (design["datasets"] if study.get("datasets") == "all" else study.get("datasets", [])) if value != "demo"]:
                pair_indexes = {job["pair_index"] for job in planned if job["study_id"] == study["study_id"] and job["dataset_id"] == dataset}
                if pair_indexes and len(pair_indexes) != 100:
                    errors.append(f"{study['study_id']}/{dataset} has {len(pair_indexes)} evaluation pairs")
    failures = collections.Counter(record.get("completion_status") for record in records)
    report = {
        "schema_version": 1,
        "run_id": run_root.name,
        "planned_jobs": len(planned),
        "terminal_records": len(records),
        "expected_formula_cells": sum(expected_cells.values()),
        "missing_formula_cells": sum(missing_cells.values()),
        "unexpected_formula_cells": sum(extra_cells.values()),
        "duplicate_planned_cells":
            cell_validation["duplicate_planned_cells"],
        "status_counts": dict(sorted(failures.items())),
        "errors": errors,
        "warnings": warnings,
        "passed": not errors,
    }
    validation_dir = run_root / "release"
    atomic_write_json(validation_dir / "validation_report.json", report)
    lines = [
        "# PACE Q1 Validation Report", "", f"Run: `{run_root.name}`", "",
        f"Planned jobs: {len(planned)}", f"Terminal records: {len(records)}", "",
        f"Independently enumerated formula cells: {sum(expected_cells.values())}",
        f"Duplicate planned cells: {report['duplicate_planned_cells']}",
        f"Missing formula cells: {report['missing_formula_cells']}",
        f"Unexpected formula cells: {report['unexpected_formula_cells']}", "",
        "## Status Counts", "",
    ]
    lines.extend(f"- {key}: {value}" for key, value in sorted(failures.items()))
    lines.extend(["", "## Hard Gates", ""])
    if errors:
        lines.extend(f"- FAIL: {error}" for error in errors)
        lines.extend(["", "HARD_GATES_FAILED", ""])
    else:
        lines.extend(["- All plan, record, horizon, exactness, and determinism gates passed.", "", "ALL_HARD_GATES_PASSED", ""])
    atomic_write_text(validation_dir / "VALIDATION_REPORT.md", "\n".join(lines))
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        root = repo_path(design["paths"]["results_root"]) / args.run_id
        report = validate(root, design)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["passed"] else 1
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"validation: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
