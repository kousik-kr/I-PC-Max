#!/usr/bin/env python3
"""Run a bounded serial PACE-B query for every dataset/distance-band stratum.

Queries sharing one dataset payload run sequentially in one JVM so the pilot
does not charge repeated graph loading against a short query allowance. The
recorded preprocessing time is added back to every stratum when projecting the
repository's process-isolated full-job policy. Each process is bounded by an
outer wall-clock limit and every query remains bounded by M_c, M_b, and M_q.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import statistics
import subprocess
import sys
import time
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, write_jsonl
from experiments.scripts.common.config import load_design
from experiments.scripts.common.hashing import sha256_file
from experiments.scripts.common.provenance import physical_core_count


REPO_ROOT = Path(__file__).resolve().parents[2]
DATASETS = ("NY", "FLA", "CAL", "USA")
AXES = (
    {"distance_bin": 1, "window": 120, "rho": 0.1},
    {"distance_bin": 2, "window": 360, "rho": 0.3},
    {"distance_bin": 3, "window": 120, "rho": 0.5},
    {"distance_bin": 4, "window": 300, "rho": 0.3},
    {"distance_bin": 5, "window": 120, "rho": 0.3},
)
PARAMETERS = {
    "NY": {"theta": 1, "l": 4, "kc": 4, "kf": 2},
    "FLA": {"theta": 2, "l": 8, "kc": 8, "kf": 4},
    "CAL": {"theta": 3, "l": 16, "kc": 16, "kf": 8},
    "USA": {"theta": 4, "l": 32, "kc": 32, "kf": 16},
}


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def _select(dataset: str, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    for axis in AXES:
        matches = [
            row for row in rows
            if row["metadata"]["split"] == "evaluation"
            and row["distance_bin"] == axis["distance_bin"]
            and row["window_length"] == axis["window"]
            and math.isclose(row["budget_slack"], axis["rho"])
            and (
                row["query_id"].endswith("-SD040")
                if dataset == "NY" and axis["distance_bin"] == 5
                else "-SD" not in row["query_id"]
                and "-GS" not in row["query_id"]
            )
        ]
        if not matches:
            raise ValueError(
                f"no pilot query for {dataset} and axis {axis}"
            )
        selected.append(min(matches, key=lambda row: row["query_id"]))
    return selected


def _percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[math.ceil(fraction * len(ordered)) - 1]


def _total_memory_bytes() -> int | None:
    try:
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) * 1024
    except OSError:
        return None
    return None


def _query_runtime(record: dict[str, Any], fallback: float) -> float:
    value = record.get("timing_ns", {}).get("query_total")
    return fallback if value is None else float(value) / 1_000_000_000


def _process_isolated_runtime(
    record: dict[str, Any],
    fallback: float,
) -> float:
    timings = record.get("timing_ns", {})
    query = timings.get("query_total")
    preprocessing = timings.get("preprocessing_total")
    if query is None or preprocessing is None:
        return fallback
    return float(query + preprocessing) / 1_000_000_000


def run_pilot(
    output_directory: Path,
    query_runtime_allowance: int,
    dataset_load_timeout: int,
) -> dict[str, Any]:
    output_directory.mkdir(parents=True, exist_ok=True)
    jar = REPO_ROOT / "target/pace-bench.jar"
    if not jar.is_file():
        raise FileNotFoundError(f"missing benchmark JAR: {jar}")
    design = load_design(
        REPO_ROOT / "experiments/configs/paper_q1_server_24c_250g.yaml"
    )
    executions: list[dict[str, Any]] = []
    process_runs: list[dict[str, Any]] = []
    manifest_rows: dict[str, dict[str, Any]] = {}
    selected_rows: list[tuple[str, dict[str, Any]]] = []
    for dataset in DATASETS:
        combined = (
            REPO_ROOT
            / f"experiments/manifests/queries/{dataset}/paper_q1.jsonl"
        )
        rows = _read_jsonl(combined)
        manifest_rows.update({row["query_id"]: row for row in rows})
        selected_rows.extend((dataset, row) for row in _select(dataset, rows))

    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for dataset, row in selected_rows:
        groups.setdefault(
            (dataset, row["metadata"]["dataset_path"]),
            [],
        ).append(row)
    for group_index, ((dataset, dataset_path), rows) in enumerate(groups.items()):
        parameters = PARAMETERS[dataset]
        query_file = (
            output_directory
            / "queries"
            / f"group-{group_index:02d}-{dataset}.jsonl"
        )
        write_jsonl(query_file, rows)
        raw = (
            output_directory
            / "raw"
            / f"group-{group_index:02d}-{dataset}.jsonl"
        )
        raw.parent.mkdir(parents=True, exist_ok=True)
        raw.unlink(missing_ok=True)
        command = [
            "java",
            "-Xmx250g",
            "-jar",
            str(jar),
            "--algorithm",
            "pace-b",
            "--dataset",
            dataset_path,
            "--query-file",
            str(query_file),
            "--output-jsonl",
            str(raw),
            "--experiment-name",
            "pace-paper-stratified-pilot-v1",
            "--theta",
            str(parameters["theta"]),
            "--pivot-limit-l",
            str(parameters["l"]),
            "--connector-limit-kc",
            str(parameters["kc"]),
            "--frontier-limit-kf",
            str(parameters["kf"]),
            "--connector-expansion-cap-mc",
            "5000000",
            "--breakpoint-cap-mb",
            "1000000",
            "--query-work-cap-mq",
            "250000000",
            "--threads",
            "24",
            "--deterministic",
            "--collect-phase-timings",
            "--collect-memory",
            "--collect-internal-counters",
        ]
        external_limit = (
            dataset_load_timeout
            + len(rows) * query_runtime_allowance
            + 60
        )
        print(
            f"pilot_group_start dataset={dataset} path={dataset_path} "
            f"queries={len(rows)} external_limit={external_limit}",
            flush=True,
        )
        started = time.monotonic()
        group_external_timeout = False
        return_code: int | None = None
        try:
            completed = subprocess.run(
                command,
                cwd=REPO_ROOT,
                timeout=external_limit,
                check=False,
            )
            return_code = completed.returncode
        except subprocess.TimeoutExpired:
            group_external_timeout = True
        elapsed = time.monotonic() - started
        result_rows = _read_jsonl(raw) if raw.is_file() else []
        records_by_query = {
            record["query"]["query_id"]: record
            for record in result_rows
        }
        process_runs.append({
            "dataset_id": dataset,
            "dataset_path": dataset_path,
            "query_count": len(rows),
            "external_timeout_seconds": external_limit,
            "external_timeout": group_external_timeout,
            "return_code": return_code,
            "wall_seconds": elapsed,
            "command": command,
        })
        for row in rows:
            record = records_by_query.get(row["query_id"])
            external_timeout = (
                group_external_timeout and record is None
            )
            execution = {
                "dataset_id": dataset,
                "query_id": row["query_id"],
                "distance_bin": row["distance_bin"],
                "window_length": row["window_length"],
                "rho": row["budget_slack"],
                "score_density": (
                    0.40 if row["query_id"].endswith("-SD040")
                    else 0.20
                ),
                "parameters": parameters,
                "process_group": group_index,
                "external_timeout_seconds": external_limit,
                "external_timeout": external_timeout,
                "return_code": return_code,
                "wall_seconds": (
                    _query_runtime(record, query_runtime_allowance)
                    if record is not None
                    else float(query_runtime_allowance)
                ),
                "record": record,
            }
            executions.append(execution)
            status = (
                "EXTERNAL_TIMEOUT"
                if external_timeout
                else record["status"]["status_code"]
                if record is not None
                else "NO_RECORD"
            )
            print(
                f"pilot_finish dataset={dataset} "
                f"band={row['distance_bin']} status={status} "
                f"query_seconds={execution['wall_seconds']:.3f}",
                flush=True,
            )
            atomic_write_json(
                output_directory / "progress.json",
                {
                    "schema_version": 1,
                    "completed_jobs": len(executions),
                    "planned_jobs": len(DATASETS) * len(AXES),
                    "last_status": status,
                    "process_groups_completed": len(process_runs),
                    "process_groups_planned": len(groups),
                    "full_matrix_launched": False,
                },
            )

    runtimes = [
        _process_isolated_runtime(
            item["record"],
            item["wall_seconds"] + dataset_load_timeout,
        )
        if item["record"] is not None
        else item["wall_seconds"] + dataset_load_timeout
        for item in executions
    ]
    query_runtimes = [
        _query_runtime(item["record"], item["wall_seconds"])
        if item["record"] is not None
        else item["wall_seconds"]
        for item in executions
    ]
    records = [
        item["record"]
        for item in executions
        if item["record"] is not None
    ]
    peak_rss = [
        int(record["memory_bytes"]["peak_rss"])
        for record in records
        if record["memory_bytes"].get("peak_rss") is not None
    ]
    statuses = [
        (
            "EXTERNAL_TIMEOUT"
            if item["external_timeout"]
            else item["record"]["status"]["status_code"]
            if item["record"] is not None
            else "NO_RECORD"
        )
        for item in executions
    ]
    cap_count = sum(
        bool(record["status"].get("cap_triggered"))
        for record in records
    )
    completed_count = sum(
        status in {"COMPLETED", "NO_FEASIBLE_PATH"}
        for status in statuses
    )
    physical_cores = physical_core_count()
    total_memory = _total_memory_bytes()
    measured_peak = max(peak_rss) if peak_rss else None
    cpu_limit = max(1, physical_cores // 24)
    memory_limit = (
        max(1, int(total_memory * 0.85) // measured_peak)
        if total_memory and measured_peak
        else 1
    )
    safe_concurrency = min(1, cpu_limit, memory_limit)

    stratum_runtime = {
        (item["dataset_id"], item["distance_bin"]):
            _process_isolated_runtime(
                item["record"],
                item["wall_seconds"] + dataset_load_timeout,
            )
            if item["record"] is not None
            else item["wall_seconds"] + dataset_load_timeout
        for item in executions
    }
    ledger_path = (
        REPO_ROOT
        / "experiments/results/diagnostics/"
        "pace_paper_readiness_20260729/matrices/"
        "canonical_job_ledger.jsonl"
    )
    projected_seconds = 0.0
    projected_pace_b_seconds = 0.0
    fallback_timeout = int(design["resources"]["timeout_seconds"])
    ledger_rows = _read_jsonl(ledger_path)
    for job in ledger_rows:
        if job["dataset_id"] == "demo":
            projected_seconds += fallback_timeout
            continue
        if job["algorithm_id"] == "pace-b":
            query = manifest_rows.get(job["query_id"])
            band = query["distance_bin"] if query else None
            duration = stratum_runtime.get(
                (job["dataset_id"], band),
                fallback_timeout,
            )
            projected_pace_b_seconds += duration
            projected_seconds += duration
        else:
            projected_seconds += fallback_timeout

    completion_rate = (
        completed_count / len(executions)
        if executions else 0.0
    )
    cap_rate = cap_count / len(executions) if executions else 0.0
    operationally_acceptable = (
        completion_rate >= 0.90
        and cap_rate <= 0.10
        and not any(status in {"EXTERNAL_TIMEOUT", "NO_RECORD"} for status in statuses)
        and projected_seconds / safe_concurrency <= 90 * 86400
    )
    summary = {
        "schema_version": 1,
        "suite": "pace-paper-stratified-pilot-v1",
        "execution_contract": {
            "simultaneous_queries": 1,
            "threads_per_query_maximum": 24,
            "heap_per_process": "250g",
            "dataset_reuse_within_process": True,
            "projection_adds_preprocessing_per_isolated_job": True,
            "outer_process_group_timeout": True,
            "algorithm_caps": {
                "M_c": 5000000,
                "M_b": 1000000,
                "M_q": 250000000,
                "M_q_contract": "PACE-MQ-TOTAL-WORK-v2",
            },
        },
        "jar_sha256": sha256_file(jar),
        "config_hash": design["config_hash"],
        "job_ledger_sha256": sha256_file(ledger_path),
        "planned_jobs": len(DATASETS) * len(AXES),
        "observed_jobs": len(executions),
        "coverage": {
            "datasets": list(DATASETS),
            "distance_bins": [axis["distance_bin"] for axis in AXES],
            "window_lengths": sorted({axis["window"] for axis in AXES}),
            "budget_slacks": sorted({axis["rho"] for axis in AXES}),
            "score_densities": sorted(
                {item["score_density"] for item in executions}
            ),
            "parameter_sets": PARAMETERS,
        },
        "runtime_seconds": {
            "scope": "projected_process_isolated_preprocessing_plus_query",
            "median": statistics.median(runtimes) if runtimes else None,
            "p95": _percentile(runtimes, 0.95),
            "maximum": max(runtimes) if runtimes else None,
        },
        "query_only_runtime_seconds": {
            "median": (
                statistics.median(query_runtimes)
                if query_runtimes else None
            ),
            "p95": _percentile(query_runtimes, 0.95),
            "maximum": max(query_runtimes) if query_runtimes else None,
        },
        "peak_rss_bytes": {
            "maximum": measured_peak,
            "observations": len(peak_rss),
        },
        "completion_rate": completion_rate,
        "cap_rate": cap_rate,
        "statuses": {
            status: statuses.count(status)
            for status in sorted(set(statuses))
        },
        "quality_reference_available_count": sum(
            record["status"]["reference_available"]
            for record in records
        ),
        "safe_concurrency": {
            "processes": safe_concurrency,
            "threads_per_process": 24,
            "physical_cores": physical_cores,
            "total_memory_bytes": total_memory,
            "measured_peak_rss_bytes": measured_peak,
            "cpu_bound": cpu_limit,
            "memory_bound": memory_limit,
            "user_process_limit": 1,
        },
        "projection": {
            "ledger_rows": len(ledger_rows),
            "pace_b_seconds": projected_pace_b_seconds,
            "all_matrix_seconds_conservative": projected_seconds,
            "safe_concurrency": safe_concurrency,
            "projected_wall_seconds": projected_seconds / safe_concurrency,
            "non_pace_b_policy": "configured timeout upper bound",
            "pace_b_policy": (
                "dataset-and-distance-band pilot preprocessing plus "
                "query runtime, matching process-isolated jobs"
            ),
        },
        "operationally_acceptable": operationally_acceptable,
        "executions": executions,
        "process_runs": process_runs,
        "full_matrix_launched": False,
    }
    atomic_write_json(output_directory / "summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "experiments/results/diagnostics/"
            "pace_paper_readiness_20260729/stratified_pilot"
        ),
    )
    parser.add_argument(
        "--query-runtime-allowance-seconds",
        type=int,
        default=300,
    )
    parser.add_argument(
        "--dataset-load-timeout-seconds",
        type=int,
        default=1800,
    )
    args = parser.parse_args()
    if args.query_runtime_allowance_seconds < 1:
        parser.error("--query-runtime-allowance-seconds must be positive")
    if args.dataset_load_timeout_seconds < 1:
        parser.error("--dataset-load-timeout-seconds must be positive")
    output = (
        args.output
        if args.output.is_absolute()
        else REPO_ROOT / args.output
    )
    try:
        summary = run_pilot(
            output,
            args.query_runtime_allowance_seconds,
            args.dataset_load_timeout_seconds,
        )
    except (OSError, ValueError) as failure:
        print(f"stratified_pilot: {failure}", file=sys.stderr)
        return 1
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if summary["operationally_acceptable"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
