#!/usr/bin/env python3
"""Execute a deterministic matrix locally or emit/submit a Slurm array."""
from __future__ import annotations

import argparse
import collections
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import time
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_text, write_jsonl
from experiments.scripts.common.config import (
    algorithm_timeout_seconds,
    load_design,
    repo_path,
)
from experiments.scripts.common.hashing import sha256_json
from experiments.scripts.common.status import CompletionStatus
from experiments.scripts.common.toolchain import environment, executable
from experiments.scripts.executors.local import run_jobs
from experiments.scripts.executors.slurm import submit, write_array_script


JAVA_STATUS = {
    "COMPLETED": CompletionStatus.SUCCESS.value,
    "CERTIFIED_COMPLETE": CompletionStatus.SUCCESS.value,
    "NO_FEASIBLE_PATH": CompletionStatus.SUCCESS.value,
    "TIME_CAPPED_NOT_CERTIFIED": CompletionStatus.TIME_CAPPED_NOT_CERTIFIED.value,
    "PATH_CAPPED_NOT_CERTIFIED": CompletionStatus.PATH_CAPPED_NOT_CERTIFIED.value,
    "TIMEOUT": CompletionStatus.TIMEOUT.value,
    "OUT_OF_MEMORY": CompletionStatus.OUT_OF_MEMORY.value,
    "LIMIT_EXCEEDED": CompletionStatus.RESOURCE_LIMIT_EXCEEDED.value,
    "FUNCTION_HORIZON_EXCEEDED": CompletionStatus.FUNCTION_HORIZON_EXCEEDED.value,
    "INVALID_QUERY": CompletionStatus.INVALID_INPUT.value,
    "INVALID_CONFIGURATION": CompletionStatus.INVALID_INPUT.value,
    "ERROR": CompletionStatus.INTERNAL_ERROR.value,
}


TIMEOUT_ONLY_POLICY = "TIMEOUT_ONLY_EXCLUDE_FROM_COMPARISON"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _job_timeout_seconds(job: dict[str, Any], design: dict[str, Any]) -> int:
    return algorithm_timeout_seconds(design, str(job["algorithm_id"]))


def _timeout_only_policy(job: dict[str, Any], design: dict[str, Any]) -> bool:
    policies = design.get("resources", {}).get("timeout_result_policy")
    return (
        isinstance(policies, dict)
        and policies.get(job.get("algorithm_id")) == TIMEOUT_ONLY_POLICY
    )


def _java_deadline_timeout(java_record: dict[str, Any]) -> bool:
    status = java_record.get("status") or {}
    status_code = str(status.get("status_code", ""))
    if status_code == "TIMEOUT":
        return True
    if status_code != "TIME_CAPPED_NOT_CERTIFIED":
        return False
    return "QUERY_DEADLINE" in set(status.get("cap_triggered") or [])


def _timeout_only_error() -> dict[str, str]:
    return {
        "type": "TIMED_OUT",
        "message": "TIMED OUT",
        "stack_trace_or_context": None,
        "failing_phase": "query",
    }


def _sanitize_timeout_only_record(
    job: dict[str, Any],
    design: dict[str, Any],
    java_record: dict[str, Any] | None,
    status: str,
    error: dict[str, Any] | None,
) -> tuple[str, dict[str, Any] | None, dict[str, Any] | None]:
    if not _timeout_only_policy(job, design):
        return status, java_record, error
    if status == CompletionStatus.TIMEOUT.value:
        return CompletionStatus.TIMEOUT.value, None, _timeout_only_error()
    if java_record is not None and _java_deadline_timeout(java_record):
        return CompletionStatus.TIMEOUT.value, None, _timeout_only_error()
    return status, java_record, error


def _record_exit_code(
    java_record: dict[str, Any] | None,
    status: str,
    default: int,
) -> int:
    if isinstance(java_record, dict):
        value = (java_record.get("status") or {}).get("exit_code")
        if isinstance(value, int):
            return value
    if status == CompletionStatus.TIMEOUT.value:
        return 124
    return default


def _query_row(job: dict[str, Any]) -> dict[str, Any]:
    manifest = repo_path(job["manifest"])
    if not manifest.is_file():
        raise FileNotFoundError(f"query manifest is missing: {manifest}")
    rows = read_jsonl(manifest)
    matches = [row for row in rows if row.get("query_id") == job["query_id"]]
    if len(matches) != 1:
        raise ValueError(
            f"query {job['query_id']} occurs {len(matches)} times in {manifest}; expected exactly one"
        )
    return matches[0]


def _enforce_exact_algorithm_guard(
    job: dict[str, Any], query: dict[str, Any], design: dict[str, Any]
) -> None:
    """Reject an exact run outside an explicitly bounded scalability scope."""
    guard = design.get("exact_algorithm_guard")
    if job.get("algorithm_id") != "pace-x" or not guard:
        return
    allowed_studies = set(guard.get("allowed_studies", []))
    if allowed_studies and job.get("study_id") not in allowed_studies:
        raise ValueError(
            f"PACE-X is restricted to studies {sorted(allowed_studies)}; "
            f"got {job.get('study_id')}"
        )
    datasets = set(guard.get("datasets", []))
    if datasets and job.get("dataset_id") not in datasets:
        raise ValueError(
            f"PACE-X is restricted to datasets {sorted(datasets)}; "
            f"got {job.get('dataset_id')}"
        )
    splits = set(guard.get("splits", []))
    split = query.get("metadata", {}).get("split")
    if splits and split not in splits:
        raise ValueError(
            f"PACE-X is restricted to splits {sorted(splits)}; got {split}"
        )
    maximum_rho = guard.get("max_budget_overhead_by_study", {}).get(
        job.get("study_id"), guard.get("max_budget_overhead")
    )
    if maximum_rho is not None:
        rho = query.get("budget_slack")
        if not isinstance(rho, (int, float)) or float(rho) > float(maximum_rho):
            raise ValueError(
                "PACE-X small-budget guard violated at execution: "
                f"rho={rho!r} > {maximum_rho}"
            )


def _resolved_pace_b(
    run_root: Path,
    design: dict[str, Any],
) -> dict[str, int]:
    path = run_root / "provenance" / "resolved_pace_b.yaml"
    if not path.is_file():
        raise FileNotFoundError("PACE-B parameters are unresolved; run the pilot stage first")
    value = json.loads(path.read_text(encoding="utf-8"))
    result = {
        "pivot_limit_l": int(value["pivot_limit_l"]),
        "connector_limit_kc": int(value["connector_limit_kc"]),
        "frontier_limit_kf": int(value["frontier_limit_kf"]),
        "connector_expansion_cap_mc":
            int(value["connector_expansion_cap_mc"]),
        "breakpoint_cap_mb": int(value["breakpoint_cap_mb"]),
        "query_work_cap_mq": int(value["query_work_cap_mq"]),
    }
    defaults = design["pace_b_defaults"]
    for name in (
        "connector_expansion_cap_mc",
        "breakpoint_cap_mb",
        "query_work_cap_mq",
    ):
        if result[name] != int(defaults[name]):
            raise ValueError(
                f"frozen {name} differs from configured safety policy"
            )
    return result


def _command(
    job: dict[str, Any], design: dict[str, Any], run_root: Path, query_file: Path, java_output: Path
) -> list[str]:
    resources = design["resources"]
    # Query timeouts must start after the isolated worker has loaded and
    # validated the dataset.  PaceBench otherwise falls back to the query
    # timeout for preprocessing, which makes short scalability timeouts expire
    # before the algorithm starts on real road networks.
    preprocessing_timeout = int(
        resources.get("preprocessing_timeout_seconds", 1800)
    )
    memory = resources.get("memory_limit_mb")
    if memory is None:
        raise ValueError("memory_limit_mb must be resolved before execution")
    parameters: dict[str, Any] = {}
    if job["algorithm_id"] == "pace-b":
        defaults = design["pace_b_defaults"]
        parameters.update({
            "theta": defaults["theta"],
            "pivot_limit_l": defaults.get("pivot_limit_l", 4),
            "connector_limit_kc": defaults.get("connector_limit_kc", 4),
            "frontier_limit_kf": defaults.get("frontier_limit_kf", 2),
            "connector_expansion_cap_mc":
                defaults["connector_expansion_cap_mc"],
            "breakpoint_cap_mb": defaults["breakpoint_cap_mb"],
            "query_work_cap_mq": defaults["query_work_cap_mq"],
            "threads": defaults["threads"],
        })
    algorithm_parameters = dict(
        job.get("algorithm_parameters") or {}
    )
    explicitly_resolved = bool(
        algorithm_parameters.pop("resolved_pace_b", False)
    )
    parameters.update(algorithm_parameters)
    require_resolved = design.get(
        "resolved_pace_b_required", job["study_id"] != "E02"
    )
    if (
        job["algorithm_id"] == "pace-b"
        and (explicitly_resolved or require_resolved)
    ):
        parameters.update(_resolved_pace_b(run_root, design))
    parameters.update(job.get("axis") or {})
    dataset_path = "demo"
    if job["dataset_id"] != "demo":
        # Use the checked-in dataset definition for the base payload.  Dataset
        # IDs are logical names and are not required to be filesystem-safe
        # spellings (NY-EXACT is materialized as data/input/NY-Exact).
        definition = design.get("dataset_definitions", {}).get(job["dataset_id"])
        if not isinstance(definition, dict) or not definition.get("path"):
            raise ValueError(
                f"dataset definition is missing for {job['dataset_id']}"
            )
        dataset_path_value = repo_path(definition["path"])
        # PaceBench derives the loaded logical ID from the directory basename.
        # Keep the on-disk NY-Exact payload compatible with the canonical
        # manifest/query ID NY-EXACT through the checked-in uppercase alias.
        if job["dataset_id"] == "NY-EXACT":
            canonical_alias = repo_path("data/input/NY-EXACT")
            if canonical_alias.exists():
                dataset_path_value = canonical_alias
        dataset_path = str(dataset_path_value)
        if "score_density" in parameters:
            pattern = design["graph_variants"]["score_density_pattern"]
            dataset_path = str(repo_path(pattern.format(dataset=job["dataset_id"], percent=int(round(100 * parameters["score_density"])))))
        elif "graph_seed" in parameters and int(parameters["graph_seed"]) != int(design["seeds"]["graph_main"]):
            pattern = design["graph_variants"]["graph_seed_pattern"]
            dataset_path = str(repo_path(pattern.format(dataset=job["dataset_id"], seed=int(parameters["graph_seed"]))))
    command = [
        executable("java"),
        f"-Xmx{max(1, int(memory) // 1024)}g",
        "-jar", str(repo_path(design["paths"]["jar"])),
        "--algorithm", job["algorithm_id"],
        "--dataset", dataset_path,
        "--query-file", str(query_file),
        "--output-jsonl", str(java_output),
        "--experiment-name", f"{job['study_id']}:{job['job_id']}",
        "--repetitions", "1", "--warmup-runs", "0",
        "--threads", str(parameters.pop("threads", 1)),
        "--timeout-seconds", str(_job_timeout_seconds(job, design)),
        "--preprocessing-timeout-seconds", str(preprocessing_timeout),
        "--memory-limit-mb", str(memory),
        "--seed", str(design.get("seeds", {}).get("schedule", 20260725)),
        "--deterministic", "--collect-phase-timings", "--collect-memory", "--collect-internal-counters",
    ]
    ablation = parameters.pop("ablation", None)
    if ablation:
        command.extend(["--ablation", str(ablation)])
    flags = {
        "theta": "--theta", "anchor_limit": "--anchor-limit", "k": "--k",
        "pivot_limit_l": "--pivot-limit-l",
        "connector_limit_kc": "--connector-limit-kc",
        "frontier_limit_kf": "--frontier-limit-kf",
        "connector_expansion_cap_mc": "--connector-expansion-cap-mc",
        "breakpoint_cap_mb": "--breakpoint-cap-mb",
        "query_work_cap_mq": "--query-work-cap-mq",
        "rpq_step_minutes": "--rpq-step-minutes", "baseline_k": "--baseline-k",
        "max_enumerated_paths": "--max-enumerated-paths", "max_labels": "--max-labels",
        "max_expansions": "--max-expansions", "max_frontier_fragments": "--max-frontier-fragments",
    }
    for name, flag in flags.items():
        if name in parameters:
            command.extend([flag, str(parameters.pop(name))])
    ignored_workload_axes = {
        "window_minutes",
        "budget_overhead",
        "score_density",
        "graph_seed",
        "compression",
        "diagnostic",
    }
    unknown = set(parameters) - ignored_workload_axes
    if unknown:
        raise ValueError(f"unsupported Java parameters for {job['job_id']}: {sorted(unknown)}")
    reference = job.get("reference_algorithm")
    if reference and reference != job["algorithm_id"]:
        command.extend(["--reference-algorithm", reference])
    return command


def execute_one(job: dict[str, Any], design: dict[str, Any], run_id: str, backend: str) -> dict[str, Any]:
    run_root = repo_path(design["paths"]["results_root"]) / run_id
    raw_path = run_root / "raw" / job["study_id"] / f"{job['job_id']}.jsonl"
    if raw_path.is_file():
        existing = read_jsonl(raw_path)
        if len(existing) == 1 and existing[0].get("input_hash") == job["input_hash"]:
            return {"job_id": job["job_id"], "skipped": True, "completion_status": existing[0]["completion_status"]}
        raise ValueError(f"existing raw record has a different identity: {raw_path}")
    work = run_root / "work" / job["job_id"]
    work.mkdir(parents=True, exist_ok=True)
    query_file = work / "query.jsonl"
    java_output = work / "java-result.jsonl"
    stdout_path = run_root / "logs" / f"{job['job_id']}.stdout.log"
    stderr_path = run_root / "logs" / f"{job['job_id']}.stderr.log"
    stdout_path.parent.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone.utc).isoformat()
    java_record = None
    error = None
    return_code = 2
    status = CompletionStatus.INTERNAL_ERROR.value
    try:
        row = _query_row(job)
        _enforce_exact_algorithm_guard(job, row, design)
        write_jsonl(query_file, [row])
        command = _command(job, design, run_root, query_file, java_output)
        resources = design["resources"]
        timeout = (
            int(resources.get("preprocessing_timeout_seconds", 1800))
            + _job_timeout_seconds(job, design)
            + 90
        )
        completed = subprocess.run(
            command, cwd=repo_path("."), env=environment(), check=False, capture_output=True, text=True, timeout=timeout
        )
        return_code = completed.returncode
        atomic_write_text(stdout_path, completed.stdout)
        atomic_write_text(stderr_path, completed.stderr)
        if java_output.is_file():
            rows = read_jsonl(java_output)
            if len(rows) != 1:
                raise ValueError(f"Java worker emitted {len(rows)} records")
            java_record = rows[0]
            java_status = java_record.get("status", {}).get("status_code", "ERROR")
            status = JAVA_STATUS.get(java_status, CompletionStatus.INTERNAL_ERROR.value)
        else:
            error = {"type": "MissingJavaRecord", "message": "worker did not emit a terminal record"}
    except subprocess.TimeoutExpired as failure:
        status = CompletionStatus.TIMEOUT.value
        return_code = 124
        atomic_write_text(stdout_path, failure.stdout or "")
        atomic_write_text(stderr_path, failure.stderr or "")
        error = {"type": "OuterTimeout", "message": str(failure)}
    except Exception as failure:
        status = CompletionStatus.INVALID_INPUT.value if isinstance(failure, (FileNotFoundError, ValueError)) else CompletionStatus.INTERNAL_ERROR.value
        error = {"type": type(failure).__name__, "message": str(failure)}
        atomic_write_text(stdout_path, "")
        atomic_write_text(stderr_path, str(failure) + "\n")
    status, java_record, error = _sanitize_timeout_only_record(
        job, design, java_record, status, error
    )
    record = {
        "schema_version": 1,
        "metric_definition_version": design["protocol"]["metric_definition_version"],
        "run_id": run_id,
        "study_id": job["study_id"],
        "job_id": job["job_id"],
        "trial_id": job["trial_id"],
        "input_hash": job["input_hash"],
        "backend": backend,
        "timestamp_start_utc": started,
        "timestamp_end_utc": datetime.now(timezone.utc).isoformat(),
        "completion_status": status,
        "exit_code": _record_exit_code(java_record, status, return_code),
        "stdout_path": stdout_path.relative_to(repo_path(".")).as_posix(),
        "stderr_path": stderr_path.relative_to(repo_path(".")).as_posix(),
        "java_record": java_record,
        "error": error,
    }
    write_jsonl(raw_path, [record])
    return {"job_id": job["job_id"], "skipped": False, "completion_status": status}


def _replace_option(command: list[str], option: str, value: str) -> None:
    try:
        index = command.index(option)
    except ValueError as failure:
        raise ValueError(f"generated command is missing {option}") from failure
    if index + 1 >= len(command):
        raise ValueError(f"generated command has no value for {option}")
    command[index + 1] = value


def _batch_key(job: dict[str, Any]) -> str:
    # Window and budget select a manifest row but do not change the Java
    # algorithm configuration.  Keeping them out of the key lets one loaded
    # dataset serve the complete T03 query stream.  Dataset variants remain in
    # the key because they change the loaded payload.
    query_only_axes = {"window_minutes", "budget_overhead"}
    execution_axis = {
        key: value
        for key, value in (job.get("axis") or {}).items()
        if key not in query_only_axes
    }
    return sha256_json({
        "study_id": job["study_id"],
        "dataset_id": job["dataset_id"],
        "algorithm_id": job["algorithm_id"],
        "variant_id": job["variant_id"],
        "algorithm_parameters": job.get("algorithm_parameters") or {},
        "execution_axis": execution_axis,
        "manifest": job["manifest"],
        "reference_algorithm": job.get("reference_algorithm"),
    })


def _manifest_rows_for_group(
    jobs: list[dict[str, Any]], design: dict[str, Any]
) -> tuple[
    list[dict[str, Any]],
    dict[tuple[str, int], dict[str, Any]],
    tuple[int, ...],
]:
    manifest = repo_path(jobs[0]["manifest"])
    rows = read_jsonl(manifest)
    by_query_id = {row.get("query_id"): row for row in rows}
    if len(by_query_id) != len(rows):
        raise ValueError(f"manifest contains duplicate query IDs: {manifest}")
    job_lookup: dict[tuple[str, int], dict[str, Any]] = {}
    query_ids: list[str] = []
    seen_query_ids: set[str] = set()
    trials_by_query: dict[str, set[int]] = collections.defaultdict(set)
    for job in jobs:
        query_id = str(job["query_id"])
        if query_id not in by_query_id:
            raise ValueError(f"query {query_id} is missing from {manifest}")
        query = by_query_id[query_id]
        _enforce_exact_algorithm_guard(job, query, design)
        trial = int(job["trial_id"])
        key = (query_id, trial)
        if key in job_lookup:
            raise ValueError(
                f"duplicate batched query/trial cell: {query_id} trial {trial}"
            )
        job_lookup[key] = job
        trials_by_query[query_id].add(trial)
        if query_id not in seen_query_ids:
            seen_query_ids.add(query_id)
            query_ids.append(query_id)
    expected_trials: set[int] | None = None
    for query_id, trials in trials_by_query.items():
        if expected_trials is None:
            expected_trials = set(trials)
        elif trials != expected_trials:
            raise ValueError(
                f"batched trials differ for query {query_id}: "
                f"{sorted(trials)} != {sorted(expected_trials)}"
            )
    if not expected_trials:
        raise ValueError("batched group has no trials")
    trial_ids = tuple(sorted(expected_trials))
    return [by_query_id[query_id] for query_id in query_ids], job_lookup, trial_ids


def _uniform_trial_groups(
    jobs: list[dict[str, Any]],
) -> list[tuple[str, list[dict[str, Any]]]]:
    """Partition one batch key by each query's remaining trial signature."""
    by_query: dict[str, list[dict[str, Any]]] = collections.OrderedDict()
    for job in jobs:
        by_query.setdefault(str(job["query_id"]), []).append(job)
    by_signature: dict[tuple[int, ...], list[dict[str, Any]]] = (
        collections.OrderedDict()
    )
    for query_jobs in by_query.values():
        signature = tuple(sorted(int(job["trial_id"]) for job in query_jobs))
        by_signature.setdefault(signature, []).extend(query_jobs)
    return [
        (
            sha256_json({
                "base_batch_key": _batch_key(group[0]),
                "remaining_trial_ids": signature,
            }),
            group,
        )
        for signature, group in by_signature.items()
    ]


def _java_timestamp(value: Any) -> str:
    if isinstance(value, str) and value:
        return value
    return datetime.now(timezone.utc).isoformat()


def _record_path(path: Path) -> str:
    try:
        return path.relative_to(repo_path(".")).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def _materialize_batch_records(
    java_output: Path,
    job_lookup: dict[tuple[str, int], dict[str, Any]],
    run_root: Path,
    run_id: str,
    backend: str,
    stdout_path: Path,
    stderr_path: Path,
    design: dict[str, Any],
    offset: int,
) -> tuple[list[dict[str, Any]], int]:
    if not java_output.is_file():
        return [], offset
    if java_output.stat().st_size < offset:
        raise ValueError(f"batched Java output was truncated: {java_output}")
    materialized: list[dict[str, Any]] = []
    seen: set[tuple[str, int]] = set()
    with java_output.open("rb") as stream:
        stream.seek(offset)
        while True:
            line_start = stream.tell()
            encoded = stream.readline()
            if not encoded:
                break
            if not encoded.endswith(b"\n"):
                offset = line_start
                break
            java_record = json.loads(encoded.decode("utf-8"))
            offset = stream.tell()
            query_id = str((java_record.get("query") or {}).get("query_id", ""))
            repetition = int((java_record.get("run") or {}).get("repetition", -1))
            key = (query_id, repetition)
            if key in seen:
                raise ValueError(
                    f"batched Java output duplicates {query_id} repetition {repetition}"
                )
            seen.add(key)
            job = job_lookup.get(key)
            if job is None:
                raise ValueError(
                    f"batched Java output has no planned job for "
                    f"{query_id} repetition {repetition}"
                )
            raw_path = (
                run_root / "raw" / job["study_id"]
                / f"{job['job_id']}.jsonl"
            )
            if raw_path.is_file():
                existing = read_jsonl(raw_path)
                if (
                    len(existing) != 1
                    or existing[0].get("input_hash") != job["input_hash"]
                ):
                    raise ValueError(
                        f"existing raw record has a different identity: {raw_path}"
                    )
                continue
            java_status = (java_record.get("status") or {}).get(
                "status_code", "ERROR"
            )
            status = JAVA_STATUS.get(
                str(java_status), CompletionStatus.INTERNAL_ERROR.value
            )
            status, stored_java_record, error = _sanitize_timeout_only_record(
                job, design, java_record, status, None
            )
            timestamp = _java_timestamp(
                (java_record.get("run") or {}).get("timestamp_utc")
            )
            record = {
                "schema_version": 1,
                "metric_definition_version":
                    design["protocol"]["metric_definition_version"],
                "run_id": run_id,
                "study_id": job["study_id"],
                "job_id": job["job_id"],
                "trial_id": job["trial_id"],
                "input_hash": job["input_hash"],
                "backend": backend,
                "timestamp_start_utc": timestamp,
                "timestamp_end_utc": timestamp,
                "completion_status": status,
                "exit_code": _record_exit_code(
                    stored_java_record, status,
                    int((java_record.get("status") or {}).get("exit_code", 1)),
                ),
                "stdout_path": _record_path(stdout_path),
                "stderr_path": _record_path(stderr_path),
                "java_record": stored_java_record,
                "error": error,
            }
            write_jsonl(raw_path, [record])
            materialized.append({
                "job_id": job["job_id"],
                "skipped": False,
                "completion_status": status,
            })
    return materialized, offset


def execute_batched(
    jobs: list[dict[str, Any]],
    design: dict[str, Any],
    run_id: str,
    backend: str,
) -> list[dict[str, Any]]:
    """Execute serial queries while reusing one loaded dataset per group."""
    if backend != "local":
        raise ValueError("shared preprocessing currently requires the local backend")
    run_root = repo_path(design["paths"]["results_root"]) / run_id
    base_groups: dict[str, list[dict[str, Any]]] = collections.OrderedDict()
    for job in jobs:
        base_groups.setdefault(_batch_key(job), []).append(job)
    groups: dict[str, list[dict[str, Any]]] = collections.OrderedDict()
    for base_jobs in base_groups.values():
        for group_hash, group_jobs in _uniform_trial_groups(base_jobs):
            groups[group_hash] = group_jobs
    all_results: list[dict[str, Any]] = []
    for group_hash, group_jobs in groups.items():
        group_id = group_hash[:24]
        work = run_root / "work" / f"batch-{group_id}"
        work.mkdir(parents=True, exist_ok=True)
        query_file = work / "queries.jsonl"
        java_output = work / "java-results.jsonl"
        stdout_path = run_root / "logs" / f"batch-{group_id}.stdout.log"
        stderr_path = run_root / "logs" / f"batch-{group_id}.stderr.log"
        stdout_path.parent.mkdir(parents=True, exist_ok=True)
        query_rows, job_lookup, trial_ids = _manifest_rows_for_group(
            group_jobs, design
        )
        write_jsonl(query_file, query_rows)
        output_offset = 0
        materialized, output_offset = _materialize_batch_records(
            java_output, job_lookup, run_root, run_id, backend,
            stdout_path, stderr_path, design, output_offset,
        )
        all_results.extend(materialized)
        while any(
            not (
                run_root / "raw" / job["study_id"]
                / f"{job['job_id']}.jsonl"
            ).is_file()
            for job in group_jobs
        ):
            representative = group_jobs[0]
            command = _command(
                representative, design, run_root,
                query_file, java_output,
            )
            _replace_option(command, "--repetitions", str(max(trial_ids) + 1))
            command.extend([
                "--repetition-indices",
                ",".join(str(value) for value in trial_ids),
            ])
            _replace_option(
                command,
                "--experiment-name",
                f"{representative['study_id']}:batch:{group_id}",
            )
            command.extend([
                "--shared-preprocessing", "--resume",
            ])
            before = sum(
                1
                for job in group_jobs
                if (
                    run_root / "raw" / job["study_id"]
                    / f"{job['job_id']}.jsonl"
                ).is_file()
            )
            with stdout_path.open("a", encoding="utf-8") as stdout_stream, \
                    stderr_path.open("a", encoding="utf-8") as stderr_stream:
                process = subprocess.Popen(
                    command,
                    cwd=repo_path("."),
                    env=environment(),
                    stdout=stdout_stream,
                    stderr=stderr_stream,
                    text=True,
                )
                while process.poll() is None:
                    materialized, output_offset = _materialize_batch_records(
                        java_output, job_lookup, run_root, run_id,
                        backend, stdout_path, stderr_path, design,
                        output_offset,
                    )
                    all_results.extend(materialized)
                    time.sleep(1)
                return_code = process.wait()
            materialized, output_offset = _materialize_batch_records(
                java_output, job_lookup, run_root, run_id, backend,
                stdout_path, stderr_path, design, output_offset,
            )
            all_results.extend(materialized)
            after = sum(
                1
                for job in group_jobs
                if (
                    run_root / "raw" / job["study_id"]
                    / f"{job['job_id']}.jsonl"
                ).is_file()
            )
            if return_code not in {0, 1}:
                raise RuntimeError(
                    f"batch {group_id} exited {return_code}; see {stderr_path}"
                )
            if after == before:
                raise RuntimeError(
                    f"batch {group_id} made no progress; see {stderr_path}"
                )
    results: list[dict[str, Any]] = []
    for job in jobs:
        raw_path = (
            run_root / "raw" / job["study_id"]
            / f"{job['job_id']}.jsonl"
        )
        rows = read_jsonl(raw_path)
        if len(rows) != 1:
            raise ValueError(f"expected one raw record: {raw_path}")
        results.append({
            "job_id": job["job_id"],
            "skipped": False,
            "completion_status": rows[0]["completion_status"],
        })
    return results


def dry_run_commands(
    jobs: list[dict[str, Any]],
    design: dict[str, Any],
    run_id: str,
) -> list[list[str]]:
    """Build normalized commands without creating work, logs, or raw records."""
    run_root = repo_path(design["paths"]["results_root"]) / run_id
    if not design.get("protocol", {}).get("shared_preprocessing"):
        return [
            _command(
                job,
                design,
                run_root,
                run_root / "work/dry-run" / f"{job['job_id']}.queries.jsonl",
                run_root / "work/dry-run" / f"{job['job_id']}.results.jsonl",
            )
            for job in jobs
        ]
    base_groups: dict[str, list[dict[str, Any]]] = collections.OrderedDict()
    for job in jobs:
        base_groups.setdefault(_batch_key(job), []).append(job)
    groups: dict[str, list[dict[str, Any]]] = collections.OrderedDict()
    for base_jobs in base_groups.values():
        for group_hash, group_jobs in _uniform_trial_groups(base_jobs):
            groups[group_hash] = group_jobs
    commands: list[list[str]] = []
    for group_hash, group_jobs in groups.items():
        group_id = group_hash[:24]
        representative = group_jobs[0]
        work = run_root / "work" / f"batch-{group_id}"
        command = _command(
            representative,
            design,
            run_root,
            work / "queries.jsonl",
            work / "java-results.jsonl",
        )
        trial_ids = sorted({int(job["trial_id"]) for job in group_jobs})
        _replace_option(command, "--repetitions", str(max(trial_ids) + 1))
        command.extend([
            "--repetition-indices", ",".join(str(value) for value in trial_ids),
        ])
        _replace_option(
            command,
            "--experiment-name",
            f"{representative['study_id']}:batch:{group_id}",
        )
        command.extend(["--shared-preprocessing", "--resume"])
        commands.append(command)
    return commands


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--backend", choices=("local", "slurm"), default="local")
    parser.add_argument("--max-concurrent", type=int, default=1)
    parser.add_argument("--job-index", type=int)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print normalized commands without writing or launching jobs",
    )
    parser.add_argument("--submit", action="store_true")
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        matrix = repo_path(args.matrix)
        jobs = read_jsonl(matrix)
        if args.job_index is not None:
            jobs = [jobs[args.job_index]]
        if args.dry_run:
            commands = dry_run_commands(jobs, design, args.run_id)
            print(json.dumps({
                "dry_run": True,
                "would_execute": False,
                "jobs": len(jobs),
                "commands": commands,
            }, indent=2))
            return 0
        if args.backend == "slurm":
            memory = design["resources"].get("memory_limit_mb")
            if memory is None:
                raise ValueError("memory_limit_mb is required for Slurm")
            script = write_array_script(
                repo_path(design["paths"]["results_root"]) / args.run_id / "plan" / f"{matrix.stem}.sbatch",
                sys.executable, args.config, args.run_id, matrix, len(jobs),
                int(design["resources"]["timeout_seconds"]), int(memory),
            )
            print(submit(script) if args.submit else script)
            return 0
        if design.get("protocol", {}).get("shared_preprocessing"):
            if args.max_concurrent != 1:
                raise ValueError(
                    "shared preprocessing requires --max-concurrent 1"
                )
            results = execute_batched(jobs, design, args.run_id, "local")
        else:
            results = run_jobs(
                jobs,
                lambda job: execute_one(job, design, args.run_id, "local"),
                args.max_concurrent,
            )
        print(json.dumps({"jobs": len(results), "results": results}, indent=2))
        infrastructure = {CompletionStatus.INVALID_INPUT.value, CompletionStatus.INTERNAL_ERROR.value, CompletionStatus.INFRASTRUCTURE_BLOCKED.value}
        return 1 if any(item["completion_status"] in infrastructure for item in results) else 0
    except (OSError, ValueError, IndexError, subprocess.SubprocessError) as failure:
        print(f"matrix execution: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
