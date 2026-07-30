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

from experiments.scripts.common.config import load_design, load_document, repo_path
from experiments.scripts.common.hashing import sha256_file
from experiments.scripts.common.toolchain import environment, executable

WITNESS_BUDGET_DEFINITION = (
    "GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME"
)
REQUIRED_WITNESS_METADATA = {
    "budget_derivation_rule",
    "budget_evidence",
    "checksum_scope_version",
    "dataset_checksum",
    "dataset_payload_checksum",
    "delta_minutes",
    "final_generated_budget",
    "grid_departure_count",
    "lower_bound_routing_contract",
    "t_hat_min_delta",
    "temporal_attribute_checksum",
    "witness_evaluated_departure_end",
    "witness_evaluated_departure_start",
    "witness_evidence_contract",
    "witness_identity_contract",
    "witness_path_checksum_sha256",
    "witness_path_edge_count",
    "witness_path_lower_bound_distance",
    "witness_travel_time_evidence_sha256",
    "witness_travel_time_max",
    "witness_travel_time_min",
}


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
    budget_definition_mismatches = 0
    grid_mismatches = 0
    for row in rows:
        query_id = row.get("query_id")
        if not isinstance(query_id, str) or query_id in ids:
            errors.append(f"missing or duplicate query_id: {query_id!r}")
        else:
            ids.add(query_id)
        if row.get("dataset_id") != dataset:
            errors.append(f"query {query_id} has dataset_id {row.get('dataset_id')!r}")
        source = row.get("source")
        destination = row.get("destination")
        if (
            not isinstance(source, int)
            or isinstance(source, bool)
            or source <= 0
            or not isinstance(destination, int)
            or isinstance(destination, bool)
            or destination <= 0
        ):
            errors.append(f"query {query_id} has invalid source/destination")
        elif source == destination:
            errors.append(f"query {query_id} has identical source and destination")
        interval_start = row.get("interval_start")
        interval_end = row.get("interval_end")
        window_length = row.get("window_length")
        budget = row.get("budget")
        if not all(
            isinstance(value, int) and not isinstance(value, bool)
            for value in (interval_start, interval_end, window_length)
        ):
            errors.append(f"query {query_id} has non-integer interval/window fields")
        elif (
            interval_start < 0
            or interval_end <= interval_start
            or window_length != interval_end - interval_start
        ):
            errors.append(f"query {query_id} has an invalid interval/window")
        if (
            not isinstance(budget, (int, float))
            or isinstance(budget, bool)
            or budget < 0
        ):
            errors.append(f"query {query_id} has an invalid budget")
        metadata = row.get("metadata")
        if not isinstance(metadata, dict):
            errors.append(f"query {query_id} has invalid metadata")
            continue
        split = metadata.get("split")
        center = metadata.get("time_center")
        if split not in pairs:
            errors.append(f"query {query_id} has invalid metadata.split {split!r}")
            continue
        pairs[split].add((source, destination))
        if isinstance(center, int):
            centers[split].add(center)
            pair_centers[(split, source, destination)].add(center)
            if all(isinstance(value, int) for value in (interval_start, interval_end)):
                if interval_start + interval_end != 2 * center:
                    errors.append(f"query {query_id} is not centered at {center}")
        else:
            errors.append(f"query {query_id} has invalid metadata.time_center {center!r}")
        support_end = metadata.get("function_support_end")
        if not isinstance(support_end, (int, float)):
            errors.append(f"query {query_id} lacks metadata.function_support_end")
        elif (
            isinstance(interval_end, (int, float))
            and isinstance(budget, (int, float))
            and interval_end + budget > support_end
        ):
            errors.append(f"query {query_id} exceeds function support")
        if metadata.get("budget_definition") != design["workload"]["budget_definition"]:
            budget_definition_mismatches += 1
        if design["workload"]["budget_definition"] == WITNESS_BUDGET_DEFINITION:
            missing_witness = sorted(
                REQUIRED_WITNESS_METADATA - metadata.keys()
            )
            if missing_witness:
                errors.append(
                    f"query {query_id} lacks witness metadata "
                    f"{missing_witness}"
                )
            else:
                for field in (
                    "witness_path_checksum_sha256",
                    "witness_travel_time_evidence_sha256",
                ):
                    value = metadata.get(field)
                    if (
                        not isinstance(value, str)
                        or len(value) != 64
                        or any(character not in "0123456789abcdef"
                               for character in value)
                    ):
                        errors.append(
                            f"query {query_id} has invalid {field}"
                        )
                if (
                    metadata.get("witness_evaluated_departure_start")
                    != interval_start
                    or metadata.get("witness_evaluated_departure_end")
                    != interval_end
                ):
                    errors.append(
                        f"query {query_id} has mismatched witness interval"
                    )
                if metadata.get("final_generated_budget") != budget:
                    errors.append(
                        f"query {query_id} has mismatched final budget"
                    )
        if metadata.get("evaluation_grid_minutes") != design["workload"]["evaluation_grid_minutes"]:
            grid_mismatches += 1
        if metadata.get("validation_source_destination_present") is not True:
            errors.append(f"query {query_id} lacks graph node validation provenance")
        if metadata.get("validation_path_expected") is not True:
            errors.append(f"query {query_id} lacks path validation provenance")
        payload_checksum = metadata.get("dataset_payload_checksum")
        if not isinstance(payload_checksum, str) or not payload_checksum:
            errors.append(
                f"query {query_id} lacks a dataset payload checksum"
            )
        band = metadata.get("distance_band")
        if split == "evaluation":
            if band not in {"B1", "B2", "B3", "B4", "B5"}:
                errors.append(f"query {query_id} has invalid distance band {band!r}")
            else:
                evaluation_bands[band].add((source, destination))
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
    if budget_definition_mismatches:
        errors.append(
            f"{budget_definition_mismatches} queries have the wrong "
            "budget definition"
        )
    if grid_mismatches:
        errors.append(
            f"{grid_mismatches} queries have the wrong evaluation grid"
        )
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
            try:
                record = validate_manifest(path, dataset, design)
                actual_ids = {row["query_id"] for row in read_jsonl(path)}
            except (OSError, ValueError, json.JSONDecodeError) as failure:
                records.append({
                    "dataset_id": dataset,
                    "path": path.as_posix(),
                    "errors": [f"manifest is invalid: {failure}"],
                })
                continue
            missing = required_ids[dataset] - actual_ids
            if missing:
                record["errors"].append(
                    f"manifest lacks {len(missing)} matrix query variants; first missing ID: {sorted(missing)[0]}"
                )
            record["required_matrix_query_ids"] = len(required_ids[dataset])
            sidecars = [
                (path.with_suffix(".manifest.json"), path, "combined"),
                *[
                    (
                        path.parent / f"{split}.manifest.json",
                        path.parent / f"{split}.jsonl",
                        split,
                    )
                    for split in ("pilot", "warmup", "evaluation")
                ],
            ]
            for sidecar_path, query_path, split in sidecars:
                if not sidecar_path.is_file():
                    record["errors"].append(f"query sidecar is missing: {sidecar_path}")
                    continue
                try:
                    sidecar = load_document(sidecar_path)
                except (OSError, ValueError, json.JSONDecodeError) as failure:
                    record["errors"].append(f"invalid query sidecar {sidecar_path}: {failure}")
                    continue
                if sidecar.get("dataset_id") != dataset:
                    record["errors"].append(
                        f"{sidecar_path} has dataset_id {sidecar.get('dataset_id')!r}"
                    )
                if sidecar.get("query_split") != split:
                    record["errors"].append(
                        f"{sidecar_path} has query_split {sidecar.get('query_split')!r}"
                    )
                if not query_path.is_file():
                    record["errors"].append(f"split query file is missing: {query_path}")
                    continue
                if sidecar.get("output_query_sha256") != sha256_file(query_path):
                    record["errors"].append(f"query checksum mismatch in {sidecar_path}")
                split_rows = read_jsonl(query_path)
                if sidecar.get("number_of_queries") != len(split_rows):
                    record["errors"].append(
                        f"query count mismatch in {sidecar_path}"
                    )
                if split != "combined":
                    expected_split_ids = {
                        row["query_id"]
                        for row in read_jsonl(path)
                        if row.get("metadata", {}).get("split") == split
                    }
                    actual_split_ids = {row.get("query_id") for row in split_rows}
                    if actual_split_ids != expected_split_ids:
                        record["errors"].append(
                            f"{split} query file does not match the combined manifest"
                        )
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
