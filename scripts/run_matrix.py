#!/usr/bin/env python3
"""Schedule explicitly declared PACE experiment jobs without regenerating queries."""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def load_config(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        try:
            import yaml  # type: ignore
        except ImportError as exc:
            raise SystemExit("configuration is not JSON-compatible YAML and PyYAML is unavailable") from exc
        return yaml.safe_load(text)


def normalized_jobs(config: dict) -> list[dict]:
    required = {
        "experiment_name", "datasets", "query_manifest", "algorithms", "ablations",
        "repetitions", "warmups", "timeout_seconds", "memory_limit_mb", "threads",
        "parameter_grid", "output_location", "reference_algorithm", "seed",
    }
    missing = sorted(required - config.keys())
    if missing:
        raise SystemExit(f"matrix configuration missing: {', '.join(missing)}")
    axes = config.get("parameter_grid") or [{}]
    jobs: list[dict] = []
    for dataset, algorithm, ablation, parameters in itertools.product(
        config["datasets"], config["algorithms"], config["ablations"], axes
    ):
        if isinstance(dataset, str):
            dataset = {"id": Path(dataset).name, "path": dataset}
        if isinstance(algorithm, str):
            algorithm = {"id": algorithm}
        if isinstance(ablation, str):
            ablation = {"id": ablation}
        job = {
            "dataset": dataset,
            "algorithm": algorithm,
            "ablation": ablation,
            "parameters": {**algorithm.get("parameters", {}), **ablation.get("parameters", {}), **parameters},
        }
        if job["algorithm"]["id"] != "pace-b" and job["ablation"]["id"] != "none":
            continue
        requested_threads = job["parameters"].get("threads", config["threads"])
        if config.get("limit_threads_to_logical_cores", False) and requested_threads > (os.cpu_count() or 1):
            continue
        jobs.append(job)
    return jobs


def command(config: dict, job: dict, resume: bool) -> tuple[str, list[str], Path, Path]:
    payload = json.dumps(job, sort_keys=True, separators=(",", ":"))
    job_id = hashlib.sha256(payload.encode()).hexdigest()[:16]
    output_dir = ROOT / config["output_location"]
    log_dir = ROOT / "results" / "logs" / config["experiment_name"]
    output = output_dir / f"{job_id}.jsonl"
    log = log_dir / f"{job_id}.log"
    cmd = [
        "java", "-jar", str(ROOT / "target" / "pace-bench.jar"),
        "--algorithm", job["algorithm"]["id"],
        "--ablation", job["ablation"]["id"],
        "--dataset", str(job["dataset"]["path"]),
        "--query-file", str(ROOT / config["query_manifest"]),
        "--output-jsonl", str(output),
        "--experiment-name", config["experiment_name"],
        "--repetitions", str(config["repetitions"]),
        "--warmup-runs", str(config["warmups"]),
        "--timeout-seconds", str(config["timeout_seconds"]),
        "--memory-limit-mb", str(config["memory_limit_mb"]),
        "--threads", str(job["parameters"].get("threads", config["threads"])),
        "--seed", str(config["seed"]), "--deterministic",
    ]
    flag_names = {
        "theta": "--theta", "anchor_limit": "--anchor-limit", "k": "--k",
        "rpq_step_minutes": "--rpq-step-minutes", "baseline_k": "--baseline-k",
        "max_enumerated_paths": "--max-enumerated-paths", "max_labels": "--max-labels",
        "max_expansions": "--max-expansions", "max_frontier_fragments": "--max-frontier-fragments",
    }
    for name, flag in flag_names.items():
        value = job["parameters"].get(name)
        if value is not None:
            if value == "unbounded" and name == "anchor_limit":
                value = 2147483647
            cmd += [flag, str(value)]
    reference = config.get("reference_algorithm")
    if reference and reference != job["algorithm"]["id"]:
        cmd += ["--reference-algorithm", reference]
    if config.get("verify_output", False):
        cmd.append("--verify-output")
    if resume:
        cmd.append("--resume")
    return job_id, cmd, output, log


def run_job(item: tuple[str, list[str], Path, Path]) -> dict:
    job_id, cmd, output, log = item
    output.parent.mkdir(parents=True, exist_ok=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("a", encoding="utf-8") as stream:
        completed = subprocess.run(cmd, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=False)
    return {"job_id": job_id, "return_code": completed.returncode, "output": str(output), "log": str(log)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--jobs", type=int, default=1)
    parser.add_argument("--filter", default="")
    parser.add_argument("--only-failed", action="store_true")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    config = load_config(args.config)
    if not (ROOT / "target" / "pace-bench.jar").exists() and not args.dry_run:
        subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=ROOT, check=True)
    scheduled = [command(config, job, args.resume) for job in normalized_jobs(config)]
    if args.filter:
        scheduled = [item for item in scheduled if args.filter in " ".join(item[1])]
    manifest_path = ROOT / "results" / "logs" / config["experiment_name"] / "scheduled_jobs.jsonl"
    previous_failed: set[str] = set()
    if args.only_failed and manifest_path.exists():
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            record = json.loads(line)
            if record.get("return_code", 0) != 0:
                previous_failed.add(record["job_id"])
        scheduled = [item for item in scheduled if item[0] in previous_failed]
    if not args.resume and not args.dry_run:
        scheduled = [item for item in scheduled if not item[2].exists()]
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    if args.dry_run:
        for job_id, cmd, output, log in scheduled:
            print(json.dumps({"job_id": job_id, "command": cmd, "output": str(output), "log": str(log)}))
        return 0
    results: list[dict] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for result in pool.map(run_job, scheduled):
            results.append(result)
            print(json.dumps(result), flush=True)
    with manifest_path.open("a", encoding="utf-8") as stream:
        for result in results:
            stream.write(json.dumps(result, sort_keys=True) + "\n")
    return 1 if any(result["return_code"] != 0 for result in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
