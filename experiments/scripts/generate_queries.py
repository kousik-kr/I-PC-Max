#!/usr/bin/env python3
"""Validate paper query manifests or invoke the existing Java graph generator."""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import subprocess
import sys
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.config import load_design, repo_path
from experiments.scripts.common.hashing import sha256_file
from experiments.scripts.common.toolchain import environment, executable


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            try:
                row = json.loads(line)
            except json.JSONDecodeError as failure:
                raise ValueError(f"{path}:{number}: {failure}") from failure
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{number}: query row is not an object")
            rows.append(row)
    return rows


def validate_manifest(path: Path, dataset: str, design: dict[str, Any]) -> dict[str, Any]:
    rows = read_jsonl(path)
    ids: set[str] = set()
    pairs: dict[str, set[tuple[int, int]]] = {name: set() for name in ("pilot", "warmup", "evaluation")}
    centers: dict[str, set[int]] = {name: set() for name in pairs}
    pair_centers: dict[tuple[str, int, int], set[int]] = collections.defaultdict(set)
    evaluation_bands: dict[str, set[tuple[int, int]]] = collections.defaultdict(set)
    errors: list[str] = []
    for row in rows:
        query_id = row.get("query_id")
        if not isinstance(query_id, str) or query_id in ids:
            errors.append(f"missing or duplicate query_id: {query_id!r}")
        else:
            ids.add(query_id)
        if row.get("dataset_id") != dataset:
            errors.append(f"query {query_id} has dataset_id {row.get('dataset_id')!r}")
        metadata = row.get("metadata") or {}
        split = metadata.get("split")
        center = metadata.get("time_center")
        if split not in pairs:
            errors.append(f"query {query_id} has invalid metadata.split {split!r}")
            continue
        pairs[split].add((row.get("source"), row.get("destination")))
        if isinstance(center, int):
            centers[split].add(center)
            pair_centers[(split, row.get("source"), row.get("destination"))].add(center)
        support_end = metadata.get("function_support_end")
        if not isinstance(support_end, (int, float)):
            errors.append(f"query {query_id} lacks metadata.function_support_end")
        elif row.get("interval_end", 0) + row.get("budget", 0) > support_end:
            errors.append(f"query {query_id} exceeds function support")
        if metadata.get("budget_definition") != design["workload"]["budget_definition"]:
            errors.append(f"query {query_id} has the wrong budget definition")
        if metadata.get("evaluation_grid_minutes") != design["workload"]["evaluation_grid_minutes"]:
            errors.append(f"query {query_id} has the wrong evaluation grid")
        band = metadata.get("distance_band")
        if split == "evaluation":
            if band not in {"B1", "B2", "B3", "B4", "B5"}:
                errors.append(f"query {query_id} has invalid distance band {band!r}")
            else:
                evaluation_bands[band].add((row.get("source"), row.get("destination")))
    expected = design["workload"]["pair_splits"]
    for split, count in expected.items():
        if len(pairs[split]) != count:
            errors.append(f"{split} has {len(pairs[split])} unique pairs; expected {count}")
        required_centers = set(design["workload"]["time_centers_minutes"])
        if centers[split] != required_centers:
            errors.append(f"{split} centers are {sorted(centers[split])}; expected {sorted(required_centers)}")
        for source, destination in pairs[split]:
            actual = pair_centers[(split, source, destination)]
            if actual != required_centers:
                errors.append(f"{split} pair {source}->{destination} has centers {sorted(actual)}")
    for band in ("B1", "B2", "B3", "B4", "B5"):
        if len(evaluation_bands[band]) != 20:
            errors.append(f"evaluation band {band} has {len(evaluation_bands[band])} pairs; expected 20")
    for left, right in (("pilot", "warmup"), ("pilot", "evaluation"), ("warmup", "evaluation")):
        overlap = pairs[left] & pairs[right]
        if overlap:
            errors.append(f"{left}/{right} pair leakage: {len(overlap)} pairs")
    return {
        "dataset_id": dataset,
        "path": path.as_posix(),
        "checksum": sha256_file(path),
        "rows": len(rows),
        "pair_counts": {key: len(value) for key, value in pairs.items()},
        "errors": errors,
    }


def validate_all(design: dict[str, Any]) -> dict[str, Any]:
    pattern = design["query_generation"]["manifest_pattern"]
    records = []
    from experiments.scripts.build_matrices import expand_study
    required_ids: dict[str, set[str]] = {dataset: set() for dataset in design["datasets"]}
    for study in design["study_definitions"]:
        for job in expand_study(study, design):
            if job["dataset_id"] in required_ids:
                required_ids[job["dataset_id"]].add(job["query_id"])
    for dataset in design["datasets"]:
        path = repo_path(pattern.format(dataset=dataset))
        if not path.is_file():
            records.append({"dataset_id": dataset, "path": path.as_posix(), "errors": ["manifest is missing"]})
        else:
            record = validate_manifest(path, dataset, design)
            actual_ids = {row["query_id"] for row in read_jsonl(path)}
            missing = required_ids[dataset] - actual_ids
            if missing:
                record["errors"].append(
                    f"manifest lacks {len(missing)} matrix query variants; first missing ID: {sorted(missing)[0]}"
                )
            record["required_matrix_query_ids"] = len(required_ids[dataset])
            records.append(record)
    return {"datasets": records, "passed": all(not item["errors"] for item in records)}


def invoke_existing_generator(design: dict[str, Any], datasets: list[str], dry_run: bool) -> int:
    """Use the repository's Java generator; Python never loads the road graph."""
    jar = repo_path(design["paths"]["jar"])
    if not jar.is_file():
        subprocess.run([executable("mvn"), "-q", "-DskipTests", "package"], cwd=repo_path("."), env=environment(), check=True)
    output = repo_path("experiments/manifests/querygen_phase5")
    command = [
        "java", "-cp", str(jar), design["query_generation"]["java_main_class"],
        "--datasets", ",".join(datasets),
        "--data-root", str(repo_path(design["paths"]["data_root"])),
        "--output-root", str(output),
        "--config", str(repo_path(design["query_generation"]["configuration"])),
        "--seed", str(design["seeds"]["query_evaluation"]),
    ]
    if dry_run:
        command.append("--dry-run")
    print(json.dumps({"command": command, "note": "existing Phase-5 generator; paper contract is validated separately"}))
    return subprocess.run(command, cwd=repo_path("."), env=environment(), check=False).returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path("experiments/configs/paper_q1.yaml"))
    parser.add_argument("--action", choices=("validate", "phase5", "phase5-dry-run"), default="validate")
    parser.add_argument("--dataset", action="append")
    args = parser.parse_args()
    try:
        design = load_design(args.config)
        datasets = args.dataset or design["datasets"]
        if args.action == "validate":
            report = validate_all(design)
            print(json.dumps(report, indent=2, sort_keys=True))
            return 0 if report["passed"] else 1
        return invoke_existing_generator(design, datasets, args.action.endswith("dry-run"))
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        print(f"query generation: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
