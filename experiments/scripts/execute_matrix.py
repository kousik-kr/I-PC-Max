#!/usr/bin/env python3
"""Execute a deterministic matrix locally or emit/submit a Slurm array."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_text, write_jsonl
from experiments.scripts.common.config import load_design, repo_path
from experiments.scripts.common.status import CompletionStatus
from experiments.scripts.common.toolchain import environment, executable
from experiments.scripts.executors.local import run_jobs
from experiments.scripts.executors.slurm import submit, write_array_script


JAVA_STATUS = {
    "COMPLETED": CompletionStatus.SUCCESS.value,
    "NO_FEASIBLE_PATH": CompletionStatus.SUCCESS.value,
    "TIMEOUT": CompletionStatus.TIMEOUT.value,
    "OUT_OF_MEMORY": CompletionStatus.OUT_OF_MEMORY.value,
    "LIMIT_EXCEEDED": CompletionStatus.RESOURCE_LIMIT_EXCEEDED.value,
    "FUNCTION_HORIZON_EXCEEDED": CompletionStatus.FUNCTION_HORIZON_EXCEEDED.value,
    "INVALID_QUERY": CompletionStatus.INVALID_INPUT.value,
    "INVALID_CONFIGURATION": CompletionStatus.INVALID_INPUT.value,
    "ERROR": CompletionStatus.INTERNAL_ERROR.value,
}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


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


def _resolved_pace_b(run_root: Path) -> dict[str, int]:
    path = run_root / "provenance" / "resolved_pace_b.json"
    if not path.is_file():
        raise FileNotFoundError("PACE-B parameters are unresolved; run the pilot stage first")
    value = json.loads(path.read_text(encoding="utf-8"))
    return {"anchor_limit": int(value["anchor_limit"]), "k": int(value["k"])}


def _command(
    job: dict[str, Any], design: dict[str, Any], run_root: Path, query_file: Path, java_output: Path
) -> list[str]:
    resources = design["resources"]
    memory = resources.get("memory_limit_mb")
    if memory is None:
        raise ValueError("memory_limit_mb must be resolved before execution")
    parameters = dict(job.get("algorithm_parameters") or {})
    parameters.update(job.get("axis") or {})
    if parameters.pop("resolved_pace_b", False):
        parameters.update(_resolved_pace_b(run_root))
    dataset_path = "demo"
    if job["dataset_id"] != "demo":
        dataset_path = str(repo_path(f"data/input/{job['dataset_id']}"))
        if "score_density" in parameters:
            pattern = design["graph_variants"]["score_density_pattern"]
            dataset_path = str(repo_path(pattern.format(dataset=job["dataset_id"], percent=int(round(100 * parameters["score_density"])))))
        elif "graph_seed" in parameters and int(parameters["graph_seed"]) != int(design["seeds"]["graph_main"]):
            pattern = design["graph_variants"]["graph_seed_pattern"]
            dataset_path = str(repo_path(pattern.format(dataset=job["dataset_id"], seed=int(parameters["graph_seed"]))))
    command = [
        executable("java"), "-jar", str(repo_path(design["paths"]["jar"])),
        "--algorithm", job["algorithm_id"],
        "--dataset", dataset_path,
        "--query-file", str(query_file),
        "--output-jsonl", str(java_output),
        "--experiment-name", f"{job['study_id']}:{job['job_id']}",
        "--repetitions", "1", "--warmup-runs", "0",
        "--threads", str(parameters.pop("threads", 1)),
        "--timeout-seconds", str(resources["timeout_seconds"]),
        "--memory-limit-mb", str(memory),
        "--seed", str(design.get("seeds", {}).get("schedule", 20260725)),
        "--deterministic", "--collect-phase-timings", "--collect-memory", "--collect-internal-counters",
    ]
    ablation = parameters.pop("ablation", None)
    if ablation:
        command.extend(["--ablation", str(ablation)])
    flags = {
        "theta": "--theta", "anchor_limit": "--anchor-limit", "k": "--k",
        "rpq_step_minutes": "--rpq-step-minutes", "baseline_k": "--baseline-k",
        "max_enumerated_paths": "--max-enumerated-paths", "max_labels": "--max-labels",
        "max_expansions": "--max-expansions", "max_frontier_fragments": "--max-frontier-fragments",
    }
    for name, flag in flags.items():
        if name in parameters:
            command.extend([flag, str(parameters.pop(name))])
    ignored_workload_axes = {"window_minutes", "budget_overhead", "score_density", "graph_seed", "compression"}
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
        write_jsonl(query_file, [row])
        command = _command(job, design, run_root, query_file, java_output)
        timeout = int(design["resources"]["timeout_seconds"]) + 90
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
        "exit_code": return_code,
        "stdout_path": stdout_path.relative_to(repo_path(".")).as_posix(),
        "stderr_path": stderr_path.relative_to(repo_path(".")).as_posix(),
        "java_record": java_record,
        "error": error,
    }
    write_jsonl(raw_path, [record])
    return {"job_id": job["job_id"], "skipped": False, "completion_status": status}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--backend", choices=("local", "slurm"), default="local")
    parser.add_argument("--max-concurrent", type=int, default=1)
    parser.add_argument("--job-index", type=int)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--submit", action="store_true")
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        matrix = repo_path(args.matrix)
        jobs = read_jsonl(matrix)
        if args.job_index is not None:
            jobs = [jobs[args.job_index]]
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
