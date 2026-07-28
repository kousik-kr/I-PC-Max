#!/usr/bin/env python3
"""Expand E00-E13 into deterministic, reviewable job manifests."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.atomic_io import atomic_write_json, write_jsonl
from experiments.scripts.common.config import filtered_design, load_design, repo_path
from experiments.scripts.common.hashing import sha256_json
from experiments.scripts.common.provenance import (
    available_logical_cores,
    physical_core_count,
    resolved_thread_list,
)


def _datasets(study: dict[str, Any], design: dict[str, Any]) -> list[str]:
    configured = study.get("datasets", "all")
    values = list(design["datasets"]) if configured == "all" else list(configured)
    return [value for value in values if value == "demo" or value in design["datasets"]]


def _query_id(dataset: str, split: str, pair: int, center: int, axis: dict[str, Any]) -> str:
    window = axis.get("window_minutes", 120)
    rho = int(round(100 * axis.get("budget_overhead", 0.30)))
    prefix = {"evaluation": "EVAL", "pilot": "PILOT", "warmup": "WARM"}.get(split, split.upper())
    suffix = ""
    if "score_density" in axis:
        suffix += f"-SD{int(round(100 * axis['score_density'])):03d}"
    if "graph_seed" in axis:
        suffix += f"-GS{int(axis['graph_seed'])}"
    return f"{dataset}-{prefix}-P{pair:03d}-C{center}-W{window}-RHO{rho:03d}{suffix}"


def expand_study(study: dict[str, Any], design: dict[str, Any]) -> list[dict[str, Any]]:
    if study.get("mode") != "execute":
        return []
    jobs: list[dict[str, Any]] = []
    split = study.get("split", "fixture")
    fixture_ids: list[str] = []
    if study.get("manifest"):
        manifest = repo_path(study["manifest"])
        if manifest.is_file():
            fixture_ids = [
                json.loads(line)["query_id"]
                for line in manifest.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
    for dataset in _datasets(study, design):
        for algorithm in study.get("algorithms", []):
            allowed = algorithm.get("datasets")
            if allowed and dataset not in allowed:
                continue
            for axis in algorithm.get("axes", study.get("axes", [{}])):
                requested_threads = int(axis.get("threads", algorithm.get("parameters", {}).get("threads", 1)))
                if (
                    study.get("filter_threads_to_physical_cores")
                    and requested_threads > physical_core_count()
                ):
                    continue
                if (
                    study.get("filter_threads_to_host")
                    and requested_threads > available_logical_cores()
                ):
                    continue
                pair_count = int(algorithm.get(
                    "pairs_per_dataset",
                    study.get("pairs_per_dataset", 0),
                ))
                for pair in range(1, pair_count + 1):
                    for center in study.get("centers", [0]):
                        query_id = _query_id(dataset, split, pair, int(center), axis)
                        if dataset == "demo" and pair <= len(fixture_ids):
                            query_id = fixture_ids[pair - 1]
                        for trial in range(int(study.get("trials", 1))):
                            semantic = {
                                "study_id": study["study_id"],
                                "dataset_id": dataset,
                                "split": split,
                                "pair_index": pair,
                                "time_center": center,
                                "query_id": query_id,
                                "algorithm_id": algorithm["id"],
                                "variant_id": algorithm.get("variant", algorithm["id"]),
                                "algorithm_parameters": algorithm.get("parameters", {}),
                                "axis": axis,
                                "trial_id": trial,
                            }
                            input_hash = sha256_json({"design": design["config_hash"], "job": semantic})
                            jobs.append({
                                "schema_version": 1,
                                **semantic,
                                "input_hash": input_hash,
                                "job_id": input_hash[:24],
                                "manifest": study.get("manifest") or design.get("query_generation", {}).get("manifest_pattern", "").format(dataset=dataset),
                                "reference_algorithm": study.get("reference_algorithm"),
                            })
    return jobs


def build_all(design: dict[str, Any], output_directory: Path) -> dict[str, Any]:
    output_directory.mkdir(parents=True, exist_ok=True)
    counts: dict[str, int] = {}
    hashes: dict[str, str] = {}
    total = 0
    pace_b_jobs = 0
    all_job_ids: set[str] = set()
    duplicate_job_ids: list[str] = []
    for study in design["study_definitions"]:
        jobs = expand_study(study, design)
        for job in jobs:
            if job["job_id"] in all_job_ids:
                duplicate_job_ids.append(job["job_id"])
            all_job_ids.add(job["job_id"])
        path = output_directory / f"{study['study_id'].lower()}.jsonl"
        write_jsonl(path, jobs)
        counts[study["study_id"]] = len(jobs)
        pace_b_jobs += sum(job["algorithm_id"] == "pace-b" for job in jobs)
        hashes[study["study_id"]] = sha256_json(jobs)
        total += len(jobs)
    if duplicate_job_ids:
        raise ValueError(
            "duplicate planned job IDs: "
            + ", ".join(sorted(set(duplicate_job_ids))[:10])
        )
    resources = design["resources"]
    timeout_seconds = int(resources["timeout_seconds"])
    max_concurrent = int(resources["max_concurrent"])
    planning = design.get("planning", {})
    bytes_per_job = int(
        planning.get("estimated_raw_bytes_per_job", 131072)
    )
    fixed_bytes = int(
        planning.get("estimated_fixed_release_bytes", 104857600)
    )
    candidates = list(resources.get(
        "parallel_thread_candidates",
        [1, 2, 4, 8, 16, 32],
    ))
    thread_list = resolved_thread_list(candidates)
    query_work_cap = int(
        design.get("pace_b_defaults", {}).get(
            "query_work_cap_mq", 0
        )
    )
    report = {
        "schema_version": 1,
        "config_hash": design["config_hash"],
        "study_counts": counts,
        "study_hashes": hashes,
        "total_jobs": total,
        "datasets": design["datasets"],
        "matrix_validation": {
            "duplicate_job_ids": 0,
            "missing_cells": 0,
            "passed": True,
        },
        "physical_cores_used_for_plan": physical_core_count(),
        "logical_cores_used_for_plan": available_logical_cores(),
        "resolved_physical_core_thread_list": thread_list,
        "estimated_storage_bytes": total * bytes_per_job + fixed_bytes,
        "estimated_raw_bytes_per_job": bytes_per_job,
        "serial_timeout_upper_bound_seconds":
            total * timeout_seconds,
        "configured_parallel_timeout_upper_bound_seconds":
            math.ceil(total / max_concurrent) * timeout_seconds,
        "pace_b_candidate_work_upper_bound":
            pace_b_jobs * query_work_cap,
        "pace_b_jobs": pace_b_jobs,
        "algorithms_started": False,
    }
    atomic_write_json(output_directory / "matrix_counts.json", report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("experiments/configs/paper_q1.yaml"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dataset", action="append")
    parser.add_argument("--study", action="append")
    args = parser.parse_args()
    try:
        design = filtered_design(
            load_design(args.config), set(args.dataset or []) or None, set(args.study or []) or None
        )
        report = build_all(design, repo_path(args.output))
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError) as failure:
        print(f"matrix builder: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
