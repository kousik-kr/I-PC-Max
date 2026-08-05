#!/usr/bin/env python3
"""Reconcile immutable ten-second PACE-B rows into a five-second T03 plan."""
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
from experiments.scripts.common.config import load_design, repo_path
from experiments.scripts.common.hashing import sha256_file, sha256_json


REUSABLE_FILE = "reusable_pace_b.jsonl"
EXECUTION_FILE = "execution_manifest.jsonl"
INDEX_FILE = "effective_result_index.jsonl"
REPORT_FILE = "reconciliation_report.json"
SUMMARY_FILE = "SUMMARY.md"
PACE_B_FIELDS = (
    "theta",
    "pivot_limit_l",
    "connector_limit_kc",
    "frontier_limit_kf",
    "connector_expansion_cap_mc",
    "breakpoint_cap_mb",
    "query_work_cap_mq",
    "threads",
    "ablation",
)


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{number}: row must be an object")
        rows.append(value)
    return rows


def _query_index(design: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
    result: dict[tuple[str, str], dict[str, Any]] = {}
    pattern = design["query_generation"]["manifest_pattern"]
    for dataset in ("NY", "FLA", "CAL"):
        path = repo_path(pattern.format(dataset=dataset))
        for row in _read_jsonl(path):
            key = (dataset, str(row.get("query_id")))
            if key in result:
                raise ValueError(f"duplicate query manifest identity: {key}")
            result[key] = row
    return result


def _source_plan_index(run_root: Path) -> dict[str, dict[str, Any]]:
    path = run_root / "plan" / "matrices" / "t03.jsonl"
    if not path.is_file():
        raise FileNotFoundError(f"source T03 plan is missing: {path}")
    rows = _read_jsonl(path)
    return {str(row["job_id"]): row for row in rows}


def _source_records(
    source_roots: list[Path],
) -> dict[tuple[Any, ...], list[dict[str, Any]]]:
    grouped: dict[tuple[Any, ...], list[dict[str, Any]]] = collections.defaultdict(list)
    for run_root in sorted(source_roots, key=lambda value: value.as_posix()):
        plan = _source_plan_index(run_root)
        raw_root = run_root / "raw" / "T03"
        if not raw_root.is_dir():
            continue
        for path in sorted(raw_root.glob("*.jsonl")):
            try:
                rows = _read_jsonl(path)
            except (OSError, ValueError, json.JSONDecodeError):
                grouped[("CORRUPT", path.as_posix())].append({
                    "source_path": path,
                    "source_run_root": run_root,
                    "corrupt": True,
                })
                continue
            for wrapper in rows:
                java = wrapper.get("java_record") or {}
                configuration = java.get("configuration") or {}
                if configuration.get("algorithm") != "pace-b":
                    continue
                source_job = plan.get(str(wrapper.get("job_id")))
                if not source_job:
                    continue
                key = _semantic_key(source_job)
                grouped[key].append({
                    "source_path": path,
                    "source_run_root": run_root,
                    "source_job": source_job,
                    "wrapper": wrapper,
                    "java": java,
                    "corrupt": False,
                })
    return grouped


def _semantic_key(job: dict[str, Any]) -> tuple[Any, ...]:
    return (
        job.get("study_id"),
        job.get("dataset_id"),
        job.get("query_id"),
        job.get("algorithm_id"),
        job.get("variant_id"),
        json.dumps(job.get("algorithm_parameters") or {}, sort_keys=True),
        json.dumps(job.get("axis") or {}, sort_keys=True),
        int(job.get("trial_id", -1)),
    )


def _expected_pace_configuration(
    job: dict[str, Any], design: dict[str, Any]
) -> dict[str, Any]:
    defaults = design["pace_b_defaults"]
    value: dict[str, Any] = {
        "theta": defaults["theta"],
        "pivot_limit_l": defaults["pivot_limit_l"],
        "connector_limit_kc": defaults["connector_limit_kc"],
        "frontier_limit_kf": defaults["frontier_limit_kf"],
        "connector_expansion_cap_mc": defaults["connector_expansion_cap_mc"],
        "breakpoint_cap_mb": defaults["breakpoint_cap_mb"],
        "query_work_cap_mq": defaults["query_work_cap_mq"],
        "threads": defaults["threads"],
        "ablation": "none",
    }
    parameters = dict(job.get("algorithm_parameters") or {})
    parameters.pop("resolved_pace_b", None)
    value.update(parameters)
    value.update(job.get("axis") or {})
    return {field: value.get(field) for field in PACE_B_FIELDS}


def _match_query(
    java: dict[str, Any],
    expected: dict[str, Any],
) -> bool:
    actual = java.get("query") or {}
    fields = (
        "query_id",
        "source",
        "destination",
        "interval_start",
        "interval_end",
        "budget",
        "budget_slack",
        "distance_bin",
    )
    return (
        all(actual.get(field) == expected.get(field) for field in fields)
        and _unsigned_u64(actual.get("query_seed"))
        == _unsigned_u64(expected.get("query_seed"))
    )


def _unsigned_u64(value: Any) -> int | None:
    """Normalize Java signed-long and manifest unsigned-decimal seed encodings."""
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    if parsed < -(1 << 63) or parsed >= (1 << 64):
        return None
    return parsed & ((1 << 64) - 1)


def _match_dataset(
    java: dict[str, Any],
    expected: dict[str, Any],
) -> bool:
    actual = java.get("dataset") or {}
    metadata = expected.get("metadata") or {}
    checks = {
        "dataset_id": expected.get("dataset_id"),
        "dataset_structure_checksum": metadata.get("dataset_checksum"),
        "dataset_payload_checksum": metadata.get("dataset_payload_checksum"),
        "temporal_attribute_checksum": metadata.get("temporal_attribute_checksum"),
    }
    return all(actual.get(field) == value for field, value in checks.items())


def _assess(
    candidate: dict[str, Any],
    job: dict[str, Any],
    design: dict[str, Any],
    query: dict[str, Any],
) -> tuple[bool, list[str], dict[str, Any]]:
    reasons: list[str] = []
    if candidate.get("corrupt"):
        return False, ["corrupt_record"], {}
    wrapper = candidate["wrapper"]
    java = candidate["java"]
    status = java.get("status") or {}
    output = java.get("output") or {}
    timing = java.get("timing_ns") or {}
    configuration = java.get("configuration") or {}
    counters = java.get("counters") or {}
    run = java.get("run") or {}

    if candidate["source_job"].get("variant_id") != job.get("variant_id"):
        reasons.append("variant_mismatch")
    expected_configuration = _expected_pace_configuration(job, design)
    if any(
        configuration.get(field) != expected_configuration.get(field)
        for field in PACE_B_FIELDS
    ):
        reasons.append("semantic_configuration_mismatch")
    if configuration.get("temporal_replay_contract") != (
        "DECLARED_DEPARTURE_GRID_LINEARIZED-v1"
    ):
        reasons.append("temporal_replay_contract_mismatch")
    if configuration.get("deterministic") is not True:
        reasons.append("nondeterministic_source")
    if configuration.get("timeout_seconds") != 10:
        reasons.append("source_timeout_is_not_declared_ten_seconds")
    if run.get("git_commit") != design["reconciliation"]["source_git_commit"]:
        reasons.append("source_git_commit_mismatch")
    if not _match_query(java, query):
        reasons.append("query_mismatch")
    if not _match_dataset(java, query):
        reasons.append("dataset_fingerprint_mismatch")
    if wrapper.get("completion_status") != "SUCCESS" or status.get(
        "status_code"
    ) not in {"COMPLETED", "CERTIFIED_COMPLETE"}:
        reasons.append("not_successful_terminal_result")
    if output.get("feasible") is not True:
        reasons.append("no_feasible_output")
    runtime = timing.get("query_total")
    if not isinstance(runtime, int) or runtime < 0:
        reasons.append("missing_or_ambiguous_query_runtime")
    elif runtime > int(design["reconciliation"]["new_timeout_ms"]) * 1_000_000:
        reasons.append("query_runtime_exceeds_five_seconds")
    if counters.get("output_validation_contract") != (
        "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1"
    ):
        reasons.append("missing_exact_output_validation_contract")
    if counters.get("output_feasible") is not True:
        reasons.append("missing_validated_feasibility_evidence")
    if counters.get("output_loopless") is not True:
        reasons.append("missing_validated_looplessness_evidence")
    if not output.get("profile_checksum"):
        reasons.append("missing_output_profile_checksum")
    evidence = {
        "measured_query_runtime_ms": runtime / 1_000_000
            if isinstance(runtime, int) else None,
        "output_checksum": output.get("profile_checksum"),
        "configuration_fingerprint": sha256_json(expected_configuration),
    }
    return not reasons, sorted(set(reasons)), evidence


def reconcile(
    design: dict[str, Any],
    matrix: Path,
    source_roots: list[Path],
    *,
    expected_counts: dict[str, int] | None = None,
) -> tuple[
    list[dict[str, Any]],
    list[dict[str, Any]],
    list[dict[str, Any]],
    dict[str, Any],
    str,
]:
    source_jar = repo_path(design["reconciliation"]["source_jar"])
    source_jar_sha256 = sha256_file(source_jar)
    if source_jar_sha256 != design["reconciliation"]["source_jar_sha256"]:
        raise ValueError(
            "source PACE-B JAR checksum differs from the frozen semantic dependency"
        )
    jobs = _read_jsonl(matrix)
    if len({job["job_id"] for job in jobs}) != len(jobs):
        raise ValueError("new T03 matrix contains duplicate job IDs")
    algorithms = collections.Counter(job.get("algorithm_id") for job in jobs)
    expected = expected_counts or {
        "pace-b": 45000,
        "iscope": 45000,
        "allfp": 45000,
    }
    observed = {algorithm: algorithms[algorithm] for algorithm in expected}
    unexpected = set(algorithms) - set(expected)
    if observed != expected or unexpected:
        raise ValueError(f"new logical T03 matrix mismatch: {dict(algorithms)}")
    query_rows = _query_index(design)
    allfp_groups: collections.Counter[tuple[Any, ...]] = collections.Counter()
    for job in jobs:
        if job.get("algorithm_id") != "allfp":
            continue
        query = query_rows[(str(job["dataset_id"]), str(job["query_id"]))]
        allfp_groups[(
            job.get("dataset_id"),
            job.get("variant_id"),
            json.dumps(job.get("algorithm_parameters") or {}, sort_keys=True),
            query.get("source"),
            query.get("destination"),
            query.get("interval_start"),
            query.get("interval_end"),
            int(job.get("trial_id", -1)),
        )] += 1
    allfp_group_sizes = collections.Counter(allfp_groups.values())
    allfp_searches = len(allfp_groups)
    allfp_projections = algorithms["allfp"] - allfp_searches
    allfp_reuse_valid = algorithms["allfp"] == 0 or (
        allfp_searches > 0
        and set(allfp_group_sizes) == {5}
    )
    sources = _source_records(source_roots)
    reusable: list[dict[str, Any]] = []
    effective_index: list[dict[str, Any]] = []
    execution: list[dict[str, Any]] = []
    reasons = collections.Counter()
    source_candidates = 0
    conflicts = 0

    for job in jobs:
        if job.get("algorithm_id") != "pace-b":
            execution.append(job)
            continue
        key = _semantic_key(job)
        candidates = sources.get(key, [])
        source_candidates += len(candidates)
        query = query_rows[(str(job["dataset_id"]), str(job["query_id"]))]
        eligible: list[tuple[dict[str, Any], dict[str, Any]]] = []
        job_reasons: set[str] = set()
        candidate_checksums: set[str] = set()
        for candidate in candidates:
            accepted, candidate_reasons, evidence = _assess(
                candidate, job, design, query
            )
            job_reasons.update(candidate_reasons)
            checksum = evidence.get("output_checksum")
            if isinstance(checksum, str) and checksum:
                candidate_checksums.add(checksum)
            if accepted:
                eligible.append((candidate, evidence))
        if len(candidate_checksums) > 1:
            conflicts += 1
            job_reasons.add("conflicting_duplicate_outputs")
            eligible = []
        checksums = {
            evidence.get("output_checksum")
            for _, evidence in eligible
        }
        if len(checksums) > 1:
            if len(candidate_checksums) <= 1:
                conflicts += 1
            job_reasons.add("conflicting_duplicate_outputs")
            eligible = []
        if eligible:
            selected, evidence = sorted(
                eligible,
                key=lambda item: (
                    item[0]["source_run_root"].as_posix(),
                    item[0]["source_path"].as_posix(),
                    str((item[0]["java"].get("run") or {}).get("run_id")),
                ),
            )[0]
            all_originals = [
                {
                    "source_run_id": item["source_run_root"].name,
                    "source_record_path": item["source_path"].as_posix(),
                    "source_record_id": (item["java"].get("run") or {}).get(
                        "run_id"
                    ),
                }
                for item, _ in eligible
            ]
            java = selected["java"]
            query_metadata = query.get("metadata") or {}
            reference = {
                "schema_version": 1,
                "result_origin": "REUSED_HISTORICAL",
                "target_job_id": job["job_id"],
                "target_input_hash": job["input_hash"],
                "source_run_id": selected["source_run_root"].name,
                "source_record_path": selected["source_path"].as_posix(),
                "source_record_id": (java.get("run") or {}).get("run_id"),
                "source_timeout_ms": design["reconciliation"]["source_timeout_ms"],
                "measured_query_runtime_ms": evidence["measured_query_runtime_ms"],
                "reuse_reason": "validated_success_with_query_runtime_at_most_5000ms",
                "pace_b_semantic_version": design["reconciliation"]
                    ["pace_b_semantic_version"],
                "configuration_fingerprint": evidence[
                    "configuration_fingerprint"
                ],
                "dataset_fingerprints": {
                    "dataset_checksum": query_metadata.get("dataset_checksum"),
                    "dataset_payload_checksum": query_metadata.get(
                        "dataset_payload_checksum"
                    ),
                    "temporal_attribute_checksum": query_metadata.get(
                        "temporal_attribute_checksum"
                    ),
                },
                "output_checksum": evidence["output_checksum"],
                "all_originals": all_originals,
            }
            reusable.append(reference)
            effective_index.append(reference)
        else:
            execution.append(job)
            if not candidates:
                job_reasons.add("unfinished_no_source_record")
            for reason in job_reasons:
                reasons[reason] += 1

    reusable.sort(key=lambda row: row["target_job_id"])
    effective_index.sort(key=lambda row: row["target_job_id"])
    execution.sort(key=lambda row: row["job_id"])
    remaining_pace_b = sum(
        job.get("algorithm_id") == "pace-b" for job in execution
    )
    report = {
        "schema_version": 1,
        "protocol": "pace-b-10s-to-5s-reconciliation-v1",
        "pace_b_semantic_version": design["reconciliation"]
            ["pace_b_semantic_version"],
        "source_run_ids": [path.name for path in source_roots],
        "source_git_commit": design["reconciliation"]["source_git_commit"],
        "source_jar": source_jar.as_posix(),
        "source_jar_sha256": source_jar_sha256,
        "source_raw_ledgers_modified": False,
        "logical_counts": expected,
        "logical_total": len(jobs),
        "source_pace_b_candidates": source_candidates,
        "reusable_pace_b": len(reusable),
        "remaining_pace_b": remaining_pace_b,
        "planned_iscope": algorithms["iscope"],
        "planned_allfp": algorithms["allfp"],
        "allfp_budget_independent_searches_planned": allfp_searches,
        "allfp_budget_projection_rows_planned": allfp_projections,
        "allfp_budget_variant_group_sizes": {
            str(size): count
            for size, count in sorted(allfp_group_sizes.items())
        },
        "allfp_budget_reuse_contract": (
            "one measured continuous fastest-profile search per "
            "dataset/source/destination/window/trial; rho changes only "
            "post-hoc budget feasibility"
        ),
        "allfp_budget_reuse_valid": allfp_reuse_valid,
        "execution_manifest_jobs": len(execution),
        "conflicting_duplicate_jobs": conflicts,
        "nonreuse_reasons": dict(sorted(reasons.items())),
        "only_timeout_difference_permitted": True,
        "new_timeout_ms": design["reconciliation"]["new_timeout_ms"],
        "profile_validation_policy": (
            "fail-closed: reusable rows require the recorded "
            "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1 contract, explicit "
            "feasible/loopless evidence, and a profile checksum"
        ),
        "passed": (
            len(reusable) + remaining_pace_b == algorithms["pace-b"]
            and len(execution) == remaining_pace_b
                + algorithms["iscope"] + algorithms["allfp"]
            and allfp_reuse_valid
        ),
    }
    summary = "\n".join([
        "# PACE-B five-second reconciliation",
        "",
        f"- Logical PACE-B jobs: {algorithms['pace-b']:,}",
        f"- Reusable PACE-B jobs: {len(reusable):,}",
        f"- Remaining PACE-B jobs: {remaining_pace_b:,}",
        f"- Planned iSCOPE jobs: {algorithms['iscope']:,}",
        f"- Planned allFP jobs: {algorithms['allfp']:,}",
        f"- Actual allFP fastest-profile searches: {allfp_searches:,}",
        f"- allFP post-hoc budget projections: {allfp_projections:,}",
        f"- Actual execution-manifest jobs: {len(execution):,}",
        f"- Conflicting duplicate jobs: {conflicts:,}",
        "- Historical raw ledgers modified: no",
        "",
        "## Non-reuse reasons",
        "",
        *(
            [f"- {reason}: {count:,}" for reason, count in sorted(reasons.items())]
            or ["- None"]
        ),
        "",
    ])
    return reusable, execution, effective_index, report, summary


def _write(
    output: Path,
    reusable: list[dict[str, Any]],
    execution: list[dict[str, Any]],
    effective_index: list[dict[str, Any]],
    report: dict[str, Any],
    summary: str,
) -> None:
    output.mkdir(parents=True, exist_ok=True)
    write_jsonl(output / REUSABLE_FILE, reusable)
    write_jsonl(output / EXECUTION_FILE, execution)
    write_jsonl(output / INDEX_FILE, effective_index)
    report = dict(report)
    report["artifact_checksums"] = {
        REUSABLE_FILE: sha256_file(output / REUSABLE_FILE),
        EXECUTION_FILE: sha256_file(output / EXECUTION_FILE),
        INDEX_FILE: sha256_file(output / INDEX_FILE),
    }
    atomic_write_json(output / REPORT_FILE, report)
    atomic_write_text(output / SUMMARY_FILE, summary)


def _validate_existing(
    output: Path,
    expected: tuple[
        list[dict[str, Any]],
        list[dict[str, Any]],
        list[dict[str, Any]],
        dict[str, Any],
        str,
    ],
) -> None:
    reusable, execution, index, report, summary = expected
    expected_rows = {
        REUSABLE_FILE: reusable,
        EXECUTION_FILE: execution,
        INDEX_FILE: index,
    }
    for name, rows in expected_rows.items():
        path = output / name
        if not path.is_file() or _read_jsonl(path) != rows:
            raise ValueError(f"reconciliation artifact mismatch: {path}")
    existing_report = json.loads((output / REPORT_FILE).read_text(encoding="utf-8"))
    for key, value in report.items():
        if existing_report.get(key) != value:
            raise ValueError(f"reconciliation report mismatch: {key}")
    if (output / SUMMARY_FILE).read_text(encoding="utf-8") != summary:
        raise ValueError("human reconciliation summary mismatch")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--source-run", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    try:
        design = load_design(repo_path(args.config))
        matrix = repo_path(args.matrix)
        sources = [repo_path(path).resolve() for path in args.source_run]
        expected = reconcile(design, matrix, sources)
        output = repo_path(args.output)
        if args.validate_only:
            _validate_existing(output, expected)
        else:
            _write(output, *expected)
        print(json.dumps(expected[3], indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as failure:
        print(f"pace-b reconciliation: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
